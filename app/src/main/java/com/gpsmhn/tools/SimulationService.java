package com.gpsmhn.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import com.gpsmhn.R;

/**
 * Foreground service giữ app chạy nền, để fen qua MHN custom thao tác mà vị trí ảo / binder /
 * route vẫn tiếp tục. Dùng type {@code specialUse} (không cần quyền runtime, không giới hạn giờ).
 *
 * <p>Service chỉ giữ tiến trình sống và đảm bảo vòng lặp mô phỏng của {@link FakeGpsEngine} đang chạy;
 * việc bật mock provider vẫn do nút BẬT GIẢ LẬP GPS quyết định.
 */
public class SimulationService extends Service {

    private static final String CHANNEL = "sim";
    private static final int NOTIF = 10;
    private static volatile boolean running;

    public static boolean isRunning() { return running; }

    public static void start(Context c) {
        try { c.startForegroundService(new Intent(c, SimulationService.class)); } catch (Exception ignored) { }
    }

    public static void stop(Context c) {
        try { c.stopService(new Intent(c, SimulationService.class)); } catch (Exception ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF, notification());
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        FakeGpsEngine.get(this).startSim();   // giữ mô phỏng sống khi app ra nền
        return START_STICKY;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "GPS MHN chạy nền", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, ToolsActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("GPS MHN đang chạy nền")
                .setContentText("Vị trí ảo vẫn hoạt động khi bạn qua app khác")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
