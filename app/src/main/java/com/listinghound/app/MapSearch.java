package com.listinghound.app;

import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URLEncoder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Searches Marketplace around exact coordinates, the way Facebook's own pages do, instead of
 * relying on a town name in the web address (which Facebook ignores for most towns and replaces
 * with wherever it thinks you are).
 */
final class MapSearch {
    private MapSearch() {}

    // Learned from a Facebook search page, reused for 30 minutes to save data
    private static String lsd, dtsg, docId;
    private static JSONObject template;
    private static long learnedAt;

    // Fallback if the page doesn't reveal its own query (shape used by Facebook's web search)
    private static final String FALLBACK_DOC = "7111939778879383";
    private static final String FALLBACK_VARS = "{\"count\":24,\"params\":{\"bqf\":{\"callsite\":\"COMMERCE_MKTPLACE_WWW\",\"query\":\"\"},"
            + "\"browse_request_params\":{\"commerce_enable_local_pickup\":true,\"commerce_enable_shipping\":true,"
            + "\"commerce_search_and_rp_available\":true,\"commerce_search_and_rp_condition\":null,"
            + "\"commerce_search_and_rp_ctime_days\":null,\"filter_location_latitude\":0,\"filter_location_longitude\":0,"
            + "\"filter_price_lower_bound\":0,\"filter_price_upper_bound\":214748364700,\"filter_radius_km\":16},"
            + "\"custom_request_params\":{\"surface\":\"SEARCH\"}}}";

