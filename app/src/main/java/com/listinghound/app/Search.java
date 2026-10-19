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
        b.append('"').append(query.trim()).append('"');
        if (!location.trim().isEmpty()) b.append(" · ").append(location.trim());
        if (radiusKm > 0) b.append(" · ").append(radiusKm).append(" km");
        if (minPrice > 0 || maxPrice > 0) {
            b.append(" · ");
            if (minPrice > 0 && maxPrice > 0) b.append('£').append(minPrice).append("–£").append(maxPrice);
            else if (minPrice > 0) b.append("£").append(minPrice).append('+');
            else b.append("up to £").append(maxPrice);
        }
        if (!include.trim().isEmpty()) b.append(" · must have: ").append(include.trim());
        if (!exclude.trim().isEmpty()) b.append(" · not: ").append(exclude.trim());
        return b.toString();
    }

    public boolean matches(Listing l) {
        String t = (l.title == null ? "" : l.title).toLowerCase(Locale.ROOT);
        for (String w : words(include)) if (!t.contains(w)) return false;
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
                || maxPrice != o.maxPrice;
    }
}
