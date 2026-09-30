package com.gpsmhn.tools;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Lưu các địa điểm đã ghim và route (chuyển từ CollectionManager/RouteSetting của AnyTo).
 * Lưu bằng SharedPreferences dạng JSON, không cần database.
 */
public final class PlaceStore {

    public static final class Place {
        public String name;
        public double lat, lng;
        public Place(String name, double lat, double lng) { this.name = name; this.lat = lat; this.lng = lng; }
    }

    private static final String PREFS = "tsbot_tools_places";
    private static final String K_PLACES = "places";
    private static final String K_ROUTE = "route";

    private final SharedPreferences prefs;

    public PlaceStore(Context context) { prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public List<Place> places() {
        List<Place> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(K_PLACES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Place(o.optString("name"), o.optDouble("lat"), o.optDouble("lng")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public void addPlace(String name, double lat, double lng) {
        List<Place> list = places();
        list.add(new Place(name, lat, lng));
        savePlaces(list);
    }

    public void removePlace(int index) {
        List<Place> list = places();
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            savePlaces(list);
        }
    }

    public void clearPlaces() { savePlaces(new ArrayList<>()); }

    private void savePlaces(List<Place> list) {
        JSONArray arr = new JSONArray();
        for (Place p : list) {
            try { arr.put(new JSONObject().put("name", p.name).put("lat", p.lat).put("lng", p.lng)); }
            catch (Exception ignored) { }
        }
        prefs.edit().putString(K_PLACES, arr.toString()).apply();
    }

    public List<double[]> route() {
        List<double[]> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(K_ROUTE, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONArray p = arr.getJSONArray(i);
                out.add(new double[]{p.optDouble(0), p.optDouble(1)});
            }
        } catch (Exception ignored) { }
        return out;
    }

    public void saveRoute(List<double[]> route) {
        JSONArray arr = new JSONArray();
        for (double[] p : route) {
            try {
                JSONArray q = new JSONArray();
                q.put(p[0]);
                q.put(p[1]);
                arr.put(q);
            } catch (Exception ignored) { }
        }
        prefs.edit().putString(K_ROUTE, arr.toString()).apply();
    }
}
