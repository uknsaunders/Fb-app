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
    public String imageUrl = "";

    // Only for recent finds
    public String searchName = "";
    public long foundAt = 0;

    /** Thumbnail for the notification; never stored. */
    public transient Bitmap thumb;

    public String url() {
        return "https://www.facebook.com/marketplace/item/" + id + "/";
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
            j.put("image", imageUrl);
            j.put("search", searchName);
            j.put("foundAt", foundAt);
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
        l.imageUrl = j.optString("image", "");
        l.searchName = j.optString("search", "");
        l.foundAt = j.optLong("foundAt", 0);
        return l;
    }
}
