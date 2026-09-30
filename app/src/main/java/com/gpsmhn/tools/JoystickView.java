package com.gpsmhn.tools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Joystick ảo (làm lại từ RockerView/JoyStick của AnyTo). Trả về vector chuẩn hoá -1..1.
 * dy dương = kéo xuống (theo hệ toạ độ màn hình).
 */
public class JoystickView extends View {

    public interface Listener {
        void onMove(double dx, double dy);
        void onRelease();
    }

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float cx, cy, baseRadius, knobRadius;
    private float knobX, knobY;
    private boolean active;
    private Listener listener;

    public JoystickView(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(4));
        ring.setColor(Color.argb(180, 74, 163, 255));
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.argb(60, 20, 60, 120));
        knob.setStyle(Paint.Style.FILL);
        knob.setColor(Color.argb(235, 213, 168, 78));
    }

    public void setListener(Listener l) { listener = l; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        cx = w / 2f;
        cy = h / 2f;
        baseRadius = Math.min(w, h) / 2f - dp(6);
        knobRadius = baseRadius / 2.6f;
        knobX = cx;
        knobY = cy;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.drawCircle(cx, cy, baseRadius, fill);
        c.drawCircle(cx, cy, baseRadius, ring);
        c.drawCircle(cx, cy, baseRadius * 0.16f, fill);
        c.drawCircle(knobX, knobY, knobRadius, knob);
        c.drawCircle(knobX, knobY, knobRadius * 0.55f, ring);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                active = true;
                float dx = e.getX() - cx, dy = e.getY() - cy;
                double dist = Math.hypot(dx, dy);
                double max = baseRadius;
                if (dist > max) { dx = (float) (dx / dist * max); dy = (float) (dy / dist * max); dist = max; }
                knobX = cx + dx;
                knobY = cy + dy;
                invalidate();
                if (listener != null) listener.onMove(dx / max, dy / max);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                active = false;
                knobX = cx;
                knobY = cy;
                invalidate();
                if (listener != null) listener.onRelease();
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    public boolean isActive() { return active; }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
