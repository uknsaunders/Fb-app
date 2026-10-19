package com.listinghound.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Everything the app keeps, in one SharedPreferences file. */
public final class Store {
    private Store() {}

    public static final int[] INTERVALS = {2, 3, 5, 10, 15, 30, 60};
    public static final int[] STOP_AFTER_HOURS = {0, 1, 2, 4, 8, 12, 24};

    private static final int MAX_SEEN = 800;
    private static final int MAX_FINDS = 100;

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("hound", Context.MODE_PRIVATE);
    }

    // ---- Settings & state ----

    public static int intervalMin(Context c) { return p(c).getInt("interval", 5); }
    public static void setIntervalMin(Context c, int m) { p(c).edit().putInt("interval", m).apply(); }

    public static int stopAfterHours(Context c) { return p(c).getInt("stopAfter", 0); }
    public static void setStopAfterHours(Context c, int h) { p(c).edit().putInt("stopAfter", h).apply(); }

    public static boolean running(Context c) { return p(c).getBoolean("running", false); }
    public static void setRunning(Context c, boolean r) { p(c).edit().putBoolean("running", r).apply(); }

    public static long stopAt(Context c) { return p(c).getLong("stopAt", 0); }
    public static void setStopAt(Context c, long t) { p(c).edit().putLong("stopAt", t).apply(); }

    public static long lastCheck(Context c) { return p(c).getLong("lastCheck", 0); }
    public static void setLastCheck(Context c, long t) { p(c).edit().putLong("lastCheck", t).apply(); }

    public static long nextCheck(Context c) { return p(c).getLong("nextCheck", 0); }
    public static void setNextCheck(Context c, long t) { p(c).edit().putLong("nextCheck", t).apply(); }

    public static String lastResult(Context c) { return p(c).getString("lastResult", ""); }
    public static void setLastResult(Context c, String s) { p(c).edit().putString("lastResult", s).apply(); }

    public static boolean loginWarned(Context c) { return p(c).getBoolean("loginWarned", false); }
    public static void setLoginWarned(Context c, boolean b) { p(c).edit().putBoolean("loginWarned", b).apply(); }

    /** Move to the next slower (+1) or faster (-1) interval step. Returns the new value. */
    public static int stepInterval(Context c, int dir) {
        int cur = intervalMin(c);
        int idx = 0;
        for (int i = 0; i < INTERVALS.length; i++) if (INTERVALS[i] <= cur) idx = i;
        idx = Math.max(0, Math.min(INTERVALS.length - 1, idx + dir));
        setIntervalMin(c, INTERVALS[idx]);
        return INTERVALS[idx];
    }

    // ---- Searches ----

    public static synchronized List<Search> searches(Context c) {
        List<Search> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString("searches", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Search.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static synchronized void saveSearches(Context c, List<Search> list) {
        JSONArray a = new JSONArray();
        for (Search s : list) a.put(s.toJson());
        p(c).edit().putString("searches", a.toString()).apply();
    }

    public static Search search(Context c, String id) {
        for (Search s : searches(c)) if (s.id.equals(id)) return s;
        return null;
    }

    public static synchronized void upsert(Context c, Search s) {
        List<Search> list = searches(c);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(s.id)) {
                list.set(i, s);
                found = true;
            }
        }
        if (!found) list.add(s);
        saveSearches(c, list);
    }

    public static synchronized void delete(Context c, String id) {
        List<Search> list = searches(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        saveSearches(c, list);
        clearSeen(c, id);
    }

    // ---- Listings already seen, per search ----

    public static boolean hasBaseline(Context c, String searchId) {
        return p(c).contains("seen_" + searchId);
    }

    /** What a listing looked like the last time a search saw it. */
    public static class Seen {
        public double amount;
        public String title;

        public Seen(double amount, String title) {
            this.amount = amount;
            this.title = title == null ? "" : title;
        }
    }

    public static synchronized LinkedHashMap<String, Seen> seen(Context c, String searchId) {
        LinkedHashMap<String, Seen> out = new LinkedHashMap<>();
        String raw = p(c).getString("seen_" + searchId, "{}");
        try {
            if (raw.trim().startsWith("[")) {
                // Older versions kept only the IDs
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) out.put(a.getString(i), new Seen(-1, ""));
            } else {
                JSONObject o = new JSONObject(raw);
                JSONArray ids = o.optJSONArray("order");
                if (ids != null) {
                    for (int i = 0; i < ids.length(); i++) {
                        String id = ids.getString(i);
                        JSONArray v = o.optJSONArray(id);
                        out.put(id, v == null ? new Seen(-1, "") : new Seen(v.optDouble(0, -1), v.optString(1, "")));
                    }
                }
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static synchronized void saveSeen(Context c, String searchId, LinkedHashMap<String, Seen> map) {
        List<String> ids = new ArrayList<>(map.keySet());
        int from = Math.max(0, ids.size() - MAX_SEEN);
        JSONObject o = new JSONObject();
        JSONArray order = new JSONArray();
        try {
            for (int i = from; i < ids.size(); i++) {
                String id = ids.get(i);
                Seen v = map.get(id);
                order.put(id);
                JSONArray arr = new JSONArray();
                arr.put(v.amount);
                arr.put(v.title);
                o.put(id, arr);
            }
            o.put("order", order);
        } catch (JSONException ignored) {
        }
        p(c).edit().putString("seen_" + searchId, o.toString()).apply();
    }

    public static synchronized void clearSeen(Context c, String searchId) {
        p(c).edit().remove("seen_" + searchId).apply();
    }

    // ---- Check timing (for responsiveness stats) ----

    /** Last time this search was checked successfully. */
    public static long lastOk(Context c, String searchId) { return p(c).getLong("ok_" + searchId, 0); }
    public static void setLastOk(Context c, String searchId, long t) { p(c).edit().putLong("ok_" + searchId, t).apply(); }

    /** Times of recent successful checks, oldest first. */
    public static synchronized List<Long> checkTimes(Context c) {
        List<Long> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString("checkLog", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getLong(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static synchronized void logCheck(Context c, long t) {
        List<Long> all = checkTimes(c);
        all.add(t);
        JSONArray a = new JSONArray();
        for (int i = Math.max(0, all.size() - 400); i < all.size(); i++) a.put(all.get(i));
        p(c).edit().putString("checkLog", a.toString()).apply();
    }

    // ---- Recent finds ----

    public static synchronized List<Listing> finds(Context c) {
        List<Listing> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString("finds", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Listing.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Adds new finds to the top of the list. */
    public static synchronized void addFinds(Context c, List<Listing> fresh) {
        List<Listing> all = new ArrayList<>(fresh);
        all.addAll(finds(c));
        JSONArray a = new JSONArray();
        for (int i = 0; i < Math.min(MAX_FINDS, all.size()); i++) a.put(all.get(i).toJson());
        p(c).edit().putString("finds", a.toString()).apply();
    }

    /** Records when a found listing was posted (-1 = couldn't tell). */
    public static synchronized void setListedAt(Context c, String findKey, long listedAt) {
        List<Listing> all = finds(c);
        JSONArray a = new JSONArray();
        for (Listing l : all) {
            if (l.key().equals(findKey)) {
                l.listedAt = listedAt;
                if ("new".equals(l.kind) && listedAt > 0 && l.foundAt - listedAt > Timing.RESURFACED) l.kind = "renewed";
            }
            a.put(l.toJson());
        }
        p(c).edit().putString("finds", a.toString()).apply();
    }

    public static synchronized void saveFinds(Context c, List<Listing> list) {
        JSONArray a = new JSONArray();
        for (Listing l : list) a.put(l.toJson());
        p(c).edit().putString("finds", a.toString()).apply();
    }

    public static synchronized void removeFind(Context c, String findKey) {
        List<Listing> all = finds(c);
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).key().equals(findKey)) all.remove(i);
        saveFinds(c, all);
    }

    public static synchronized void clearFinds(Context c) {
        p(c).edit().remove("finds").apply();
    }
}
