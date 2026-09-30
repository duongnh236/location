package com.gpsmhn.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bản đồ nền OSM cho tab TIỆN ÍCH (chuyển ý tưởng "bản đồ + đánh dấu vị trí" của AnyTo).
 *
 * <p>AnyTo dùng Mapbox; Mapbox cần token trả phí nên ở đây dùng tile OpenStreetMap miễn phí
 * để project build được ngay mà không cần khoá riêng. Có: kéo, chụm để zoom, chạm để chọn điểm,
 * marker đã lưu (ghim), vị trí hiện tại và đường route.
 */
public class OsmMapView extends View {

    public interface Listener {
        void onMapTap(double lat, double lng);
        void onCurrentChanged(double lat, double lng);
    }

    private static final int MIN_ZOOM = 3, MAX_ZOOM = 19;
    private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";

    private double centerLat = 21.027764, centerLng = 105.834160;
    private double zoom = 15;
    private boolean centered = false;

    private double curLat, curLng;
    private boolean hasCurrent;

    private double realLat, realLng;
    private boolean hasReal;

    private final List<double[]> markers = new ArrayList<>();     // {lat,lng}
    private final List<String> markerLabels = new ArrayList<>();
    private final List<double[]> route = new ArrayList<>();

    private Listener listener;

    // Touch state
    private float lastX, lastY, downX, downY;
    private long downTime;
    private boolean dragging;
    private float pinchStartDist;
    private double pinchStartZoom;
    private float pinchFocusX, pinchFocusY;

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final TileStore tiles;
    private final ExecutorService loader = Executors.newFixedThreadPool(4);

