package com.gpsmhn.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tìm đường (routing) theo phố bằng OSRM công khai — không cần token.
 *
 * <p>Gọi {@code https://router.project-osrm.org/route/v1/driving/{lng},{lat};{lng},{lat}} rồi đọc
 * GeoJSON trả về để lấy danh sách điểm dọc đường. Đường này được vẽ lên bản đồ và cho engine đi theo.
 */
public final class RouteFinder {

    public static final class Result {
        public final List<double[]> path;       // {lat, lng}
        public final double distanceMeters;
        public final double durationSeconds;
        public Result(List<double[]> path, double distanceMeters, double durationSeconds) {
            this.path = path;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
        }
    }

    private static final String BASE = "https://router.project-osrm.org/route/v1/driving/";

    private RouteFinder() { }

    public static Result fetch(double fromLat, double fromLng, double toLat, double toLng) throws Exception {
        String url = BASE + fromLng + "," + fromLat + ";" + toLng + "," + toLat
                + "?overview=full&geometries=geojson";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("User-Agent", "gps-mhn/1.0");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject o = new JSONObject(sb.toString());
            if (!"Ok".equals(o.optString("code"))) throw new Exception("OSRM code=" + o.optString("code"));
            JSONArray routes = o.optJSONArray("routes");
            if (routes == null || routes.length() == 0) throw new Exception("không có đường");
            JSONObject route = routes.getJSONObject(0);
            double distance = route.optDouble("distance");
            double duration = route.optDouble("duration");
            JSONObject geometry = route.optJSONObject("geometry");
            JSONArray coords = geometry == null ? null : geometry.optJSONArray("coordinates");
            List<double[]> path = new ArrayList<>();
            if (coords != null) {
                for (int i = 0; i < coords.length(); i++) {
                    JSONArray c = coords.optJSONArray(i);
                    if (c == null || c.length() < 2) continue;
                    path.add(new double[]{c.optDouble(1), c.optDouble(0)});   // [lat, lng]
                }
            }
            if (path.size() < 2) throw new Exception("đường rỗng");
            return new Result(path, distance, duration);
        } finally {
            conn.disconnect();
        }
    }
}
