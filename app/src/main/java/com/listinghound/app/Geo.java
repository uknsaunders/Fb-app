package com.listinghound.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;

import java.util.List;
import java.util.Locale;

/** Turns town names into coordinates (cached) so distances can be checked on the phone. */
public final class Geo {
    private Geo() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("geo", Context.MODE_PRIVATE);
    }

    /** Returns {lat, lng}, or null if the place can't be found. Call off the main thread. */
    @SuppressWarnings("deprecation")
    public static double[] locate(Context c, String place) {
        if (place == null) return null;
        String key = place.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return null;
        String cached = p(c).getString(key, null);
        if (cached != null) {
            if (cached.equals("none")) return null;
            String[] parts = cached.split(",");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        }
        if (!Geocoder.isPresent()) return null;
        try {
            List<Address> res = new Geocoder(c, Locale.UK).getFromLocationName(place, 1);
            if (res == null || res.isEmpty()) {
                p(c).edit().putString(key, "none").apply();
                return null;
            }
            Address a = res.get(0);
            p(c).edit().putString(key, a.getLatitude() + "," + a.getLongitude()).apply();
            return new double[]{a.getLatitude(), a.getLongitude()};
        } catch (Exception e) {
            return null; // network hiccup: don't cache, try again next time
        }
    }

    /** The search's centre: its Marketplace location written as a place name. */
    public static double[] centre(Context c, Search s) {
        String loc = s.location.trim().replace('-', ' ').replace('_', ' ');
        if (loc.isEmpty()) return null;
        double[] r = locate(c, loc + ", United Kingdom");
        return r != null ? r : locate(c, loc);
    }

    public static double[] of(Context c, Listing l) {
        if (l.city.isEmpty()) return null;
        String q = l.city + (l.state.isEmpty() ? "" : ", " + l.state);
        return locate(c, q);
    }

    public static double km(double[] a, double[] b) {
        double r = 6371;
        double dLat = Math.toRadians(b[0] - a[0]);
        double dLng = Math.toRadians(b[1] - a[1]);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a[0])) * Math.cos(Math.toRadians(b[0]))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }
}
