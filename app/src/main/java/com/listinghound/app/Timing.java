package com.listinghound.app;

import android.content.Context;

import java.util.List;

/** Works out how much of each alert's delay was Facebook and how much was the app. */
public final class Timing {
    private Timing() {}

    private static String mins(long ms) {
        long m = Math.round(ms / 60_000.0);
        if (m < 1) return "under 1 min";
        if (m < 90) return m + " min";
        return String.format(java.util.Locale.UK, "%.1f h", m / 60.0);
    }

    /** Explanation for one new-listing find, or "" if there isn't enough information. */
    public static String explain(Context c, Listing l) {
        if (!"new".equals(l.kind) || l.listedAt <= 0) return "";
        long total = l.foundAt - l.listedAt;
        StringBuilder b = new StringBuilder();
        b.append("Listed ").append(Notif.time(c, l.listedAt));
        if (l.prevCheckAt > 0) b.append(" · previous check ").append(Notif.time(c, l.prevCheckAt));
        b.append(" · spotted ").append(Notif.time(c, l.foundAt));
        b.append("\n").append(mins(total)).append(" from listing to alert");
        if (l.prevCheckAt > 0) {
            if (l.listedAt < l.prevCheckAt) {
                // It existed at the previous check but wasn't in the results yet
                b.append(" — Facebook held it at least ").append(mins(l.prevCheckAt - l.listedAt))
                        .append(", app caught it within ").append(mins(l.foundAt - l.prevCheckAt))
                        .append(" of release");
            } else {
                b.append(" — no Facebook delay, app caught it at the next check");
            }
            long interval = Store.intervalMin(c) * 60_000L;
            if (l.foundAt - l.prevCheckAt > interval * 3 / 2 + 60_000L) {
                b.append("\n⚠ Gap of ").append(mins(l.foundAt - l.prevCheckAt))
                        .append(" between checks — the phone paused the app");
            }
        }
        return b.toString();
    }

    /** One-paragraph summary across recent finds and checks, or "" if nothing to report yet. */
    public static String summary(Context c) {
        List<Listing> finds = Store.finds(c);
        int n = 0, held = 0;
        long totalSum = 0, fbSum = 0, appSum = 0;
        for (Listing l : finds) {
            if (!"new".equals(l.kind) || l.listedAt <= 0 || l.prevCheckAt <= 0) continue;
            if (n >= 20) break;
            n++;
            totalSum += l.foundAt - l.listedAt;
            if (l.listedAt < l.prevCheckAt) {
                held++;
                fbSum += l.prevCheckAt - l.listedAt;
                appSum += l.foundAt - l.prevCheckAt;
            } else {
                appSum += l.foundAt - l.listedAt;
            }
        }

        StringBuilder b = new StringBuilder();
        if (n > 0) {
            b.append("Last ").append(n).append(" new listing").append(n == 1 ? "" : "s").append(": average ")
                    .append(mins(totalSum / n)).append(" from listing to alert.\n");
            b.append("Facebook held back ").append(held).append(" of ").append(n);
            if (held > 0) b.append(" (average at least ").append(mins(fbSum / held)).append(")");
            b.append(". App's share averaged at most ").append(mins(appSum / n)).append('.');
        }

        // How often checks ran on time in the last 24 hours
        List<Long> times = Store.checkTimes(c);
        long since = System.currentTimeMillis() - 24 * 3600_000L;
        long allowed = Store.intervalMin(c) * 60_000L * 3 / 2 + 60_000L;
        int gaps = 0, late = 0;
        long worst = 0;
        for (int i = 1; i < times.size(); i++) {
            if (times.get(i) < since) continue;
            long gap = times.get(i) - times.get(i - 1);
            if (gap > 3 * 3600_000L) continue; // watcher was stopped
            gaps++;
            if (gap > allowed) late++;
            worst = Math.max(worst, gap);
        }
        if (gaps >= 3) {
            if (b.length() > 0) b.append('\n');
            int pct = Math.round(100f * (gaps - late) / gaps);
            b.append("Checks on time (24 h): ").append(pct).append("% of ").append(gaps);
            if (late > 0) b.append(" · longest gap ").append(mins(worst));
        }
        return b.toString();
    }
}