    public OsmMapView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(226, 236, 244));
        textPaint.setTextSize(dp(11));
        textPaint.setColor(Color.WHITE);
        markerPaint.setColor(Color.rgb(200, 60, 60));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(4));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(Color.rgb(255, 170, 30));
        tiles = new TileStore(context);
    }

    public void setListener(Listener l) { listener = l; }

    public double zoom() { return zoom; }
    public double centerLat() { return centerLat; }
    public double centerLng() { return centerLng; }

    public void setZoom(double z) { zoom = clampZoom(z); invalidate(); }

    public void centerOn(double lat, double lng, boolean animate) {
        centerLat = lat;
        centerLng = lng;
        centered = true;
        invalidate();
    }

    public void setCurrent(double lat, double lng) {
        curLat = lat; curLng = lng; hasCurrent = true;
        if (!centered) centerOn(lat, lng, false);
        invalidate();
    }

    /** Vị trí GPS thật của máy (chấm xanh lá), khác với vị trí đang giả lập. */
    public void setRealLocation(double lat, double lng) {
        realLat = lat; realLng = lng; hasReal = true;
        invalidate();
    }

    public void setMarkers(List<double[]> pts, List<String> labels) {
        markers.clear();
        markerLabels.clear();
        if (pts != null) markers.addAll(pts);
        if (labels != null) markerLabels.addAll(labels);
        invalidate();
    }

    public void setRoute(List<double[]> pts) {
        route.clear();
        if (pts != null) route.addAll(pts);
        invalidate();
    }

    // ------------------------------------------------------------------ projection

    private static double worldSize(int z) { return 256.0 * (1 << z); }

    private static double[] latLngToWorld(double lat, double lng, int z) {
        double world = worldSize(z);
        double x = (lng + 180) / 360 * world;
        double sinLat = Math.sin(Math.toRadians(lat));
        double y = (0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)) * world;
        return new double[]{x, y};
    }

    private static double[] worldToLatLng(double x, double y, int z) {
        double world = worldSize(z);
        double lng = x / world * 360 - 180;
        double n = Math.PI - 2 * Math.PI * y / world;
        double lat = Math.toDegrees(Math.atan(Math.sinh(n)));
        return new double[]{lat, lng};
    }

    private int tileZoom() {
        int z = (int) Math.round(zoom);
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    private float[] project(double lat, double lng, int tz, double scale, double cxWorld, double cyWorld) {
        double[] w = latLngToWorld(lat, lng, tz);
        return new float[]{
                (float) ((w[0] - cxWorld) * scale + getWidth() / 2.0),
                (float) ((w[1] - cyWorld) * scale + getHeight() / 2.0)
        };
    }

    private double[] unproject(float sx, float sy, int tz, double scale, double cxWorld, double cyWorld) {
        double wx = (sx - getWidth() / 2.0) / scale + cxWorld;
        double wy = (sy - getHeight() / 2.0) / scale + cyWorld;
        return worldToLatLng(wx, wy, tz);
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int tz = tileZoom();
        double scale = Math.pow(2, zoom - tz);
        double[] centerWorld = latLngToWorld(centerLat, centerLng, tz);
        double cxWorld = centerWorld[0], cyWorld = centerWorld[1];

        double halfW = (w / 2.0) / scale, halfH = (h / 2.0) / scale;
        int minX = (int) Math.floor((cxWorld - halfW) / 256);
        int maxX = (int) Math.floor((cxWorld + halfW) / 256);
        int minY = (int) Math.floor((cyWorld - halfH) / 256);
        int maxY = (int) Math.floor((cyWorld + halfH) / 256);

        int maxTile = (1 << tz) - 1;
        int tileOnScreen = (int) Math.ceil(256 * scale);
        for (int tx = minX; tx <= maxX; tx++) {
            for (int ty = minY; ty <= maxY; ty++) {
                if (ty < 0 || ty > maxTile) continue;
                int wrappedX = ((tx % (maxTile + 1)) + (maxTile + 1)) % (maxTile + 1);
                Bitmap bmp = tiles.get(tz, wrappedX, ty);
                float left = (float) ((tx * 256 - cxWorld) * scale + w / 2.0);
                float top = (float) ((ty * 256 - cyWorld) * scale + h / 2.0);
                if (bmp != null) {
                    c.drawBitmap(bmp, null, new android.graphics.RectF(left, top, left + tileOnScreen, top + tileOnScreen), tilePaint);
                } else {
                    tilePaint.setColor(Color.rgb(210, 222, 232));
                    c.drawRect(left, top, left + tileOnScreen, top + tileOnScreen, tilePaint);
                    tiles.request(tz, wrappedX, ty);
                }
            }
        }

        // Route (cam)
        if (route.size() > 1) {
            Path path = new Path();
            for (int i = 0; i < route.size(); i++) {
                float[] p = project(route.get(i)[0], route.get(i)[1], tz, scale, cxWorld, cyWorld);
                if (i == 0) path.moveTo(p[0], p[1]); else path.lineTo(p[0], p[1]);
            }
            c.drawPath(path, linePaint);
        }

        // Markers
        for (int i = 0; i < markers.size(); i++) {
            float[] p = project(markers.get(i)[0], markers.get(i)[1], tz, scale, cxWorld, cyWorld);
            drawPin(c, p[0], p[1], markerPaint.getColor());
            if (i < markerLabels.size() && markerLabels.get(i) != null && !markerLabels.get(i).isEmpty()) {
                drawLabel(c, markerLabels.get(i), p[0] + dp(9), p[1] - dp(10));
            }
        }

        // Current position
        if (hasCurrent) {
            float[] p = project(curLat, curLng, tz, scale, cxWorld, cyWorld);
            markerPaint.setColor(Color.rgb(40, 120, 230));
            c.drawCircle(p[0], p[1], dp(9), markerPaint);
            markerPaint.setStyle(Paint.Style.STROKE);
            markerPaint.setStrokeWidth(dp(2));
            markerPaint.setColor(Color.WHITE);
            c.drawCircle(p[0], p[1], dp(9), markerPaint);
            markerPaint.setStyle(Paint.Style.FILL);
            c.drawCircle(p[0], p[1], dp(3), markerPaint);
            drawLabel(c, "Vị trí hiện tại", p[0] + dp(12), p[1] + dp(4));
        }

        // Vị trí GPS thật của máy
        if (hasReal) {
            float[] p = project(realLat, realLng, tz, scale, cxWorld, cyWorld);
            markerPaint.setStyle(Paint.Style.FILL);
            markerPaint.setColor(Color.rgb(30, 200, 110));
            c.drawCircle(p[0], p[1], dp(9), markerPaint);
            markerPaint.setStyle(Paint.Style.STROKE);
            markerPaint.setStrokeWidth(dp(2));
            markerPaint.setColor(Color.WHITE);
            c.drawCircle(p[0], p[1], dp(9), markerPaint);
            markerPaint.setStyle(Paint.Style.FILL);
            drawLabel(c, "GPS thật", p[0] + dp(12), p[1] + dp(4));
        }
    }

    private void drawPin(Canvas c, float x, float y, int color) {
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(color);
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x - dp(7), y - dp(18));
        p.lineTo(x + dp(7), y - dp(18));
        p.close();
        c.drawPath(p, markerPaint);
        c.drawCircle(x, y - dp(20), dp(8), markerPaint);
        markerPaint.setColor(Color.WHITE);
        c.drawCircle(x, y - dp(20), dp(3.5f), markerPaint);
    }

    private void drawLabel(Canvas c, String s, float x, float y) {
        textPaint.setColor(Color.rgb(8, 17, 31));
        float w = textPaint.measureText(s);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(210, 255, 255, 255));
        c.drawRoundRect(x - dp(3), y - dp(12), x + w + dp(3), y + dp(3), dp(4), dp(4), bg);
        c.drawText(s, x, y, textPaint);
    }

    // ------------------------------------------------------------------ touch

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = downX = e.getX();
                lastY = downY = e.getY();
                downTime = System.currentTimeMillis();
                dragging = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    pinchStartDist = spacing(e);
                    pinchStartZoom = zoom;
                    pinchFocusX = (e.getX(0) + e.getX(1)) / 2;
                    pinchFocusY = (e.getY(0) + e.getY(1)) / 2;
                    dragging = false;
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (e.getPointerCount() >= 2) {
                    float dist = spacing(e);
                    if (pinchStartDist > 1) {
                        double factor = dist / pinchStartDist;
                        zoom = clampZoom(pinchStartZoom + Math.log(factor) / Math.log(2));
                        invalidate();
                    }
                    dragging = true;
                    return true;
                }
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                lastX = e.getX();
                lastY = e.getY();
                if (Math.abs(e.getX() - downX) > dp(6) || Math.abs(e.getY() - downY) > dp(6)) dragging = true;
                if (dragging) panBy(dx, dy);
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!dragging && System.currentTimeMillis() - downTime < 400) {
                    int tz = tileZoom();
                    double scale = Math.pow(2, zoom - tz);
                    double[] cw = latLngToWorld(centerLat, centerLng, tz);
                    double[] ll = unproject(e.getX(), e.getY(), tz, scale, cw[0], cw[1]);
                    if (listener != null) listener.onMapTap(ll[0], ll[1]);
                }
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void panBy(float dx, float dy) {
        int tz = tileZoom();
        double scale = Math.pow(2, zoom - tz);
        double[] cw = latLngToWorld(centerLat, centerLng, tz);
        double wx = cw[0] - dx / scale;
        double wy = cw[1] - dy / scale;
        double[] ll = worldToLatLng(wx, wy, tz);
        centerLat = ll[0];
        centerLng = ll[1];
        centered = true;
        invalidate();
        if (listener != null) listener.onCurrentChanged(centerLat, centerLng);
    }

    private static float spacing(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private static double clampZoom(double z) { return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z)); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    // ------------------------------------------------------------------ tiles

    private class TileStore {
        private final LruCache<String, Bitmap> memory;
        private final File dir;

        TileStore(Context context) {
            int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 6);
            memory = new LruCache<String, Bitmap>(maxKb) {
                @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount() / 1024; }
            };
            dir = new File(context.getCacheDir(), "osm_tiles");
            if (!dir.exists()) dir.mkdirs();
        }

        Bitmap get(int z, int x, int y) {
            String key = z + "/" + x + "/" + y;
            Bitmap b = memory.get(key);
            if (b != null) return b;
            File f = fileFor(z, x, y);
            if (f.isFile()) {
                Bitmap decoded = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (decoded != null) { memory.put(key, decoded); return decoded; }
            }
            return null;
        }

        void request(int z, int x, int y) {
            String key = z + "/" + x + "/" + y;
            if (memory.get(key) != null) return;
            loader.execute(() -> {
                File f = fileFor(z, x, y);
                try {
                    if (!f.isFile()) {
                        URL url = new URL(String.format(java.util.Locale.US, TILE_URL, z, x, y));
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(8000);
                        conn.setRequestProperty("User-Agent", "tsbot-tools/1.0 (Android)");
                        conn.connect();
                        if (conn.getResponseCode() == 200) {
                            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                                byte[] buf = new byte[16384];
                                int n;
                                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                            }
                        }
                        conn.disconnect();
                    }
                    Bitmap decoded = BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (decoded != null) {
                        memory.put(key, decoded);
                        postInvalidate();
                    }
                } catch (Exception ignored) { }
            });
        }

        private File fileFor(int z, int x, int y) {
            File d = new File(dir, z + "/" + x);
            if (!d.exists()) d.mkdirs();
            return new File(d, y + ".png");
        }
    }
}
