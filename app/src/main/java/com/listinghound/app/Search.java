package com.listinghound.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** One saved Marketplace search and the filters applied to its results. */
public class Search {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String query = "";
    /** City part of the Marketplace URL, e.g. "leeds". Blank = Facebook's own location. */
    public String location = "";
    public int radiusKm = 0;
    public int minPrice = 0;
    public int maxPrice = 0;
    /** Comma-separated words that must all appear in the title. */
    public String include = "";
    /** Comma-separated words; any of them in the title rejects the listing. */
    public String exclude = "";
    public boolean enabled = true;
    /** Facebook category slug, e.g. "garden"; blank = any category. */
    public String category = "";
    /** Only these categories (slugs, comma-separated); blank = all. */
    public String cats = "";

    public java.util.Set<String> catSet() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String p : cats.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    /** Facebook category numbers to leave out, comma-separated (older versions). */
    public String blockedCats = "";
    /** Example titles for each blocked kind: lines of "id<TAB>label". */
    public String blockedLabels = "";

    public java.util.Set<String> blockedSet() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String p : blockedCats.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    public java.util.Map<String, String> blockedLabelMap() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String line : blockedLabels.split("\n")) {
            int t = line.indexOf('\t');
            if (t > 0) m.put(line.substring(0, t), line.substring(t + 1));
        }
        return m;
    }

    /** false = match ALL keywords; true = match ANY keyword (each is searched separately). */
    public boolean includeAny = false;
    /** Keywords must appear in the listing title, not just somewhere Facebook matched them. */
    public boolean titleOnly = true;
    /** Description filters (comma-separated, any of). */
    public String descInclude = "";
    public String descExclude = "";

    /** The comma-separated keyword phrases. */
    public List<String> phrases() {
        return words(query);
    }

    /** What to send to Facebook: one search for ALL, one per keyword for ANY. */
    public List<String> fbQueries() {
        List<String> p = phrases();
        List<String> out = new ArrayList<>();
        if (p.isEmpty()) return out;
        if (includeAny) {
            for (int i = 0; i < Math.min(5, p.size()); i++) out.add(p.get(i));
        } else {
            out.add(String.join(" ", p));
        }
        return out;
    }

    public boolean hasDescFilter() {
        return !words(descInclude).isEmpty() || !words(descExclude).isEmpty();
    }

    /** Description test; an unknown description (null) passes. */
    public boolean descOk(String desc) {
        if (desc == null) return true;
        String d = desc.toLowerCase(Locale.ROOT);
        List<String> inc = words(descInclude);
        if (!inc.isEmpty()) {
            boolean any = false;
            for (String w : inc) if (d.contains(w)) any = true;
            if (!any) return false;
        }
        for (String w : words(descExclude)) if (d.contains(w)) return false;
        return true;
    }

    /** All the words of a phrase appear in the text, in any order. */
    static boolean phraseIn(String text, String phrase) {
        for (String w : phrase.split("\\s+")) if (!w.isEmpty() && !text.contains(w)) return false;
        return true;
    }

    public static final String[][] CATEGORIES = {
            {"", "Any category"},
            {"garden", "Garden & outdoor"},
            {"home-improvements", "Home improvement & DIY"},
            {"tools", "Tools"},
            {"furniture", "Furniture"},
            {"appliances", "Household appliances"},
            {"electronics", "Electronics"},
            {"sports", "Sports & outdoors"},
            {"toys", "Toys & games"},
            {"family", "Family & baby"},
            {"apparel", "Clothing"},
            {"instruments", "Musical instruments"},
            {"video-games", "Video games"},
            {"antiques", "Antiques & collectibles"},
            {"entertainment", "Books, films & music"},
            {"office-supplies", "Office supplies"},
            {"pets", "Pet supplies"},
            {"free", "Free stuff"},
            {"vehicles", "Vehicles"},
            {"motorcycles", "Motorcycles"},
            {"boats", "Boats"},
            {"propertyrentals", "Property for rent"}};

    public static String categoryLabel(String slug) {
        for (String[] c : CATEGORIES) if (c[0].equals(slug)) return c[1];
        return slug;
    }
    // What to do with each kind of find
    public static final int ALERT = 0, LIST_ONLY = 1, IGNORE = 2;
    public static final String[] MODE_LABELS = {"Alert me", "List only (no alert)", "Ignore"};
    public int modeNew = ALERT;
    public int modeDrop = ALERT;
    public int modeRenewed = LIST_ONLY;
    public int modeUpdated = ALERT;

    public int modeFor(String kind) {
        switch (kind) {
            case "drop": return modeDrop;
            case "renewed": return modeRenewed;
            case "updated": return modeUpdated;
            default: return modeNew;
        }
    }
    /** Ignore listings further away than the radius, even if Facebook includes them. */
    public boolean strictArea = true;

    public String displayName() {
        return name.trim().isEmpty() ? query.trim() : name.trim();
    }

    public String summary() {
        StringBuilder b = new StringBuilder();
        List<String> kws = phrases();
        if (kws.size() > 1) b.append(includeAny ? "any of: " : "all of: ");
        b.append(String.join(", ", kws));
        if (titleOnly) b.append(" (in title)");
        if (!location.trim().isEmpty()) b.append(" · ").append(location.trim());
        if (radiusKm > 0) b.append(" · ").append(radiusKm).append(" km");
        if (minPrice > 0 || maxPrice > 0) {
            b.append(" · ");
            if (minPrice > 0 && maxPrice > 0) b.append('£').append(minPrice).append("–£").append(maxPrice);
            else if (minPrice > 0) b.append("£").append(minPrice).append('+');
            else b.append("up to £").append(maxPrice);
        }
        if (!cats.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String c : catSet()) names.add(categoryLabel(c));
            b.append(" · only ").append(String.join(", ", names));
        }
        if (!descInclude.trim().isEmpty()) b.append(" · description has: ").append(descInclude.trim());
        if (!descExclude.trim().isEmpty()) b.append(" · description not: ").append(descExclude.trim());
        if (!exclude.trim().isEmpty()) b.append(" · not: ").append(exclude.trim());
        return b.toString();
    }

    public boolean matches(Listing l) {
        String t = (l.title == null ? "" : l.title).toLowerCase(Locale.ROOT);
        if (!cats.isEmpty()) {
            String slug = Categories.slugFor(l.categoryId);
            // Unknown category numbers are let through, so nothing is missed
            if (slug != null && !catSet().contains(slug)) return false;
        }
        List<String> kws = phrases();
        if (titleOnly && !kws.isEmpty()) {
            boolean any = false, all = true;
            for (String p : kws) {
                if (phraseIn(t, p)) any = true;
                else all = false;
            }
            if (includeAny ? !any : !all) return false;
        }
        for (String w : words(exclude)) if (t.contains(w)) return false;
        if (l.amount >= 0) {
            if (minPrice > 0 && l.amount < minPrice) return false;
            if (maxPrice > 0 && l.amount > maxPrice) return false;
        }
        return true;
    }

    static List<String> words(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String p : csv.split(",")) {
            String w = p.trim().toLowerCase(Locale.ROOT);
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("id", id);
            j.put("name", name);
            j.put("query", query);
            j.put("location", location);
            j.put("radiusKm", radiusKm);
            j.put("minPrice", minPrice);
            j.put("maxPrice", maxPrice);
            j.put("include", include);
            j.put("exclude", exclude);
            j.put("enabled", enabled);
            j.put("category", category);
            j.put("cats", cats);
            j.put("blockedCats", blockedCats);
            j.put("blockedLabels", blockedLabels);
            j.put("includeAny", includeAny);
            j.put("titleOnly", titleOnly);
            j.put("descInclude", descInclude);
            j.put("descExclude", descExclude);
            j.put("modeNew", modeNew);
            j.put("modeDrop", modeDrop);
            j.put("modeRenewed", modeRenewed);
            j.put("modeUpdated", modeUpdated);
            j.put("strictArea", strictArea);
        } catch (JSONException ignored) {
        }
        return j;
    }

    public static Search fromJson(JSONObject j) {
        Search s = new Search();
        s.id = j.optString("id", s.id);
        s.name = j.optString("name", "");
        s.query = j.optString("query", "");
        s.location = j.optString("location", "");
        s.radiusKm = j.optInt("radiusKm", 0);
        s.minPrice = j.optInt("minPrice", 0);
        s.maxPrice = j.optInt("maxPrice", 0);
        s.include = j.optString("include", "");
        s.exclude = j.optString("exclude", "");
        s.enabled = j.optBoolean("enabled", true);
        s.category = ""; // category pages ignored the keywords; kinds are now filtered on the phone
        s.cats = j.optString("cats", "");
        s.blockedCats = "";
        s.blockedLabels = "";
        s.includeAny = j.optBoolean("includeAny", false);
        s.descInclude = j.optString("descInclude", "");
        s.descExclude = j.optString("descExclude", "");
        if (j.has("titleOnly")) {
            s.titleOnly = j.optBoolean("titleOnly", true);
        } else {
            // Older versions: a separate "title must contain" list. Fold it into the keywords.
            String inc = j.optString("include", "").trim();
            s.titleOnly = !inc.isEmpty();
            if (!inc.isEmpty() && s.includeAny) s.query = inc;
        }
        // Older versions had on/off switches for drops and title changes
        s.modeNew = j.optInt("modeNew", ALERT);
        s.modeDrop = j.has("modeDrop") ? j.optInt("modeDrop") : (j.optBoolean("alertDrops", true) ? ALERT : IGNORE);
        s.modeRenewed = j.optInt("modeRenewed", LIST_ONLY);
        s.modeUpdated = j.has("modeUpdated") ? j.optInt("modeUpdated") : (j.optBoolean("alertUpdates", true) ? ALERT : IGNORE);
        s.strictArea = j.optBoolean("strictArea", true);
        return s;
    }

    /** True when a change to this search means old results no longer apply. */
    public boolean resultsDifferFrom(Search o) {
        return o == null
                || !query.equals(o.query)
                || !location.equals(o.location)
                || radiusKm != o.radiusKm
                || minPrice != o.minPrice
                || maxPrice != o.maxPrice
                || includeAny != o.includeAny;
    }
}
