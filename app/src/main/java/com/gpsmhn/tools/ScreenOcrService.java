package com.gpsmhn.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.gpsmhn.R;

import java.nio.ByteBuffer;

/**
 * Chụp màn hình + OCR bằng MLKit (chuyển từ ScreenGrabber/OcrHelper của AnyTo).
 *
 * <p>Android 14 bắt buộc MediaProjection phải chạy trong foreground service kiểu mediaProjection,
 * nên phần chụp màn hình nằm ở service này. Kết quả OCR trả qua {@link Listener}.
 */
public class ScreenOcrService extends Service {

    public interface Listener {
        void onOcrText(String text, Bitmap screenshot);
        void onOcrError(String message);
    }

    private static final String CHANNEL = "ocr";
    private static final int NOTIF_ID = 9;

    private static volatile Listener listener;
    private static volatile boolean running;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread work;
    private Handler worker;
    private TextRecognizer recognizer;

    public static void setListener(Listener l) { listener = l; }
    public static boolean isRunning() { return running; }

    @Override public void onCreate() {
        super.onCreate();
        work = new HandlerThread("ocr");
        work.start();
        worker = new Handler(work.getLooper());
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if ("START".equals(action)) {
            startForeground(NOTIF_ID, notification());
            if (!startProjection(intent.getIntExtra("code", 0), (Intent) intent.getParcelableExtra("data"))) {
                notifyError("Không khởi động được chụp màn hình");
                stopSelf();
            }
        } else if ("CAPTURE".equals(action)) {
            capture();
        } else if ("STOP".equals(action)) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private boolean startProjection(int resultCode, Intent data) {
        if (running) return true;
        if (resultCode == 0 || data == null) return false;
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) return false;
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, worker);
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);
            int w = dm.widthPixels, h = dm.heightPixels;
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            display = projection.createVirtualDisplay("tsbot-ocr", w, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
            running = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void capture() {
        if (!running || reader == null) {
            notifyError("Chưa bật chụp màn hình. Hãy bấm \"BẬT CHỤP MÀN HÌNH\" trước.");
            return;
        }
        worker.post(() -> {
            Image image = null;
            for (int i = 0; i < 10 && image == null; i++) {
                try { image = reader.acquireLatestImage(); } catch (Exception ignored) { }
            }
            if (image == null) { notifyError("Không lấy được khung hình"); return; }
            Bitmap bitmap = imageToBitmap(image);
            image.close();
            if (bitmap == null) { notifyError("Không đọc được ảnh màn hình"); return; }
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener(result -> {
                        Listener l = listener;
                        if (l != null) l.onOcrText(result.getText(), bitmap);
                    })
                    .addOnFailureListener(e -> notifyError("OCR lỗi: " + e.getMessage()));
        });
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int width = image.getWidth(), height = image.getHeight();
            int rowPadding = rowStride - pixelStride * width;
            Bitmap padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
            if (cropped != padded) padded.recycle();
            return cropped;
        } catch (Exception e) {
            return null;
        }
    }

    private void notifyError(String message) {
        Listener l = listener;
        if (l != null) l.onOcrError(message);
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Chụp màn hình OCR", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, ToolsActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("aTSBot • OCR")
                .setContentText("Đang đọc màn hình game…")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        running = false;
        try { if (display != null) display.release(); } catch (Exception ignored) { }
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        try { if (recognizer != null) recognizer.close(); } catch (Exception ignored) { }
        if (work != null) work.quitSafely();
        display = null; reader = null; projection = null;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
