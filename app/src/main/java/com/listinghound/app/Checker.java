package com.listinghound.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.LinkedHashMap;
import java.util.Locale;

/** One pass over every enabled search. Runs on a background thread. */
public final class Checker {
    private Checker() {}

    public static class Summary {
        public final List<Listing> fresh = new ArrayList<>();
        public int searched;
        public int failed;
        public int ok;
        public boolean loginNeeded;
        public String lastError;
        public String text = "";
    }

    private static final Random RND = new Random();

    public static Summary runAll(Context c) {
        Summary sum = new Summary();
        List<Search> list = Store.searches(c);
        boolean first = true;
        for (Search s : list) {
            if (!s.enabled || s.query.trim().isEmpty()) continue;
            if (!first) pause(2000 + RND.nextInt(3000));
            first = false;
            sum.searched++;

            Fetcher.Result r = Fetcher.fetch(s);
            if (r.error != null) {
                sum.failed++;
                sum.lastError = r.error;
                continue;
            }
            if (r.loginWall) {
                sum.failed++;
                sum.loginNeeded = true;
                continue;
            }

            boolean baseline = !Store.hasBaseline(c, s.id);
            long prevOk = Store.lastOk(c, s.id);
            LinkedHashMap<String, Store.Seen> seen = Store.seen(c, s.id);
            long now = System.currentTimeMillis();
            for (Listing l : r.listings) {
                Store.Seen prev = seen.remove(l.id); // re-added below, so recent ones stay at the end
                if (!baseline && s.matches(l)) {
                    if (prev == null) {
                        l.kind = "new";
                        l.prevCheckAt = prevOk;
                        sum.fresh.add(l);
                    } else if (s.alertDrops && prev.amount > 0 && l.amount >= 0 && l.amount < prev.amount - 0.005) {
                        l.kind = "drop";
                        l.oldPrice = formatLike(l.price, prev.amount);
                        sum.fresh.add(l);
                    } else if (s.alertUpdates && !prev.title.isEmpty() && !l.title.isEmpty()
                            && !prev.title.equalsIgnoreCase(l.title)) {
                        l.kind = "updated";
                        l.oldTitle = prev.title;
                        sum.fresh.add(l);
                    }
                }
                l.searchName = s.displayName();
                l.foundAt = now;
                seen.put(l.id, new Store.Seen(l.amount, l.title));
            }
            Store.saveSeen(c, s.id, seen);
            Store.setLastOk(c, s.id, now);
            sum.ok++;
        }

        // When were the new ones posted? (shows how quickly they were spotted)
        for (int i = 0; i < Math.min(5, sum.fresh.size()); i++) {
            Listing l = sum.fresh.get(i);
            if ("new".equals(l.kind) && l.listedAt == 0) l.listedAt = Fetcher.listedAt(l.id);
        }

        // Thumbnails for the first few alerts only
        for (int i = 0; i < Math.min(5, sum.fresh.size()); i++) {
            Listing l = sum.fresh.get(i);
            l.thumb = Fetcher.thumbnail(l.imageUrl);
        }
        if (!sum.fresh.isEmpty()) Store.addFinds(c, sum.fresh);
        if (sum.ok > 0) Store.logCheck(c, System.currentTimeMillis());

        if (sum.searched == 0) {
            sum.text = "No searches switched on";
        } else if (sum.loginNeeded && sum.failed == sum.searched) {
            sum.text = "Facebook wants you to log in";
        } else if (sum.failed == sum.searched) {
            sum.text = "Couldn't reach Facebook" + (sum.lastError != null ? " (" + sum.lastError + ")" : "");
        } else {
            int n = 0, d = 0, u = 0;
            for (Listing l : sum.fresh) {
                if ("drop".equals(l.kind)) d++;
                else if ("updated".equals(l.kind)) u++;
                else n++;
            }
            sum.text = n + " new";
            if (d > 0) sum.text += ", " + d + " price drop" + (d == 1 ? "" : "s");
            if (u > 0) sum.text += ", " + u + " updated";
            if (sum.failed > 0) sum.text += " · " + sum.failed + " search" + (sum.failed == 1 ? "" : "es") + " failed";
        }
        return sum;
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Old price written in the same style as the new one, e.g. "£40". */
    static String formatLike(String newPrice, double amount) {
        String num = amount == Math.rint(amount)
                ? String.valueOf((long) amount)
                : String.format(Locale.UK, "%.2f", amount);
        String prefix = "";
        if (newPrice != null) {
            int i = 0;
            while (i < newPrice.length() && !Character.isDigit(newPrice.charAt(i))) i++;
            prefix = newPrice.substring(0, i);
        }
        return prefix + num;
    }
}