    private static final Pattern LSD = Pattern.compile("\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"");
    private static final Pattern DTSG = Pattern.compile("\"DTSGInitialData\",\\[\\],\\{\"token\":\"([^\"]+)\"");
    private static final Pattern SCRIPT = Pattern.compile(
            "<script[^>]*type=\"application/json\"[^>]*>(.*?)</script>", Pattern.DOTALL);

    static String lastNote = "";

    private static String cookie() {
        try {
            String c = CookieManager.getInstance().getCookie(Fetcher.FB);
            return c == null ? "" : c;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String userId() {
        Matcher m = Pattern.compile("c_user=(\\d+)").matcher(cookie());
        return m.find() ? m.group(1) : "0";
    }

    /** Loads one normal search page to pick up Facebook's session tokens and current search query. */
    private static synchronized boolean prepare(String pageUrl) {
        if (lsd != null && template != null && System.currentTimeMillis() - learnedAt < 30 * 60_000L) return true;
        String html = Fetcher.fetchPage(pageUrl);
        if (html == null) return false;
        Matcher m = LSD.matcher(html);
        lsd = m.find() ? m.group(1) : null;
        m = DTSG.matcher(html);
        dtsg = m.find() ? m.group(1) : null;
        template = null;
        docId = null;
        Matcher sm = SCRIPT.matcher(html);
        while (sm.find() && template == null) {
            String js = sm.group(1);
            if (js == null || !js.contains("filter_location_latitude")) continue;
            try {
                findPreloader(new JSONTokener(js).nextValue(), 0);
            } catch (Throwable ignored) {
            }
        }
        if (template == null) {
            try {
                template = new JSONObject(FALLBACK_VARS);
                docId = FALLBACK_DOC;
            } catch (Exception e) {
                return false;
            }
        }
        learnedAt = System.currentTimeMillis();
        return lsd != null;
    }

    /** Finds {"queryID"/"id": ..., "variables": {...filter_location_latitude...}} on the page. */
    private static void findPreloader(Object o, int depth) {
        if (o == null || depth > 200 || template != null) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            JSONObject vars = j.optJSONObject("variables");
            if (vars != null && vars.toString().contains("filter_location_latitude")) {
                String id = j.optString("queryID", j.optString("doc_id", j.optString("id", "")));
                if (id.matches("\\d{8,}")) {
                    template = vars;
                    docId = id;
                    return;
                }
            }
            Iterator<String> k = j.keys();
            while (k.hasNext()) findPreloader(j.opt(k.next()), depth + 1);
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) findPreloader(a.opt(i), depth + 1);
        }
    }

    private static void setDeep(Object o, String key, Object value) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            try {
                if (j.has(key)) j.put(key, value);
            } catch (Exception ignored) {
            }
            Iterator<String> k = j.keys();
            while (k.hasNext()) setDeep(j.opt(k.next()), key, value);
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) setDeep(a.opt(i), key, value);
        }
    }

    private static JSONObject find(Object o, String key) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            if (j.optJSONObject(key) != null) return j.optJSONObject(key);
            Iterator<String> k = j.keys();
            while (k.hasNext()) {
                JSONObject r = find(j.opt(k.next()), key);
                if (r != null) return r;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = find(a.opt(i), key);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Searches around lat/lng. Returns null if this way of searching isn't working (caller falls back). */
    static Fetcher.Result search(Search s, String query, String sort, int radiusKm, double lat, double lng) {
        String pageUrl;
        try {
            pageUrl = Fetcher.FB + "/marketplace/search?query=" + URLEncoder.encode(query, "UTF-8");
        } catch (Exception e) {
            return null;
        }
        if (!prepare(pageUrl)) {
            lastNote = "couldn't get a search session";
            return null;
        }
        JSONObject vars;
        String doc;
        synchronized (MapSearch.class) {
            try {
                vars = new JSONObject(template.toString());
            } catch (Exception e) {
                return null;
            }
            doc = docId;
        }
        try {
            setDeep(vars, "filter_location_latitude", lat);
            setDeep(vars, "filter_location_longitude", lng);
            setDeep(vars, "cursor", JSONObject.NULL);
            if (radiusKm > 0) setDeep(vars, "filter_radius_km", radiusKm);
            setDeep(vars, "filter_price_lower_bound", s.minPrice > 0 ? s.minPrice * 100L : 0);
            setDeep(vars, "filter_price_upper_bound", s.maxPrice > 0 ? s.maxPrice * 100L : 214748364700L);
            JSONObject bqf = find(vars, "bqf");
            if (bqf != null) bqf.put("query", query);
            JSONObject brp = find(vars, "browse_request_params");
            if (brp != null) brp.put("commerce_search_sort_by", sort.toUpperCase(java.util.Locale.ROOT));
            if (vars.has("count")) vars.put("count", 24);
        } catch (Exception e) {
            return null;
        }

        FormBody.Builder form = new FormBody.Builder()
                .add("av", userId())
                .add("__user", userId())
                .add("__a", "1")
                .add("lsd", lsd)
                .add("fb_api_caller_class", "RelayModern")
                .add("server_timestamps", "true")
                .add("variables", vars.toString())
                .add("doc_id", doc);
        if (dtsg != null) form.add("fb_dtsg", dtsg);

        Request req = new Request.Builder()
                .url(Fetcher.FB + "/api/graphql/")
                .post(form.build())
                .header("User-Agent", Fetcher.UA)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-GB,en;q=0.9")
                .header("Origin", Fetcher.FB)
                .header("Referer", pageUrl)
                .header("Sec-Fetch-Site", "same-origin")
                .header("X-FB-LSD", lsd)
                .header("Cookie", cookie())
                .build();

        Fetcher.Result r = new Fetcher.Result();
        try (Response resp = Fetcher.CLIENT.newCall(req).execute()) {
            r.httpCode = resp.code();
            ResponseBody body = resp.body();
            if (!resp.isSuccessful() || body == null) {
                lastNote = "map search refused (" + resp.code() + ")";
                forget();
                return null;
            }
            String text = body.string();
            if (text.startsWith("for (;;);")) text = text.substring(9);
            Map<String, Listing> found = new LinkedHashMap<>();
            boolean anyData = false;
            String err = null;
            for (String line : text.split("\n")) {
                line = line.trim();
                if (!line.startsWith("{")) continue;
                try {
                    JSONObject j = new JSONObject(line);
                    if (j.has("data")) anyData = true;
                    if (j.has("errors") && err == null) {
                        JSONObject e0 = j.optJSONArray("errors") == null ? null : j.optJSONArray("errors").optJSONObject(0);
                        err = e0 == null ? "error" : e0.optString("message", "error");
                    }
                    Fetcher.walkInto(j, found);
                } catch (Exception ignored) {
                }
            }
            if (!anyData) {
                lastNote = "map search failed" + (err != null ? ": " + err : "");
                forget();
                return null;
            }
            r.listings.addAll(found.values());
            r.mapSearch = true;
            lastNote = "searched around the map point";
            return r;
        } catch (Exception e) {
            lastNote = "map search error: " + e.getClass().getSimpleName();
            return null;
        }
    }

    /** Drop cached tokens so the next search starts a fresh session. */
    static synchronized void forget() {
        lsd = null;
        template = null;
        learnedAt = 0;
    }
}
