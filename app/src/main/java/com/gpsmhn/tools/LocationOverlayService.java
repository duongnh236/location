package com.gpsmhn.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gpsmhn.R;

/**
 * Joystick nổi trên màn hình (chuyển từ JoyStick overlay của AnyTo). Cần quyền "hiển thị trên
 * ứng dụng khác" (SYSTEM_ALERT_WINDOW). Joystick điều khiển {@link FakeGpsEngine} dùng chung.
 */
public class LocationOverlayService extends Service {

    private static final String CHANNEL = "overlay";
    private static final int NOTIF_ID = 8;

    private WindowManager windowManager;
    private View overlay;
    private WindowManager.LayoutParams params;
    private FakeGpsEngine engine;

    @Override public void onCreate() {
        super.onCreate();
        engine = FakeGpsEngine.get(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, notification());
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (overlay == null) showOverlay();
        return START_NOT_STICKY;
    }

    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.START;
        params.x = dp(16);
        params.y = dp(80);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.argb(150, 8, 17, 31));
        box.setPadding(dp(8), dp(6), dp(8), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Joystick GPS");
        title.setTextColor(Color.rgb(213, 168, 78));
        title.setTextSize(12);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = new TextView(this);
        close.setText("  ✕  ");
        close.setTextColor(Color.WHITE);
        close.setTextSize(14);
        close.setOnClickListener(v -> stopSelf());
        header.addView(close);
        box.addView(header, new LinearLayout.LayoutParams(dp(170), -2));

        JoystickView joystick = new JoystickView(this);
        joystick.setListener(new JoystickView.Listener() {
            @Override public void onMove(double dx, double dy) {
                if (!engine.isRunning()) engine.startSim();
                engine.setJoystick(dx, dy);
            }
            @Override public void onRelease() { engine.stopJoystick(); }
        });
        box.addView(joystick, new LinearLayout.LayoutParams(dp(170), dp(170)));

        // Kéo thanh tiêu đề để di chuyển overlay.
        header.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            int startParamsX, startParamsY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX(); startY = e.getRawY();
                        startParamsX = params.x; startParamsY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = startParamsX + (int) (e.getRawX() - startX);
                        params.y = startParamsY - (int) (e.getRawY() - startY);
                        windowManager.updateViewLayout(overlay, params);
                        return true;
                    default:
                        return false;
                }
            }
        });

        overlay = box;
        try {
            windowManager.addView(overlay, params);
        } catch (Exception e) {
            overlay = null;
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        if (windowManager != null && overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) { }
        }
        overlay = null;
        super.onDestroy();
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Joystick GPS", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, ToolsActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("aTSBot • Joystick GPS")
                .setContentText("Joystick đang nổi trên màn hình")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override public IBinder onBind(Intent intent) { return null; }
}
