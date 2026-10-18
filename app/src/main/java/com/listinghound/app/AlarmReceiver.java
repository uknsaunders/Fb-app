package com.listinghound.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Wakes the watcher for each check, even when the phone is in deep sleep. */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Store.running(c)) return;
        Intent i = new Intent(c, WatchService.class).setAction(WatchService.ACT_ALARM);
        try {
            c.startForegroundService(i);
        } catch (Exception e) {
            try {
                c.startService(i);
            } catch (Exception ignored) {
            }
        }
    }

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static boolean canBeExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        return c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    public static void set(Context c, long at) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        try {
            if (canBeExact(c)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c));
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c));
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c));
        }
    }

    public static void cancel(Context c) {
        c.getSystemService(AlarmManager.class).cancel(pi(c));
    }
}
