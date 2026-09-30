package com.gpsmhn.tools;

import android.app.AppOpsManager;
import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Giả lập GPS (chuyển từ AnyTo MyService): tạo nhà cung cấp vị trí test của Android rồi bơm
 * toạ độ giả vào hệ thống, để app khác (Pokémon GO, bản đồ...) đọc được.
 *
 * <p>Cần bật app này làm "ứng dụng vị trí mô phỏng" trong Tùy chọn nhà phát triển.
 * Engine là singleton theo tiến trình để Activity và service joystick nổi dùng chung.
 *
 * <p>Có 3 kiểu di chuyển: teleport tức thời, joystick (vector hướng, tốc độ m/s), và route
 * (đi lần lượt qua danh sách điểm, có thể lặp).
 */
public final class FakeGpsEngine {

    public interface Listener {
        void onPosition(double lat, double lng, float bearing, double speed);
        void onEngineState(boolean running, String message);
    }

    private static volatile FakeGpsEngine instance;

    private final Context app;
    private final LocationManager lm;
    private final HandlerThread thread;
    private final Handler handler;
    private final List<Listener> listeners = new ArrayList<>();
    private final Object lock = new Object();

    private boolean running;
    private boolean mockActive;            // provider test đã cài được chưa (mock location)
    private double lat = 21.027764;       // Hà Nội
    private double lng = 105.834160;
    private float bearing = 0f;
    private double speed = 1.2;            // m/s (đi bộ)

    // Vector joystick (đã chuẩn hoá -1..1) và cờ đang giữ joystick.
    private double joyX, joyY;
    private boolean joyActive;

    // Route
    private final List<double[]> route = new ArrayList<>();
    private int routeIndex;
    private boolean routeLoop;

    // Đích di chuyển mượt (đi bộ / xe đạp / ô tô)
    private double targetLat, targetLng;
    private boolean hasTarget;

