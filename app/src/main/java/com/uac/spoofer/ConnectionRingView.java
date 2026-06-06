package com.uac.spoofer;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class ConnectionRingView extends View {
    public static final int MODE_IDLE = 0;
    public static final int MODE_LOADING = 1;
    public static final int MODE_CONNECTED = 2;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringBounds = new RectF();
    private ValueAnimator animator;
    private float rotation = 0f;
    private int mode = MODE_IDLE;

    public ConnectionRingView(Context context) {
        super(context);
        init();
    }

    public ConnectionRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ConnectionRingView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        basePaint.setStyle(Paint.Style.STROKE);
        basePaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setMode(int newMode) {
        if (mode == newMode) {
            return;
        }
        mode = newMode;
        if (mode == MODE_LOADING) {
            startAnimator();
        } else {
            stopAnimator();
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimator();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int size = Math.min(getWidth(), getHeight());
        float stroke = Math.max(6f, size * 0.045f);
        float pad = stroke + 8f;
        ringBounds.set(
                (getWidth() - size) / 2f + pad,
                (getHeight() - size) / 2f + pad,
                (getWidth() + size) / 2f - pad,
                (getHeight() + size) / 2f - pad
        );
        basePaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);
        basePaint.setColor(color(mode == MODE_CONNECTED ? R.color.status_running_line : R.color.input_line));
        arcPaint.setColor(color(mode == MODE_CONNECTED ? R.color.accent_green : R.color.accent));
        canvas.drawArc(ringBounds, -90, 360, false, basePaint);
        if (mode == MODE_LOADING) {
            canvas.drawArc(ringBounds, rotation, 92, false, arcPaint);
            canvas.drawArc(ringBounds, rotation + 148, 42, false, arcPaint);
        } else if (mode == MODE_CONNECTED) {
            canvas.drawArc(ringBounds, -90, 360, false, arcPaint);
        }
    }

    private void startAnimator() {
        if (animator != null && animator.isRunning()) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(1050);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            rotation = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @SuppressWarnings("deprecation")
    private int color(int colorId) {
        return getResources().getColor(colorId);
    }
}
