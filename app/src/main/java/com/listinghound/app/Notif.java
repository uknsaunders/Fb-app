package com.listinghound.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** All the app's notifications. */
public final class Notif {
    private Notif() {}

    static final String CH_STATUS = "status";
    static final String CH_FINDS = "finds";
    static final String CH_ALERTS = "alerts";

    static final int ID_STATUS = 1;
    static final int ID_LOGIN = 2;
    static final int ID_SUMMARY = 3;
    static final String GROUP = "finds";

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel st = new NotificationChannel(CH_STATUS, "Watching status", NotificationManager.IMPORTANCE_LOW);
        st.setDescription("Shows while searches are being checked, with Stop and Slower buttons");
        st.setShowBadge(false);
        NotificationChannel fd = new NotificationChannel(CH_FINDS, "New listings", NotificationManager.IMPORTANCE_HIGH);
        fd.setDescription("A new listing matches one of your searches");
        NotificationChannel al = new NotificationChannel(CH_ALERTS, "Problems", NotificationManager.IMPORTANCE_DEFAULT);
        al.setDescription("Login needed or checks failing");
        nm.createNotificationChannel(st);
        nm.createNotificationChannel(fd);
        nm.createNotificationChannel(al);
    }

    private static PendingIntent serviceAction(Context c, String action, int req) {
        Intent i = new Intent(c, WatchService.class).setAction(action);
        return PendingIntent.getService(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static String time(Context c, long t) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(t));
    }

    static Notification status(Context c, boolean checking) {
        int mins = Store.intervalMin(c);
        int n = 0;
        for (Search s : Store.searches(c)) if (s.enabled) n++;
        String title = "Watching " + n + " search" + (n == 1 ? "" : "es") + " · every " + mins + " min";

        StringBuilder text = new StringBuilder();
        if (checking) {
            text.append("Checking now…");
        } else {
            long next = Store.nextCheck(c);
            if (next > 0) text.append("Next check ").append(time(c, next));
            String last = Store.lastResult(c);
            if (!last.isEmpty()) text.append(" · last: ").append(last);
        }
        long stopAt = Store.stopAt(c);
        if (stopAt > 0) text.append(" · stops ").append(time(c, stopAt));

        return new Notification.Builder(c, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text.toString())
                .setStyle(new Notification.BigTextStyle().bigText(text.toString()))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp(c))
                .addAction(new Notification.Action.Builder(null, "Check now",
                        serviceAction(c, WatchService.ACT_CHECK, 11)).build())
                .addAction(new Notification.Action.Builder(null, "Slower",
                        serviceAction(c, WatchService.ACT_SLOWER, 12)).build())
                .addAction(new Notification.Action.Builder(null, "Stop",
                        serviceAction(c, WatchService.ACT_STOP, 13)).build())
                .build();
    }

    static void finds(Context c, List<Listing> fresh) {
        if (fresh.isEmpty()) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);

        if (fresh.size() > 6) {
            Notification.InboxStyle inbox = new Notification.InboxStyle();
            for (int i = 0; i < Math.min(7, fresh.size()); i++) inbox.addLine(line(fresh.get(i)));
            if (fresh.size() > 7) inbox.setSummaryText("+" + (fresh.size() - 7) + " more in the app");
            nm.notify(ID_SUMMARY, new Notification.Builder(c, CH_FINDS)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(fresh.size() + " new or changed listings")
                    .setContentText(line(fresh.get(0)))
                    .setStyle(inbox)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(c))
                    .build());
            return;
        }

        for (Listing l : fresh) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(l.url()));
            PendingIntent pi = PendingIntent.getActivity(c, l.key().hashCode(), view,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String title = l.headline();
            String text = l.searchName + (l.city.isEmpty() ? "" : " · " + l.city)
                    + (l.km >= 0 ? " (" + Math.round(l.km) + " km)" : "");
            if ("new".equals(l.kind) && l.listedAt > 0) text += " · listed " + time(c, l.listedAt);
            if ("updated".equals(l.kind) && !l.oldTitle.isEmpty()) text += " · was “" + l.oldTitle + "”";
            Notification.Builder b = new Notification.Builder(c, CH_FINDS)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setGroup(GROUP)
                    .setWhen(l.foundAt)
                    .setShowWhen(true)
                    .setContentIntent(pi);
            if (l.thumb != null) {
                b.setLargeIcon(l.thumb);
                b.setStyle(new Notification.BigPictureStyle().bigPicture(l.thumb).setSummaryText(text));
            }
            nm.notify(l.key().hashCode(), b.build());
        }
        if (fresh.size() > 1) {
            nm.notify(ID_SUMMARY, new Notification.Builder(c, CH_FINDS)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(fresh.size() + " new listings")
                    .setGroup(GROUP)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(c))
                    .build());
        }
    }

    private static String line(Listing l) {
        return l.headline();
    }

    static void problem(Context c, String title, String text, boolean openLogin) {
        Intent i = new Intent(c, openLogin ? LoginActivity.class : MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(c, openLogin ? 21 : 22, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSystemService(NotificationManager.class).notify(ID_LOGIN,
                new Notification.Builder(c, CH_ALERTS)
                        .setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .build());
    }

    static void cancelProblem(Context c) {
        c.getSystemService(NotificationManager.class).cancel(ID_LOGIN);
    }
}
