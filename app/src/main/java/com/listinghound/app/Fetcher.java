package com.listinghound.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    public static String buildUrl(Search s) {
        StringBuilder u = new StringBuilder(FB).append("/marketplace/");
        String loc = s.location.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
        if (!loc.isEmpty()) u.append(loc).append('/');
        String q;
        try {
            q = URLEncoder.encode(s.query.trim(), "UTF-8");
        } catch (Exception e) {
            q = s.query.trim();
        }
        u.append("search?query=").append(q);
        u.append("&sortBy=creation_time_descend&exact=false");
        if (s.minPrice > 0) u.append("&minPrice=").append(s.minPrice);
        if (s.maxPrice > 0) u.append("&maxPrice=").append(s.maxPrice);
        if (s.radiusKm > 0) u.append("&radius=").append(s.radiusKm);
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

    public static Result fetch(Search s) {
        Result r = new Result();
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(buildUrl(s)).openConnection();
            con.setConnectTimeout(20000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", UA);
            con.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            con.setRequestProperty("Accept-Language", "en-GB,en;q=0.9");
            con.setRequestProperty("Sec-Fetch-Dest", "document");
            con.setRequestProperty("Sec-Fetch-Mode", "navigate");
            con.setRequestProperty("Sec-Fetch-Site", "none");
            con.setRequestProperty("Upgrade-Insecure-Requests", "1");
            String cookie = null;
            try {
                cookie = CookieManager.getInstance().getCookie(FB);
            } catch (Throwable ignored) {
            }
            if (cookie != null && !cookie.isEmpty()) con.setRequestProperty("Cookie", cookie);

            r.httpCode = con.getResponseCode();
            String finalUrl = con.getURL().toString();
            if (r.httpCode >= 400) {
                r.error = "Facebook returned error " + r.httpCode;
                return r;
            }
            String html = read(con.getInputStream());
            parse(html, r.listings);
            if (r.listings.isEmpty()) {
                boolean wall = finalUrl.contains("/login") || finalUrl.contains("/checkpoint")
                        || html.contains("id=\"login_form\"") || html.contains("name=\"login\"");
                if (wall) r.loginWall = true;
            }
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } finally {
            if (con != null) con.disconnect();
        }
        return r;
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

    private static Listing fromNode(JSONObject j) {
        String id = j.optString("id", "");
        if (!id.matches("\\d{6,}")) return null;
        if (j.optBoolean("is_sold", false) || j.optBoolean("is_pending", false)) return null;
        Listing l = new Listing();
        l.id = id;
        l.title = j.optString("marketplace_listing_title", "").trim();
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
            if (rg != null) l.city = rg.optString("city", "");
        }
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

    /** Small thumbnail for a notification; null on any failure. */
    public static Bitmap thumbnail(String url) {
        if (url == null || url.isEmpty()) return null;
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            con.setRequestProperty("User-Agent", UA);
            if (con.getResponseCode() != 200) return null;
            byte[] data;
            try (InputStream is = con.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 3 * 1024 * 1024) return null;
                }
                data = bos.toByteArray();
            }
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
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
