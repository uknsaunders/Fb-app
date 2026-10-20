package com.listinghound.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Loads a Marketplace search page and pulls the listings out of it. */
public final class Fetcher {
    private Fetcher() {}

    static final String FB = "https://www.facebook.com";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/129.0.0.0 Safari/537.36";
    private static final int MAX_BYTES = 12 * 1024 * 1024;

    public static class Result {
        public final List<Listing> listings = new ArrayList<>();
        public boolean loginWall;
        public String error;
        public int httpCode;
    }

    public static String buildUrl(Search s, String sort, int radiusKm) {
        List<String> qs = s.fbQueries();
        return buildUrl(s, qs.isEmpty() ? s.query : qs.get(0), sort, radiusKm);
    }

    /** Runs each of the search's Facebook queries and merges the results. */
    public static Result fetchAll(Search s, String sort, int radiusKm) {
        Result merged = new Result();
        List<String> qs = s.fbQueries();
        if (qs.isEmpty()) qs = java.util.Collections.singletonList(s.query);
        java.util.Set<String> ids = new java.util.HashSet<>();
        int ok = 0, walls = 0;
        for (int i = 0; i < qs.size(); i++) {
            if (i > 0) {
                try {
                    Thread.sleep(1200 + (long) (Math.random() * 1500));
                } catch (InterruptedException ignored) {
                }
            }
            Result r = fetchUrl(buildUrl(s, qs.get(i), sort, radiusKm));
            merged.httpCode = r.httpCode;
            if (r.error != null) {
                merged.error = r.error;
                continue;
            }
            if (r.loginWall) {
                walls++;
                continue;
            }
            ok++;
            for (Listing l : r.listings) if (ids.add(l.id)) merged.listings.add(l);
        }
        if (ok > 0) merged.error = null;
        merged.loginWall = ok == 0 && walls > 0;
        return merged;
    }

    public static String buildUrl(Search s, String query, String sort, int radiusKm) {
        StringBuilder u = new StringBuilder(FB).append("/marketplace/");
        String loc = s.location.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
        if (!loc.isEmpty()) u.append(loc).append('/');
        u.append("search?");
        String q;
        try {
            q = URLEncoder.encode(query.trim(), "UTF-8");
        } catch (Exception e) {
            q = query.trim();
        }
        u.append("query=").append(q);
        u.append("&sortBy=").append(sort).append("&exact=false");
        if (s.minPrice > 0) u.append("&minPrice=").append(s.minPrice);
        if (s.maxPrice > 0) u.append("&maxPrice=").append(s.maxPrice);
        if (radiusKm > 0) u.append("&radius=").append(radiusKm);
        return u.toString();
    }

