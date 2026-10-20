package com.listinghound.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Facebook only tags listings with a category number. This learns which number belongs to which
 * named category by looking at each category's own Marketplace page once, then caches the table.
 */
public final class Categories {
    private Categories() {}

    private static final long MAX_AGE = 14L * 24 * 3600_000L;
    private static volatile Map<String, String> map = new HashMap<>();
    private static volatile long learnedAt = 0;

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("cats", Context.MODE_PRIVATE);
    }

    public static void load(Context c) {
        if (!map.isEmpty()) return;
        Map<String, String> m = new HashMap<>();
        try {
            JSONObject o = new JSONObject(p(c).getString("map", "{}"));
            Iterator<String> k = o.keys();
            while (k.hasNext()) {
                String id = k.next();
                m.put(id, o.getString(id));
            }
        } catch (Exception ignored) {
        }
        map = m;
        learnedAt = p(c).getLong("at", 0);
    }

    public static boolean known() {
        return !map.isEmpty();
    }

    public static boolean stale() {
        return map.isEmpty() || System.currentTimeMillis() - learnedAt > MAX_AGE;
    }

    /** Category slug for a listing's category number, or null if not known. */
    public static String slugFor(String categoryId) {
        if (categoryId == null || categoryId.isEmpty()) return null;
        return map.get(categoryId);
    }

    public static String nameFor(String categoryId) {
        String slug = slugFor(categoryId);
        return slug == null ? "" : Search.categoryLabel(slug);
    }

    public interface Progress { void at(int done, int total); }

    /** Visits each category page and records which numbers appear there. Run off the main thread. */
    public static boolean learn(Context c, Progress progress) {
        String loc = "";
        for (Search s : Store.searches(c)) {
            String l = s.location.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
            if (!l.isEmpty()) {
                loc = l;
                break;
            }
        }
        // counts[id][slug]
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        int total = Search.CATEGORIES.length - 1, done = 0, ok = 0;
        for (String[] cat : Search.CATEGORIES) {
            if (cat[0].isEmpty()) continue;
            String url = Fetcher.FB + "/marketplace/" + (loc.isEmpty() ? "category" : loc) + "/" + cat[0];
            Fetcher.Result r = Fetcher.fetchUrl(url);
            if (r.error == null && !r.loginWall && !r.listings.isEmpty()) {
                ok++;
                for (Listing l : r.listings) {
                    if (l.categoryId.isEmpty()) continue;
                    counts.computeIfAbsent(l.categoryId, x -> new HashMap<>()).merge(cat[0], 1, Integer::sum);
                }
            }
            done++;
            if (progress != null) progress.at(done, total);
            try {
                Thread.sleep(800 + (long) (Math.random() * 900));
            } catch (InterruptedException ignored) {
            }
        }
        if (ok == 0) return false;
        // Each number belongs to the category page it appeared on most
        Map<String, String> m = new HashMap<>(map);
        for (Map.Entry<String, Map<String, Integer>> e : counts.entrySet()) {
            String best = null;
            int bestN = 0;
            for (Map.Entry<String, Integer> s : e.getValue().entrySet()) {
                if (s.getValue() > bestN) {
                    bestN = s.getValue();
                    best = s.getKey();
                }
            }
            if (best != null) m.put(e.getKey(), best);
        }
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, String> e : m.entrySet()) o.put(e.getKey(), e.getValue());
        } catch (Exception ignored) {
        }
        map = m;
        learnedAt = System.currentTimeMillis();
        p(c).edit().putString("map", o.toString()).putLong("at", learnedAt).apply();
        return true;
    }
}
