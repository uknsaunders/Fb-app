package com.listinghound.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    public static synchronized Set<String> seen(Context c, String searchId) {
        Set<String> out = new LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(p(c).getString("seen_" + searchId, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static synchronized void saveSeen(Context c, String searchId, Set<String> ids) {
        List<String> list = new ArrayList<>(ids);
        int from = Math.max(0, list.size() - MAX_SEEN);
        JSONArray a = new JSONArray();
        for (int i = from; i < list.size(); i++) a.put(list.get(i));
        p(c).edit().putString("seen_" + searchId, a.toString()).apply();
    }

    public static synchronized void clearSeen(Context c, String searchId) {
        p(c).edit().remove("seen_" + searchId).apply();
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

    public static synchronized void clearFinds(Context c) {
        p(c).edit().remove("finds").apply();
    }
}