    private long lastTick;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            double dt = Math.min(0.5, (now - lastTick) / 1000.0);
            lastTick = now;
            boolean moved = false;
            synchronized (lock) {
                if (joyActive) moved = stepJoystick(dt) || moved;
                else if (running && route.size() > 1) moved = stepRoute(dt) || moved;
                else if (running && hasTarget) moved = stepTarget(dt) || moved;
            }
            if (running) publish(moved);
            handler.postDelayed(this, 100);
        }
    };

    public static FakeGpsEngine get(Context context) {
        FakeGpsEngine local = instance;
        if (local == null) {
            synchronized (FakeGpsEngine.class) {
                local = instance;
                if (local == null) {
                    local = new FakeGpsEngine(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private FakeGpsEngine(Context context) {
        this.app = context;
        this.lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.thread = new HandlerThread("fake-gps");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    // ------------------------------------------------------------------ state

    public void addListener(Listener l) { synchronized (lock) { if (!listeners.contains(l)) listeners.add(l); } }
    public void removeListener(Listener l) { synchronized (lock) { listeners.remove(l); } }

    public boolean isRunning() { return running; }
    public double lat() { return lat; }
    public double lng() { return lng; }
    public float bearing() { return bearing; }
    public double speed() { return speed; }
    public void setSpeed(double metersPerSecond) { speed = Math.max(0.1, metersPerSecond); }

    public boolean isJoystickActive() { return joyActive; }

    /** App đã được chọn làm nguồn vị trí mô phỏng chưa. */
    public static boolean hasMockPermission(Context context) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                        android.os.Process.myUid(), context.getPackageName());
            } else {
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                        android.os.Process.myUid(), context.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ start / stop

    /** Bắt đầu vòng lặp mô phỏng (di chuyển + route + đẩy toạ độ qua binder). KHÔNG bật mock provider. */
    public void startSim() {
        if (running) return;
        running = true;
        lastTick = SystemClock.elapsedRealtime();
        handler.post(tick);
        publish(true);
        notifyState(true, stateMessage());
    }

    public void stopSim() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(tick);
        joyActive = false;
        notifyState(mockActive, stateMessage());
    }

    /**
     * Bật mock provider (nhà cung cấp vị trí test của Android) để app/game khác đọc được vị trí giả.
     * Đây chính là việc nút "BẬT GIẢ LẬP GPS" làm. Trả về cảnh báo (nếu có), hoặc null nếu OK.
     */
    public String enableMock() {
        String warning = null;
        try {
            removeProviders();
            addProvider(LocationManager.GPS_PROVIDER, true);
            addProvider(LocationManager.NETWORK_PROVIDER, false);
            mockActive = true;
        } catch (SecurityException e) {
            mockActive = false;
            warning = "Chưa chọn app làm \"ứng dụng vị trí mô phỏng\". Không đưa được vị trí giả cho app khác; "
                    + "vẫn có thể đẩy toạ độ qua binder MHN.";
        } catch (Exception e) {
            mockActive = false;
            warning = "Không tạo được nhà cung cấp vị trí: " + e.getMessage();
        }
        if (mockActive && !running) startSim();
        publish(true);
        notifyState(running, stateMessage());
        return warning;
    }

    /** Tắt mock provider; app khác trở lại đọc GPS thật. */
    public void disableMock() {
        if (mockActive) { try { removeProviders(); } catch (Exception ignored) { } }
        mockActive = false;
        publish(true);
        notifyState(running, stateMessage());
    }

    public boolean isMockActive() { return mockActive; }

    /** Bật cả mô phỏng lẫn mock provider (giữ cho tương thích). */
    public String start() { startSim(); return enableMock(); }

    /** Tắt cả mô phỏng lẫn mock provider. */
    public void stop() {
        stopSim();
        disableMock();
        notifyState(false, "Đã tắt giả lập GPS. Vị trí trở về GPS thật.");
    }

    private String stateMessage() {
        if (!running) return mockActive ? "Mock provider đang bật (chưa mô phỏng)" : "Đã dừng mô phỏng";
        return (mockActive ? "Đang giả lập GPS" : "Đang mô phỏng (mock provider tắt)")
                + " tại " + fmt(lat) + ", " + fmt(lng);
    }

    public void shutdown() {
        stop();
        thread.quitSafely();
    }

    // ------------------------------------------------------------------ moving

    /** Teleport tức thời tới toạ độ. */
    public void moveTo(double newLat, double newLng) {
        synchronized (lock) {
            lat = clampLat(newLat);
            lng = wrapLng(newLng);
            hasTarget = false;
        }
        publish(true);
    }

    /** Bắt đầu đi mượt tới đích (đi bộ / xe đạp / ô tô tuỳ tốc độ đã đặt). */
    public void startMoveTo(double destLat, double destLng) {
        synchronized (lock) {
            targetLat = clampLat(destLat);
            targetLng = wrapLng(destLng);
            hasTarget = true;
            route.clear();
            routeIndex = 0;
        }
    }

    public void cancelMoveTo() { synchronized (lock) { hasTarget = false; } }
    public boolean hasTarget() { return hasTarget; }
    public double targetLat() { return targetLat; }
    public double targetLng() { return targetLng; }

    public void nudge(double dLat, double dLng) {
        synchronized (lock) {
            lat = clampLat(lat + dLat);
            lng = wrapLng(lng + dLng);
        }
        publish(true);
    }

    /** Cập nhật vector joystick; dx/dy trong khoảng -1..1 (dy dương = kéo xuống). */
    public void setJoystick(double dx, double dy) {
        double mag = Math.hypot(dx, dy);
        if (mag > 1) { dx /= mag; dy /= mag; }
        synchronized (lock) {
            joyX = dx; joyY = dy;
            joyActive = Math.hypot(dx, dy) > 0.02;
            if (joyActive) bearing = (float) ((Math.toDegrees(Math.atan2(dx, -dy)) + 360) % 360);
        }
    }

    public void stopJoystick() { synchronized (lock) { joyActive = false; joyX = joyY = 0; } }

    // ------------------------------------------------------------------ route

    public void setRoute(List<double[]> points, boolean loop) {
        synchronized (lock) {
            route.clear();
            if (points != null) route.addAll(points);
            routeIndex = 0;
            routeLoop = loop;
            hasTarget = false;
        }
    }

    public void addRoutePoint(double pointLat, double pointLng) {
        synchronized (lock) { route.add(new double[]{pointLat, pointLng}); }
    }

    public List<double[]> route() { synchronized (lock) { return new ArrayList<>(route); } }
    public int routeIndex() { return routeIndex; }
    public void clearRoute() { synchronized (lock) { route.clear(); routeIndex = 0; } }
    public void setRouteLoop(boolean loop) { routeLoop = loop; }

    // ------------------------------------------------------------------ internal

    private void addProvider(String name, boolean gps) {
        Criteria c = new Criteria();
        c.setPowerRequirement(Criteria.POWER_LOW);
        c.setAccuracy(gps ? Criteria.ACCURACY_FINE : Criteria.ACCURACY_COARSE);
        if (Build.VERSION.SDK_INT >= 31) {
            ProviderProperties props = new ProviderProperties.Builder()
                    .setHasAltitudeSupport(true).setHasSpeedSupport(true).setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(gps ? ProviderProperties.ACCURACY_FINE : ProviderProperties.ACCURACY_COARSE)
                    .build();
            lm.addTestProvider(name, props);
        } else {
            lm.addTestProvider(name, false, true, true, false, true, true, true,
                    Criteria.POWER_LOW, gps ? Criteria.ACCURACY_FINE : Criteria.ACCURACY_COARSE);
        }
        lm.setTestProviderEnabled(name, true);
        lm.setTestProviderLocation(name, buildLocation(lat, lng, bearing, 0));
        try {
            lm.setTestProviderStatus(name, android.location.LocationProvider.AVAILABLE, null,
                    SystemClock.elapsedRealtime());
        } catch (Exception ignored) { }
    }

    private void removeProviders() {
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (lm.isProviderEnabled(p)) lm.setTestProviderEnabled(p, false);
            } catch (Exception ignored) { }
            try { lm.removeTestProvider(p); } catch (Exception ignored) { }
        }
    }

    private Location buildLocation(double la, double ln, float bear, float spd) {
        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(la);
        location.setLongitude(ln);
        location.setAccuracy(2.0f);
        location.setAltitude(55.0);
        location.setBearing(bear);
        location.setSpeed(spd);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        Bundle extras = new Bundle();
        extras.putInt("satellites", 7);
        location.setExtras(extras);
        return location;
    }

    private boolean stepJoystick(double dt) {
        double r = Math.hypot(joyX, joyY);
        if (r <= 0.02) return false;
        double meters = speed * r * dt;
        applyMove(bearing, meters, speed * r);
        return true;
    }

    private boolean stepRoute(double dt) {
        if (route.isEmpty()) return false;
        if (routeIndex >= route.size()) {
            if (!routeLoop) return false;
            routeIndex = 0;
        }
        double[] target = route.get(routeIndex);
        double metersLat = (target[0] - lat) * 111320.0;
        double metersLng = (target[1] - lng) * 111320.0 * Math.cos(Math.toRadians(lat));
        double dist = Math.hypot(metersLat, metersLng);
        double step = speed * dt;
        if (dist <= step || dist < 0.5) {
            lat = target[0];
            lng = target[1];
            routeIndex++;
            if (routeIndex >= route.size() && !routeLoop) {
                return true;
            }
            return true;
        }
        double heading = Math.toDegrees(Math.atan2(metersLng, metersLat));
        bearing = (float) ((heading + 360) % 360);
        lat += (step * Math.cos(Math.toRadians(bearing))) / 111320.0;
        lng += (step * Math.sin(Math.toRadians(bearing))) / (111320.0 * Math.cos(Math.toRadians(lat)));
        return true;
    }

    private boolean stepTarget(double dt) {
        double metersLat = (targetLat - lat) * 111320.0;
        double metersLng = (targetLng - lng) * 111320.0 * Math.cos(Math.toRadians(lat));
        double dist = Math.hypot(metersLat, metersLng);
        double step = speed * dt;
        if (dist <= step || dist < 0.5) {
            lat = targetLat;
            lng = targetLng;
            hasTarget = false;
            return true;
        }
        double heading = Math.toDegrees(Math.atan2(metersLng, metersLat));
        bearing = (float) ((heading + 360) % 360);
        lat += (step * Math.cos(Math.toRadians(bearing))) / 111320.0;
        lng += (step * Math.sin(Math.toRadians(bearing))) / (111320.0 * Math.cos(Math.toRadians(lat)));
        return true;
    }

    private void applyMove(double headingDeg, double meters, double currentSpeed) {
        lat += (meters * Math.cos(Math.toRadians(headingDeg))) / 111320.0;
        lng += (meters * Math.sin(Math.toRadians(headingDeg))) / (111320.0 * Math.cos(Math.toRadians(lat)));
        lat = clampLat(lat);
        lng = wrapLng(lng);
    }

    /** Bơm toạ độ hiện tại vào cả 2 provider và báo listener. */
    private void publish(boolean force) {
        if (!running) return;
        if (mockActive) {
            try {
                lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, buildLocation(lat, lng, bearing, (float) speed));
                lm.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, buildLocation(lat, lng, bearing, (float) speed));
            } catch (Exception ignored) { }
        }
        List<Listener> copy;
        synchronized (lock) { copy = new ArrayList<>(listeners); }
        for (Listener l : copy) {
            try { l.onPosition(lat, lng, bearing, speed); } catch (Exception ignored) { }
        }
    }

    private void notifyState(boolean isRunning, String message) {
        List<Listener> copy;
        synchronized (lock) { copy = new ArrayList<>(listeners); }
        for (Listener l : copy) {
            try { l.onEngineState(isRunning, message); } catch (Exception ignored) { }
        }
    }

    private static double clampLat(double v) { return Math.max(-85, Math.min(85, v)); }
    private static double wrapLng(double v) {
        while (v > 180) v -= 360;
        while (v < -180) v += 360;
        return v;
    }

    private static String fmt(double v) { return String.format(java.util.Locale.US, "%.5f", v); }
}
