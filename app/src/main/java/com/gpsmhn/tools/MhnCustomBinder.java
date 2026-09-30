package com.gpsmhn.tools;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

/**
 * Bind (AIDL) tới bản Monster Hunter Now đã patch ("MHN custom") để **đẩy toạ độ trực tiếp qua
 * binder**, không cần mock location.
 *
 * <p>Phía app MHN custom (đã kiểm tra trong monsterhunterCustom.apk) expose:
 * <pre>
 *   service   : com.imyfone.main.LocationServiceMHN   (exported=true, không permission)
 *   action    : com.imyfone.main.action_mhn
 *   interface : com.imyfone.main.LocaltionInterfaceMHN
 *       TX 1  void   changeLocation(double lat, double lng)
 *       TX 2  String getVersion()
 *       TX 3  void   sendKeepLive()
 * </pre>
 * Server side: {@code changeLocation} -> {@code NianticLabs.saveLocation(lat,lng)} -> reflect vào
 * plugin.apk ({@code LocationUtil.getInstance().save(lat,lng)}) để ghi đè vị trí trong game.
 *
 * <p>Lưu ý: plugin chỉ được nạp khi app MHN đã chạy (MagellanUnityPlayerActivity onCreate ->
 * NianticLabs.install). Vì vậy phải mở MHN custom ít nhất 1 lần trước khi đẩy toạ độ; nếu chưa,
 * lệnh changeLocation vẫn gửi được nhưng game chưa nhận.
 */
public final class MhnCustomBinder implements FakeGpsEngine.Listener {

    public interface Listener {
        void onMhnState(boolean connected, String message);
    }

    public static final String PACKAGE = "com.nianticlabs.monsterhunter";
    public static final String ACTION = "com.imyfone.main.action_mhn";
    public static final String DESCRIPTOR = "com.imyfone.main.LocaltionInterfaceMHN";

    private static final int TX_CHANGE_LOCATION = 1;
    private static final int TX_GET_VERSION = 2;
    private static final int TX_KEEP_LIVE = 3;

    private static final long REBIND_INTERVAL_MS = 5000L;
    private static final long KEEPALIVE_INTERVAL_MS = 2000L;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Listener listener;
    private boolean started;
    private boolean bound;
    private boolean connected;
    private boolean pushEnabled = true;
    private IBinder binder;
    private double lastLat = 0, lastLng = 0;
    private boolean hasLast = false;
    private String version = "";
    private String lastMessage = "Chưa kết nối MHN custom.";
    private long lastPushMs = 0;

