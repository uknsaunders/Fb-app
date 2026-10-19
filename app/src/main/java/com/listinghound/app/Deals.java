package com.listinghound.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** DealRank: scores what's for sale now and suggests the best buys. */
public final class Deals {
    private Deals() {}

    // ---------- Settings ----------

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("deals", Context.MODE_PRIVATE);
    }

    public static boolean dailyOn(Context c) { return p(c).getBoolean("dailyOn", true); }
    public static void setDailyOn(Context c, boolean b) { p(c).edit().putBoolean("dailyOn", b).apply(); }

    public static int hour(Context c) { return p(c).getInt("hour", 9); }
    public static void setHour(Context c, int h) { p(c).edit().putInt("hour", h).apply(); }

    /** How much each factor counts, 0–100. */
    public static int weight(Context c, String k) { return p(c).getInt("w_" + k, defaultWeight(k)); }
    public static void setWeight(Context c, String k, int v) { p(c).edit().putInt("w_" + k, v).apply(); }

    static int defaultWeight(String k) {
        switch (k) {
            case "price": return 100;
            case "drop": return 60;
            case "renewed": return 50;
            case "fresh": return 40;
            case "distance": return 50;
            default: return 50;
        }
    }

    public static final String[][] FACTORS = {
            {"price", "Cheaper than typical price"},
            {"drop", "Price has been reduced"},
            {"renewed", "Old listing, still unsold (seller may haggle)"},
            {"fresh", "Just posted (good ones go fast)"},
            {"distance", "Penalty for being further away"}};

    /** Score needed for a "Consider this" alert. */
    public static int minScore(Context c) { return p(c).getInt("minScore", 75); }
    public static void setMinScore(Context c, int v) { p(c).edit().putInt("minScore", v).apply(); }

    /** How far beyond each search's radius to look, as a % of the radius. */
    public static int extraPct(Context c) { return p(c).getInt("extraPct", 50); }
    public static void setExtraPct(Context c, int v) { p(c).edit().putInt("extraPct", v).apply(); }

    public static long lastScan(Context c) { return p(c).getLong("lastScan", 0); }
    public static String lastScanNote(Context c) { return p(c).getString("lastNote", ""); }

    // ---------- Stored results ----------

    public static synchronized List<Listing> results(Context c) {
        List<Listing> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString("results", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Listing.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static synchronized void saveResults(Context c, List<Listing> list, String note) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < Math.min(40, list.size()); i++) a.put(list.get(i).toJson());
        p(c).edit().putString("results", a.toString())
                .putLong("lastScan", System.currentTimeMillis())
                .putString("lastNote", note).apply();
    }

    /** Deals already notified, with the score at the time (so we only repeat if it jumps). */
    private static Map<String, Integer> notified(Context c) {
        Map<String, Integer> m = new HashMap<>();
        for (String e : p(c).getString("notified", "").split(",")) {
            int i = e.indexOf(':');
            if (i > 0) {
                try {
                    m.put(e.substring(0, i), Integer.parseInt(e.substring(i + 1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return m;
    }

    private static void saveNotified(Context c, Map<String, Integer> m) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (n++ > 300) break;
            if (b.length() > 0) b.append(',');
            b.append(e.getKey()).append(':').append(e.getValue());
        }
        p(c).edit().putString("notified", b.toString()).apply();
    }

    // ---------- Scan ----------

    private static final String[] RED_FLAGS = {"wanted", "spares", "repair", "broken", "faulty",
            "parts only", "not working", "for parts", "swap", "looking for", "iso "};

    public static String scan(Context c) {
        List<Listing> all = new ArrayList<>();
        int searched = 0, failed = 0;
        int extra = extraPct(c);
        for (Search s : Store.searches(c)) {
            if (!s.enabled || s.query.trim().isEmpty()) continue;
            searched++;
            List<Listing> found = scanSearch(c, s, extra);
            if (found == null) failed++;
            else all.addAll(found);
        }

        // Look up posting times for the most promising, then score again
        Collections.sort(all, (a, b) -> b.score - a.score);
        int lookups = 0;
        for (Listing l : all) {
            if (lookups >= 12) break;
            if (l.listedAt == 0) {
                l.listedAt = Fetcher.listedAt(l.id);
                lookups++;
            }
        }
        Map<String, Search> byId = new HashMap<>();
        for (Search s : Store.searches(c)) byId.put(s.id, s);
        for (Listing l : all) rescore(c, l, byId.get(l.searchId));
        Collections.sort(all, (a, b) -> b.score - a.score);

        String note = searched == 0 ? "No searches switched on"
                : failed == searched ? "Couldn't reach Facebook"
                : all.size() + " listings scored across " + searched + " search" + (searched == 1 ? "" : "es");
        saveResults(c, all, note);
        notifyBest(c, all);
        return note;
    }

    /** Fetches newest and cheapest, a bit beyond the radius. Returns null on failure. */
    private static List<Listing> scanSearch(Context c, Search s, int extraPct) {
        int wide = s.radiusKm > 0 ? Math.round(s.radiusKm * (1 + extraPct / 100f)) : 0;
        LinkedHashMap<String, Listing> got = new LinkedHashMap<>();
        boolean ok = false;
        for (String sort : new String[]{"creation_time_descend", "price_ascend"}) {
            Fetcher.Result r = Fetcher.fetchUrl(Fetcher.buildUrl(s, sort, wide));
            if (r.error != null || r.loginWall) continue;
            ok = true;
            for (Listing l : r.listings) if (!got.containsKey(l.id)) got.put(l.id, l);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
        }
        if (!ok) return null;

        double[] centre = s.radiusKm > 0 && !s.location.trim().isEmpty() ? Geo.centre(c, s) : null;
        LinkedHashMap<String, Store.Seen> seen = Store.seen(c, s.id);
        List<Listing> out = new ArrayList<>();
        for (Listing l : got.values()) {
            if (!s.matches(l)) continue;
            if (centre != null) {
                double[] at = Geo.of(c, l);
                if (at != null) l.km = Geo.km(centre, at);
            }
            double limit = s.radiusKm * (1 + extraPct / 100f) * 1.15 + 3;
            if (centre != null && l.km > limit) continue;
            l.outside = centre != null && l.km > s.radiusKm * 1.15 + 3;
            l.searchName = s.displayName();
            l.searchId = s.id;
            l.foundAt = System.currentTimeMillis();
            Store.Seen prev = seen.get(l.id);
            if (prev != null && prev.amount > 0 && l.amount >= 0 && l.amount < prev.amount - 0.005) {
                l.kind = "drop";
                l.oldPrice = Checker.formatLike(l.price, prev.amount);
            }
            out.add(l);
        }
        // Price drops the watcher recorded earlier
        for (Listing f : Store.finds(c)) {
            if (!"drop".equals(f.kind) || !s.id.equals(f.searchId)) continue;
            for (Listing l : out) {
                if (l.id.equals(f.id) && !"drop".equals(l.kind)) {
                    l.kind = "drop";
                    l.oldPrice = f.oldPrice;
                }
            }
        }
        // Typical price for this search = median of sensible prices
        List<Double> prices = new ArrayList<>();
        for (Listing l : out) if (l.amount > 1.5 && !redFlag(l)) prices.add(l.amount);
        Collections.sort(prices);
        double median = prices.isEmpty() ? -1 : prices.get(prices.size() / 2);
        for (Listing l : out) {
            l.typical = median;
            rescore(c, l, s);
        }
        return out;
    }

    static boolean redFlag(Listing l) {
        String t = " " + (l.title == null ? "" : l.title).toLowerCase(Locale.ROOT) + " ";
        for (String w : RED_FLAGS) if (t.contains(w)) return true;
        return false;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** DealRank 0–100 with plain-English reasons. */
    static void rescore(Context c, Listing l, Search s) {
        double median = l.typical;
        int wP = weight(c, "price"), wD = weight(c, "drop"), wR = weight(c, "renewed"),
                wF = weight(c, "fresh"), wDist = weight(c, "distance");
        List<String> why = new ArrayList<>();

        double price = 0;
        if (median > 0 && l.amount > 1.5) {
            double below = 1 - l.amount / median;
            price = clamp(below / 0.6);
            if (below >= 0.1) why.add(Math.round(below * 100) + "% below typical (" + Checker.formatLike(l.price, median) + ")");
            else if (below <= -0.1) why.add(Math.round(-below * 100) + "% above typical");
        }

        double drop = 0;
        if ("drop".equals(l.kind) && !l.oldPrice.isEmpty()) {
            double old = parse(l.oldPrice);
            if (old > 0 && l.amount >= 0) {
                double pct = (old - l.amount) / old;
                drop = clamp(pct / 0.4);
                why.add("Reduced " + l.oldPrice + " → " + l.price);
            }
        }

        double renewed = 0, fresh = 0;
        if (l.listedAt > 0) {
            double ageDays = (System.currentTimeMillis() - l.listedAt) / 86_400_000.0;
            if (ageDays > 2) {
                renewed = clamp((ageDays - 2) / 30);
                why.add("Unsold " + Math.round(ageDays) + " days");
            } else if (ageDays < 0.25) {
                fresh = clamp(1 - ageDays / 0.25);
                long mins = Math.round(ageDays * 1440);
                why.add("Posted " + (mins < 60 ? mins + " min" : Math.round(mins / 60.0) + " h") + " ago");
            }
        }

        double total = wP + wD + wR + wF;
        double score = total == 0 ? 0 : 100 * (wP * price + wD * drop + wR * renewed + wF * fresh) / total;
        // Being good on several fronts at once deserves a nudge up
        int strong = (price > 0.5 ? 1 : 0) + (drop > 0.5 ? 1 : 0) + (renewed > 0.5 ? 1 : 0) + (fresh > 0.5 ? 1 : 0);
        if (strong >= 2) score += 10;

        if (l.km >= 0 && s != null && s.radiusKm > 0) {
            double beyond = Math.max(0, l.km - s.radiusKm);
            double pen = (wDist / 100.0) * 25 * clamp(beyond / Math.max(5, s.radiusKm * 0.5));
            score -= pen;
            if (l.outside) why.add(Math.round(l.km) + " km — a bit further, worth the drive?");
        }

        if (redFlag(l)) {
            score *= 0.3;
            why.add(0, "⚠ looks like wanted / spares / repairs");
        }
        if (l.amount >= 0 && l.amount <= 1.5) {
            score = Math.min(score, 20);
            why.add(0, "⚠ price looks like a placeholder");
        } else if (median > 0 && l.amount < median * 0.2) {
            score = Math.min(score, 60);
            why.add(0, "⚠ very cheap — check carefully");
        }

        l.score = (int) Math.round(Math.max(0, Math.min(100, score)));
        l.reasons = String.join(" · ", why);
    }

    private static double parse(String price) {
        try {
            return Double.parseDouble(price.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------- Notification ----------

    static final String CH = "deals";
    static final int ID = 40;

    static void channel(Context c) {
        android.app.NotificationChannel ch = new android.app.NotificationChannel(CH, "Consider this (deals)",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Daily suggestions that score highly on DealRank");
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private static void notifyBest(Context c, List<Listing> all) {
        int min = minScore(c);
        Map<String, Integer> done = notified(c);
        List<Listing> pick = new ArrayList<>();
        for (Listing l : all) {
            if (l.score < min) break;
            Integer before = done.get(l.id);
            if (before != null && l.score < before + 10) continue;
            pick.add(l);
        }
        if (pick.isEmpty()) return;
        for (Listing l : pick) done.put(l.id, l.score);
        saveNotified(c, done);

        channel(c);
        Listing top = pick.get(0);
        Intent open = new Intent(c, MainActivity.class).putExtra("showDeals", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 41, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent viewTop = android.app.TaskStackBuilder.create(c)
                .addNextIntentWithParentStack(ListingViewActivity.intent(c, top))
                .getPendingIntent(42, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String title = pick.size() == 1 ? "Consider this: " + top.priceOrBlank() + " " + top.title
                : "Consider these " + pick.size() + " deals";
        Notification.Builder b = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setAutoCancel(true);
        if (pick.size() == 1) {
            String text = "DealRank " + top.score + " · " + top.reasons;
            b.setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(viewTop);
            android.graphics.Bitmap thumb = Fetcher.thumbnail(top.imageUrl);
            if (thumb != null) b.setLargeIcon(thumb);
        } else {
            Notification.InboxStyle inbox = new Notification.InboxStyle();
            for (int i = 0; i < Math.min(6, pick.size()); i++) {
                Listing l = pick.get(i);
                inbox.addLine(l.score + " · " + (l.priceOrBlank().isEmpty() ? "" : l.price + " ") + l.title);
            }
            b.setContentText(top.score + " · " + top.priceOrBlank() + " " + top.title).setStyle(inbox).setContentIntent(pi);
        }
        c.getSystemService(NotificationManager.class).notify(ID, b.build());
    }

    // ---------- Daily schedule ----------

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, DealsReceiver.class).setAction(DealsReceiver.ACT_DAILY);
        return PendingIntent.getBroadcast(c, 43, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(alarmIntent(c));
        if (!dailyOn(c)) return;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour(c));
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 60_000L) cal.add(Calendar.DAY_OF_MONTH, 1);
        try {
            if (AlarmReceiver.canBeExact(c)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), alarmIntent(c));
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), alarmIntent(c));
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), alarmIntent(c));
        }
    }

    public static long nextScan(Context c) {
        if (!dailyOn(c)) return 0;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour(c));
        cal.set(Calendar.MINUTE, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1);
        return cal.getTimeInMillis();
    }

    /** Runs a scan now, in the background, even if the app is closed. */
    public static void runNow(Context c) {
        Intent i = new Intent(c, DealsService.class);
        c.startForegroundService(i);
    }

    static boolean scanning;
    static final Set<Runnable> listeners = new HashSet<>();
}
