package com.listinghound.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** One pass over every enabled search. Runs on a background thread. */
public final class Checker {
    private Checker() {}

    public static class Summary {
        public final List<Listing> fresh = new ArrayList<>();
        public int searched;
        public int failed;
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
            Set<String> seen = Store.seen(c, s.id);
            for (Listing l : r.listings) {
                if (!seen.contains(l.id)) {
                    if (!baseline && s.matches(l)) {
                        l.searchName = s.displayName();
                        l.foundAt = System.currentTimeMillis();
                        sum.fresh.add(l);
                    }
                    seen.add(l.id);
                }
            }
            Store.saveSeen(c, s.id, seen);
        }

        // Thumbnails for the first few alerts only
        for (int i = 0; i < Math.min(5, sum.fresh.size()); i++) {
            Listing l = sum.fresh.get(i);
            l.thumb = Fetcher.thumbnail(l.imageUrl);
        }
        if (!sum.fresh.isEmpty()) Store.addFinds(c, sum.fresh);

        if (sum.searched == 0) {
            sum.text = "No searches switched on";
        } else if (sum.loginNeeded && sum.failed == sum.searched) {
            sum.text = "Facebook wants you to log in";
        } else if (sum.failed == sum.searched) {
            sum.text = "Couldn't reach Facebook" + (sum.lastError != null ? " (" + sum.lastError + ")" : "");
        } else {
            sum.text = sum.fresh.size() + " new";
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
}