    private final IBinder.DeathRecipient deathRecipient = this::onBinderDied;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            connected = true;
            binder = service;
            try { service.linkToDeath(deathRecipient, 0); } catch (Exception ignored) { }
            version = getVersion();
            if (pushEnabled && hasLast) changeLocation(lastLat, lastLng);
            notifyState(true, "✓ Đã kết nối MHN custom"
                    + (version == null || version.isEmpty() ? "" : " (v: " + version + ")"));
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            connected = false;
            binder = null;
            notifyState(false, "MHN custom ngắt kết nối, đang thử lại…");
            scheduleRebind();
        }

        @Override public void onBindingDied(ComponentName name) {
            connected = false;
            binder = null;
            safeUnbind();
            notifyState(false, "Bind MHN custom bị chết, đang thử lại…");
            scheduleRebind();
        }

        @Override public void onNullBinding(ComponentName name) {
            connected = false;
            binder = null;
            safeUnbind();
            notifyState(false, "MHN custom trả binder rỗng.");
            scheduleRebind();
        }
    };

    public MhnCustomBinder(Context context) { this.app = context.getApplicationContext(); }

    public void setListener(Listener l) { listener = l; if (l != null) l.onMhnState(connected, lastMessage); }

    public boolean isConnected() { return connected; }
    public boolean isPushEnabled() { return pushEnabled; }
    public void setPushEnabled(boolean enabled) {
        pushEnabled = enabled;
        if (enabled && connected && hasLast) changeLocation(lastLat, lastLng);
    }
    public String lastMessage() { return lastMessage; }

    /** App MHN đã cài chưa (không phân biệt bản thường hay bản patch). */
    public boolean isGameInstalled() {
        try {
            app.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public String start() {
        if (started) return null;
        started = true;
        if (!isGameInstalled()) {
            notifyState(false, "Chưa cài app " + PACKAGE + " (cần bản MHN đã patch).");
            return "Chưa cài " + PACKAGE;
        }
        // Nghe vị trí trực tiếp từ engine để đẩy sang MHN kể cả khi app ở nền (Activity đã onPause).
        FakeGpsEngine.get(app).addListener(this);
        bindNow();
        handler.postDelayed(rebindLoop, REBIND_INTERVAL_MS);
        handler.postDelayed(keepAliveLoop, KEEPALIVE_INTERVAL_MS);
        return null;
    }

    public void stop() {
        started = false;
        handler.removeCallbacks(rebindLoop);
        handler.removeCallbacks(keepAliveLoop);
        try { FakeGpsEngine.get(app).removeListener(this); } catch (Exception ignored) { }
        if (binder != null) {
            try { binder.unlinkToDeath(deathRecipient, 0); } catch (Exception ignored) { }
        }
        binder = null;
        connected = false;
        safeUnbind();
        notifyState(false, "Đã ngắt kết nối MHN custom.");
    }

    // Vị trí do engine phát ra -> đẩy thẳng vào game (chạy được cả khi app ở nền).
    @Override public void onPosition(double lat, double lng, float bearing, double speed) { pushLocation(lat, lng); }
    @Override public void onEngineState(boolean running, String message) { }

    /** Gọi mỗi khi vị trí giả lập đổi: gửi toạ độ vào game qua binder. */
    public void pushLocation(double lat, double lng) {
        lastLat = lat;
        lastLng = lng;
        hasLast = true;
        if (!connected || !pushEnabled) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPushMs < 150) return;     // gộp lệnh, tránh spam IPC khi joystick chạy 10Hz
        lastPushMs = now;
        changeLocation(lat, lng);
    }

    // ------------------------------------------------------------------ AIDL

    public void changeLocation(double lat, double lng) {
        IBinder b = binder;
        if (b == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeDouble(lat);
            data.writeDouble(lng);
            b.transact(TX_CHANGE_LOCATION, data, reply, 0);
            reply.readException();
        } catch (Exception ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public String getVersion() {
        IBinder b = binder;
        if (b == null) return "";
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            b.transact(TX_GET_VERSION, data, reply, 0);
            reply.readException();
            return reply.readString();
        } catch (Exception e) {
            return "";
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public void sendKeepLive() {
        IBinder b = binder;
        if (b == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            b.transact(TX_KEEP_LIVE, data, reply, 0);
            reply.readException();
        } catch (Exception ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // ------------------------------------------------------------------ internal

    private void bindNow() {
        if (bound) return;
        Intent intent = new Intent();
        intent.setPackage(PACKAGE);
        intent.setAction(ACTION);
        try {
            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                notifyState(false, "Không bind được (bản MHN hiện tại không expose service custom).");
            }
        } catch (Exception e) {
            bound = false;
            notifyState(false, "Lỗi bind MHN: " + e.getMessage());
        }
    }

    private void safeUnbind() {
        if (!bound) return;
        try { app.unbindService(connection); } catch (Exception ignored) { }
        bound = false;
    }

    private void onBinderDied() {
        connected = false;
        binder = null;
        safeUnbind();
        notifyState(false, "Binder MHN died, đang bind lại…");
        scheduleRebind();
    }

    private void scheduleRebind() {
        if (!started) return;
        handler.removeCallbacks(rebindLoop);
        handler.postDelayed(rebindLoop, REBIND_INTERVAL_MS);
    }

    private final Runnable rebindLoop = new Runnable() {
        @Override public void run() {
            if (!started) return;
            if (!connected || binder == null || !binder.isBinderAlive()) {
                if (bound && !connected) safeUnbind();
                bindNow();
            }
            handler.postDelayed(this, REBIND_INTERVAL_MS);
        }
    };

    /** Giữ service sống + nhắc lại toạ độ đang giả lập (game có thể reset vị trí). */
    private final Runnable keepAliveLoop = new Runnable() {
        @Override public void run() {
            if (!started) return;
            if (connected) {
                sendKeepLive();
                if (pushEnabled && hasLast) changeLocation(lastLat, lastLng);
            }
            handler.postDelayed(this, KEEPALIVE_INTERVAL_MS);
        }
    };

    private void notifyState(boolean isConnected, String message) {
        lastMessage = message;
        if (listener != null) listener.onMhnState(isConnected, message);
    }
}
