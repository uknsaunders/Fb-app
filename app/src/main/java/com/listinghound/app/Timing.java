package com.listinghound.app;

import android.content.Context;

import java.util.List;

/** Works out how much of each alert's delay was Facebook and how much was the app. */
public final class Timing {
    private Timing() {}

    private static final long DAY = 24 * 3600_000L;
    /** Listed this long before it first showed up = an older listing resurfacing, not a review hold. */
    static final long RESURFACED = 2 * DAY;

    static String mins(long ms) {
        long m = Math.round(ms / 60_000.0);
        if (m < 1) return "under 1 min";
        if (m < 90) return m + " min";
        if (m < 48 * 60) return String.format(java.util.Locale.UK, "%.1f h", m / 60.0);
        long d = Math.round(m / 1440.0);
        return d + " days";
    }

    /** Time, plus the date when it isn't the same day as the reference. */
    static String when(Context c, long t, long ref) {
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        a.setTimeInMillis(t);
        b.setTimeInMillis(ref);
        boolean sameDay = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
        if (sameDay) return Notif.time(c, t);
        return android.text.format.DateUtils.formatDateTime(c, t,
                android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
                + " " + Notif.time(c, t);
    }

    static boolean resurfaced(Listing l) {
        long ref = l.prevCheckAt > 0 ? l.prevCheckAt : l.foundAt;
        return ref - l.listedAt > RESURFACED;
    }

    /** Explanation for one new-listing find, or "" if there isn't enough information. */
    public static String explain(Context c, Listing l) {
        if ("renewed".equals(l.kind) && l.listedAt > 0) {
            return "First listed " + when(c, l.listedAt, l.foundAt) + " · bumped back up, seen " + Notif.time(c, l.foundAt)
                    + "\nStill unsold after " + mins(l.foundAt - l.listedAt) + " — seller may take an offer";
        }
        if (!"new".equals(l.kind) || l.listedAt <= 0) return "";
        long total = l.foundAt - l.listedAt;
        StringBuilder b = new StringBuilder();
        b.append("Listed ").append(when(c, l.listedAt, l.foundAt));
        if (l.prevCheckAt > 0) b.append(" · previous check ").append(when(c, l.prevCheckAt, l.foundAt));
        b.append(" · spotted ").append(Notif.time(c, l.foundAt));
        if (resurfaced(l)) {
            b.append("\nOlder listing (").append(mins(total))
                    .append(" old) that has only now appeared in results — probably renewed by the seller");
        } else {
            b.append("\n").append(mins(total)).append(" from listing to alert");
            if (l.prevCheckAt > 0) {
                if (l.listedAt < l.prevCheckAt) {
                    b.append(" — Facebook held it at least ").append(mins(l.prevCheckAt - l.listedAt))
                            .append(", app caught it within ").append(mins(l.foundAt - l.prevCheckAt))
                            .append(" of release");
                } else {
                    b.append(" — no Facebook delay, app caught it at the next check");
                }
            }
        }
        if (l.prevCheckAt > 0) {
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
        int n = 0, held = 0, old = 0;
        long totalSum = 0, fbSum = 0, appSum = 0;
        for (Listing l : finds) {
            if ("renewed".equals(l.kind)) {
                old++;
                continue;
            }
            if (!"new".equals(l.kind) || l.listedAt <= 0 || l.prevCheckAt <= 0) continue;
            if (resurfaced(l)) {
                old++;
                continue;
            }
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
        if (old > 0) {
            if (b.length() > 0) b.append('\n');
            b.append(old).append(" older listing").append(old == 1 ? "" : "s")
                    .append(" renewed by sellers left out of these figures.");
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
