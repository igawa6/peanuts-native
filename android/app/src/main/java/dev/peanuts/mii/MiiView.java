package dev.peanuts.mii;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.TextureView;

/**
 * A Mii drawn live from the firmware's parts (MiiGl's preview): show() a Mii, and it is drawn again only when a value changed. The player turns the head by
 * dragging it or with the right stick; it settles back slowly to the front when let go (calm, no spring).
 */
public final class MiiView extends TextureView implements TextureView.SurfaceTextureListener {
    private MiiGl.Preview preview;
    private MiiStore.Mii shown;
    private float yaw, pitch, downX, downY, downYaw, downPitch;
    private boolean held;

    public MiiView(Context c) {
        super(c);
        setOpaque(false);
        setSurfaceTextureListener(this);
        setFocusable(false);
    }

    public void show(MiiStore.Mii m) {
        shown = m.copy(m.name);
        if (preview != null) preview.show(shown);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        preview = MiiGl.get(getContext()).preview(st, w, h);
        if (shown != null) preview.show(shown);
        preview.turn(yaw, pitch);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { if (preview != null) preview.size(w, h); }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (preview != null) { preview.release(); preview = null; }
        return false;   // the GL thread releases the surface texture's EGL surface first; Android frees it when the view goes
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    @SuppressLint("ClickableViewAccessibility")   // turning the head is a look, not an action
    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                held = true; downX = e.getX(); downY = e.getY(); downYaw = yaw; downPitch = pitch;
                removeCallbacks(settle);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                turn(downYaw + (e.getX() - downX) / getWidth() * 180f, downPitch + (e.getY() - downY) / getHeight() * 60f);
                return true;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                held = false;
                postDelayed(settle, 900);
                return true;
            default: return super.onTouchEvent(e);
        }
    }

    /** The right stick turns the head while it is held over (the editor forwards its motion events) */
    public boolean stick(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == 0) return false;
        float x = e.getAxisValue(MotionEvent.AXIS_Z), y = e.getAxisValue(MotionEvent.AXIS_RZ);
        if (Math.abs(x) < 0.15f && Math.abs(y) < 0.15f) { if (!held) postDelayed(settle, 900); return false; }
        removeCallbacks(settle);
        turn(x * 70f, y * 25f);
        return true;
    }

    private void turn(float y, float p) {
        yaw = Math.max(-80f, Math.min(80f, y)); pitch = Math.max(-25f, Math.min(25f, p));
        if (preview != null) preview.turn(yaw, pitch);
    }

    /** Back to the front over about half a second, eased, a step a frame */
    private final Runnable settle = new Runnable() {
        @Override public void run() {
            if (held) return;
            float y = yaw * 0.82f, p = pitch * 0.82f;
            if (Math.abs(y) < 0.4f && Math.abs(p) < 0.4f) { turn(0, 0); return; }
            turn(y, p);
            postOnAnimation(this);
        }
    };
}