    public static boolean loggedIn() {
        try {
            String c = CookieManager.getInstance().getCookie(FB);
            return c != null && c.contains("c_user=");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Tries IPv4 addresses before IPv6 ones, so a broken IPv6 route can't stall every check. */
    private static final Dns IPV4_FIRST = host -> {
        List<InetAddress> all = Dns.SYSTEM.lookup(host);
        List<InetAddress> v4 = new ArrayList<>();
        List<InetAddress> v6 = new ArrayList<>();
        for (InetAddress a : all) (a instanceof Inet4Address ? v4 : v6).add(a);
        v4.addAll(v6);
        return v4;
    };

    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .dns(IPV4_FIRST)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    public static Result fetch(Search s) {
        return fetchAll(s, "creation_time_descend", s.radiusKm);
    }

    public static Result fetchUrl(String pageUrl) {
        Result r = new Result();
        Request.Builder rb = new Request.Builder()
                .url(pageUrl)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-GB,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Upgrade-Insecure-Requests", "1");
        String cookie = null;
        try {
            cookie = CookieManager.getInstance().getCookie(FB);
        } catch (Throwable ignored) {
        }
        if (cookie != null && !cookie.isEmpty()) rb.header("Cookie", cookie);

        try (Response resp = CLIENT.newCall(rb.build()).execute()) {
            r.httpCode = resp.code();
            String finalUrl = resp.request().url().toString();
            if (r.httpCode >= 400) {
                r.error = "Facebook returned error " + r.httpCode;
                return r;
            }
            ResponseBody body = resp.body();
            String html = body == null ? "" : read(body.byteStream());
            parse(html, r.listings);
            if (r.listings.isEmpty()) {
                boolean wall = finalUrl.contains("/login") || finalUrl.contains("/checkpoint")
                        || html.contains("id=\"login_form\"") || html.contains("name=\"login\"");
                if (wall) r.loginWall = true;
            }
        } catch (Exception e) {
            r.error = friendly(e);
        }
        return r;
    }

    private static String friendly(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "No internet connection";
        if (e instanceof java.net.SocketTimeoutException || e instanceof java.io.InterruptedIOException)
            return "Facebook took too long to answer";
        if (e instanceof java.net.ConnectException) return "Couldn't connect to Facebook";
        return e.getClass().getSimpleName();
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_BYTES) break;
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---- Parsing ----

    private static final Pattern SCRIPT = Pattern.compile(
            "<script[^>]*type=\"application/json\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile(
            "\"marketplace_listing_title\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ID = Pattern.compile("\"id\":\"(\\d{8,})\"");
    private static final Pattern PRICE = Pattern.compile(
            "\"formatted_amount\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern AMOUNT = Pattern.compile("\"amount\":\"([0-9.]+)\"");

    static void parse(String html, List<Listing> out) {
        Map<String, Listing> found = new LinkedHashMap<>();
        Matcher m = SCRIPT.matcher(html);
        while (m.find()) {
            String js = m.group(1);
            if (js == null || !js.contains("marketplace_listing_title")) continue;
            try {
                walk(new JSONTokener(js).nextValue(), found, 0);
            } catch (Throwable ignored) {
            }
        }
        if (found.isEmpty() && html.contains("marketplace_listing_title")) fallback(html, found);
        out.addAll(found.values());
    }

    private static void walk(Object o, Map<String, Listing> found, int depth) {
        if (depth > 200 || o == null) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            if (j.has("marketplace_listing_title") && j.has("id")) {
                Listing l = fromNode(j);
                if (l != null && !found.containsKey(l.id)) found.put(l.id, l);
            }
            Iterator<String> keys = j.keys();
            while (keys.hasNext()) walk(j.opt(keys.next()), found, depth + 1);
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), found, depth + 1);
        }
    }

    /** Collects key=value pairs whose key mentions "categor", up to two levels deep. */
    private static String categoryFields(JSONObject j, int depth) {
        StringBuilder b = new StringBuilder();
        Iterator<String> keys = j.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = j.opt(k);
            if (k.toLowerCase(Locale.ROOT).contains("categor")) {
                String val = v instanceof JSONObject || v instanceof JSONArray ? v.toString() : String.valueOf(v);
                if (val.length() > 120) val = val.substring(0, 120) + "…";
                b.append(k).append('=').append(val).append("; ");
            } else if (depth < 2 && v instanceof JSONObject) {
                b.append(categoryFields((JSONObject) v, depth + 1));
            }
        }
        return b.toString();
    }

    private static Listing fromNode(JSONObject j) {
        String id = j.optString("id", "");
        if (!id.matches("\\d{6,}")) return null;
        if (j.optBoolean("is_sold", false) || j.optBoolean("is_pending", false)) return null;
        Listing l = new Listing();
        l.id = id;
        l.title = j.optString("marketplace_listing_title", "").trim();
        l.categoryInfo = categoryFields(j, 0);
        Matcher cm = Pattern.compile("marketplace_listing_category_id=(\\d+)").matcher(l.categoryInfo);
        if (cm.find()) l.categoryId = cm.group(1);
        JSONObject price = j.optJSONObject("listing_price");
        if (price != null) {
            l.price = price.optString("formatted_amount", "");
            try {
                l.amount = Double.parseDouble(price.optString("amount", "-1"));
            } catch (NumberFormatException e) {
                l.amount = -1;
            }
        }
        JSONObject loc = j.optJSONObject("location");
        if (loc != null) {
            JSONObject rg = loc.optJSONObject("reverse_geocode");
            if (rg != null) {
                l.city = rg.optString("city", "");
                l.state = rg.optString("state", "");
            }
        }
        long ct = j.optLong("creation_time", 0);
        if (ct > 1_000_000_000L) l.listedAt = ct * 1000L;
        JSONObject photo = j.optJSONObject("primary_listing_photo");
        if (photo != null) {
            JSONObject img = photo.optJSONObject("image");
            if (img != null) l.imageUrl = img.optString("uri", "");
        }
        return l;
    }

    /** Used if Facebook changes its page so the JSON blocks can't be read whole. */
    private static void fallback(String html, Map<String, Listing> found) {
        Matcher t = TITLE.matcher(html);
        while (t.find()) {
            int start = Math.max(0, t.start() - 4000);
            String before = html.substring(start, t.start());
            Matcher idm = ID.matcher(before);
            String id = null;
            while (idm.find()) id = idm.group(1);
            if (id == null || found.containsKey(id)) continue;
            Listing l = new Listing();
            l.id = id;
            l.title = unescape(t.group(1));
            String around = html.substring(Math.max(0, t.start() - 2000), Math.min(html.length(), t.end() + 2000));
            Matcher pm = PRICE.matcher(around);
            if (pm.find()) l.price = unescape(pm.group(1));
            Matcher am = AMOUNT.matcher(around);
            if (am.find()) {
                try {
                    l.amount = Double.parseDouble(am.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            found.put(id, l);
        }
    }

    private static String unescape(String s) {
        try {
            Object v = new JSONTokener("\"" + s + "\"").nextValue();
            return v instanceof String ? (String) v : s;
        } catch (Exception e) {
            return s;
        }
    }

    static final String[] TIME_KEYS = {"creation_time", "created_time", "listing_time", "publish_time", "time_created", "creation_timestamp"};

    private static String fetchItemPage(String id, int[] codeOut, String[] urlOut) throws Exception {
        Request.Builder rb = new Request.Builder()
                .url(FB + "/marketplace/item/" + id + "/")
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-GB,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none");
        try {
            String cookie = CookieManager.getInstance().getCookie(FB);
            if (cookie != null && !cookie.isEmpty()) rb.header("Cookie", cookie);
        } catch (Throwable ignored) {
        }
        try (Response resp = CLIENT.newCall(rb.build()).execute()) {
            if (codeOut != null) codeOut[0] = resp.code();
            if (urlOut != null) urlOut[0] = resp.request().url().toString();
            ResponseBody body = resp.body();
            if (!resp.isSuccessful() || body == null) return null;
            return read(body.byteStream());
        }
    }

    /** Looks inside the page's data for the object that IS this listing, then for a time field within it. */
    private static long timeFromStructure(String html, String id) {
        Matcher m = SCRIPT.matcher(html);
        while (m.find()) {
            String js = m.group(1);
            if (js == null || !js.contains(id)) continue;
            try {
                long t = findListingTime(new JSONTokener(js).nextValue(), id, 0);
                if (t > 0) return t;
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static long findListingTime(Object o, String id, int depth) {
        if (o == null || depth > 220) return -1;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            if (id.equals(j.optString("id", null))) {
                long t = timeIn(j, 0);
                if (t > 0) return t;
            }
            Iterator<String> keys = j.keys();
            while (keys.hasNext()) {
                long t = findListingTime(j.opt(keys.next()), id, depth + 1);
                if (t > 0) return t;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                long t = findListingTime(a.opt(i), id, depth + 1);
                if (t > 0) return t;
            }
        }
        return -1;
    }

    /** A time field on this object or a few levels inside it (not inside other listings). */
    private static long timeIn(JSONObject j, int depth) {
        for (String k : TIME_KEYS) {
            long v = j.optLong(k, 0);
            if (v > 1_000_000_000L && v < 10_000_000_000L) return v * 1000L;
        }
        if (depth >= 3) return -1;
        Iterator<String> keys = j.keys();
        while (keys.hasNext()) {
            Object v = j.opt(keys.next());
            if (v instanceof JSONObject) {
                JSONObject c = (JSONObject) v;
                String cid = c.optString("id", "");
                if (!cid.isEmpty() && cid.matches("\\d{8,}")) continue; // a different listing
                long t = timeIn(c, depth + 1);
                if (t > 0) return t;
            }
        }
        return -1;
    }

    private static final Map<String, String> DESC_CACHE = new LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 300;
        }
    };

    /** The listing's description text, or null if it can't be read. */
    public static String description(String id) {
        synchronized (DESC_CACHE) {
            if (DESC_CACHE.containsKey(id)) return DESC_CACHE.get(id);
        }
        String d = null;
        try {
            String html = fetchItemPage(id, null, null);
            if (html != null) d = descFromPage(html, id);
        } catch (Exception ignored) {
        }
        if (d != null) {
            synchronized (DESC_CACHE) {
                DESC_CACHE.put(id, d);
            }
        }
        return d;
    }

    private static String descFromPage(String html, String id) {
        Matcher m = SCRIPT.matcher(html);
        while (m.find()) {
            String js = m.group(1);
            if (js == null || !js.contains(id) || !js.contains("description")) continue;
            try {
                String d = findDesc(new JSONTokener(js).nextValue(), id, 0, false);
                if (d != null) return d;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String findDesc(Object o, String id, int depth, boolean inListing) {
        if (o == null || depth > 220) return null;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            boolean here = inListing || id.equals(j.optString("id", null));
            if (here) {
                for (String k : new String[]{"redacted_description", "description"}) {
                    Object v = j.opt(k);
                    if (v instanceof JSONObject) {
                        String t = ((JSONObject) v).optString("text", null);
                        if (t != null && !t.isEmpty()) return t;
                    } else if (v instanceof String && !((String) v).isEmpty()) {
                        return (String) v;
                    }
                }
            }
            Iterator<String> keys = j.keys();
            while (keys.hasNext()) {
                Object v = j.opt(keys.next());
                // Stay inside this listing: don't wander into other listings (they have their own long IDs)
                boolean child = here && !(v instanceof JSONObject && ((JSONObject) v).optString("id", "").matches("\\d{8,}"))
                        && depth < 200;
                String d = findDesc(v, id, depth + 1, child && here);
                if (d != null) return d;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                String d = findDesc(a.opt(i), id, depth + 1, inListing);
                if (d != null) return d;
            }
        }
        return null;
    }

    /** When a listing was posted. Returns ms, or -1 if it can't be found reliably. */
    public static long listedAt(String id) {
        try {
            String html = fetchItemPage(id, null, null);
            if (html == null) return -1;
            long t = timeFromStructure(html, id);
            if (t > System.currentTimeMillis() + 3600_000L) return -1;
            return t;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Plain-English report of what Facebook's page for this listing contains, for troubleshooting. */
    public static String diagnose(String id) {
        StringBuilder b = new StringBuilder();
        int[] code = {0};
        String[] url = {""};
        String html;
        try {
            html = fetchItemPage(id, code, url);
        } catch (Exception e) {
            return "Couldn't load the listing page: " + e.getClass().getSimpleName();
        }
        b.append("Listing ID: ").append(id).append('\n');
        b.append("Page: HTTP ").append(code[0]);
        if (html == null) return b.append(" (no page)").toString();
        b.append(", ").append(html.length() / 1024).append(" KB\n");
        String u = url[0];
        if (u.contains("/login") || u.contains("/checkpoint")) b.append("Redirected to a login page\n");
        String idKey = "\"id\":\"" + id + "\"";
        int idCount = 0;
        for (int i = html.indexOf(idKey); i >= 0; i = html.indexOf(idKey, i + 1)) idCount++;
        b.append("Listing ID on page: ").append(idCount).append(" times");
        b.append(" · plain mentions: ").append(html.split(id, -1).length - 1).append('\n');
        b.append("Has listing title: ").append(html.contains("marketplace_listing_title") ? "yes" : "no").append('\n');
        int first = html.indexOf(idKey);
        for (String k : TIME_KEYS) {
            Matcher m = Pattern.compile("\"" + k + "\":(\\d{10})").matcher(html);
            int n = 0, bestD = Integer.MAX_VALUE;
            long bestV = 0;
            while (m.find()) {
                n++;
                int d = first < 0 ? Integer.MAX_VALUE : Math.abs(m.start() - first);
                if (bestV == 0 || d < bestD) {
                    bestD = d;
                    bestV = Long.parseLong(m.group(1));
                }
            }
            if (n == 0) continue;
            b.append(k).append(": ").append(n).append(" found, nearest ")
                    .append(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                            .format(new java.util.Date(bestV * 1000L)));
            if (bestD != Integer.MAX_VALUE) b.append(" (").append(bestD).append(" chars from ID)");
            b.append('\n');
        }
        Matcher rel = Pattern.compile("[Ll]isted ([^\"<]{1,40}?) ago").matcher(html);
        if (rel.find()) b.append("Page text: “Listed ").append(rel.group(1)).append(" ago”\n");
        long t = timeFromStructure(html, id);
        b.append("Structured lookup result: ").append(t > 0
                ? java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(new java.util.Date(t))
                : "not found");
        return b.toString();
    }

    /** Small thumbnail for a notification; null on any failure. */
    public static Bitmap thumbnail(String url) {
        if (url == null || url.isEmpty()) return null;
        Request req = new Request.Builder().url(url).header("User-Agent", UA).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            ResponseBody body = resp.body();
            if (!resp.isSuccessful() || body == null) return null;
            byte[] data = body.bytes();
            if (data.length > 3 * 1024 * 1024) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 256 && o.outHeight / (sample * 2) >= 256) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
    }
}
