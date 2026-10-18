package com.listinghound.app;

import android.graphics.Bitmap;

import org.json.JSONException;
import org.json.JSONObject;

/** A Marketplace listing, as found in search results or kept in the "recent finds" list. */
public class Listing {
    public String id = "";
    public String title = "";
    public String price = "";
    /** Numeric price, or -1 when unknown. */
    public double amount = -1;
    public String city = "";
    public String state = "";
    /** Distance from the search centre in km, or -1 if unknown. */
    public double km = -1;
    public String imageUrl = "";

    // Only for recent finds
    public String searchName = "";
    public long foundAt = 0;
    /** "new", "drop" (price lowered) or "updated" (title changed). */
    public String kind = "new";
    public String oldPrice = "";
    public String oldTitle = "";
    /** When the seller posted it (ms); 0 = not looked up yet, -1 = unknown. */
    public long listedAt = 0;
    /** The search's previous successful check before this one spotted it; 0 = unknown. */
    public long prevCheckAt = 0;

    /** Thumbnail for the notification; never stored. */
    public transient Bitmap thumb;

    public String url() {
        return "https://www.facebook.com/marketplace/item/" + id + "/";
    }

    /** Identifies one alert (the same listing can alert again for a price drop). */
    public String key() {
        return id + ":" + kind + ":" + foundAt;
    }

    /** Main line for notifications and the finds list. */
    public String headline() {
        String p = priceOrBlank();
        switch (kind) {
            case "drop":
                return "Price drop " + (oldPrice.isEmpty() ? "" : oldPrice + " → ") + p + " · " + title;
            case "updated":
                return "Updated · " + (p.isEmpty() ? "" : p + " · ") + title;
            default:
                return (p.isEmpty() ? "" : p + " · ") + title;
        }
    }

    public String priceOrBlank() {
        return price == null ? "" : price;
    }

    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("id", id);
            j.put("title", title);
            j.put("price", price);
            j.put("amount", amount);
            j.put("city", city);
            j.put("state", state);
            j.put("km", km);
            j.put("image", imageUrl);
            j.put("search", searchName);
            j.put("foundAt", foundAt);
            j.put("kind", kind);
            j.put("oldPrice", oldPrice);
            j.put("oldTitle", oldTitle);
            j.put("listedAt", listedAt);
            j.put("prevCheckAt", prevCheckAt);
        } catch (JSONException ignored) {
        }
        return j;
    }

    public static Listing fromJson(JSONObject j) {
        Listing l = new Listing();
        l.id = j.optString("id", "");
        l.title = j.optString("title", "");
        l.price = j.optString("price", "");
        l.amount = j.optDouble("amount", -1);
        l.city = j.optString("city", "");
        l.state = j.optString("state", "");
        l.km = j.optDouble("km", -1);
        l.imageUrl = j.optString("image", "");
        l.searchName = j.optString("search", "");
        l.foundAt = j.optLong("foundAt", 0);
        l.kind = j.optString("kind", "new");
        l.oldPrice = j.optString("oldPrice", "");
        l.oldTitle = j.optString("oldTitle", "");
        l.listedAt = j.optLong("listedAt", 0);
        l.prevCheckAt = j.optLong("prevCheckAt", 0);
        return l;
    }
}
