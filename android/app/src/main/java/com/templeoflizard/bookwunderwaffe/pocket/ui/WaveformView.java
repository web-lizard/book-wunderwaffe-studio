package com.templeoflizard.bookwunderwaffe.pocket.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;
import java.util.Random;

/**
 * Lightweight, dependency-free waveform/progress control for the Pocket player.
 *
 * <p>The Android media APIs do not expose decoded amplitudes without a full decoder.  The view
 * therefore renders a stable visual fingerprint derived from the selected asset id.  It still
 * behaves as a precise scrub control: touch and drag to seek, with the played part highlighted.</p>
 */
public final class WaveformView extends View {
    public interface OnSeekListener {
        void onSeek(float fraction, boolean finished);
    }

    private static final int BAR_COUNT = 72;

    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pendingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final float[] amplitudes = new float[BAR_COUNT];

    private float progress;
    private boolean dragging;
    private OnSeekListener onSeekListener;

    public WaveformView(Context context) {
        this(context, null);
    }

    public WaveformView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setFocusable(true);
        setClickable(true);
        panelPaint.setColor(Color.rgb(17, 17, 21));
        pendingPaint.setColor(Color.rgb(69, 69, 79));
        playedPaint.setColor(Color.rgb(196, 121, 255));
        playheadPaint.setColor(Color.rgb(85, 189, 178));
        seedBars("book-wunderwaffe-pocket");
    }

    public void setWaveSeed(String seed) {
        seedBars(seed == null ? "" : seed);
        invalidate();
    }

    public void setProgress(float fraction) {
        float normalized = Math.max(0f, Math.min(1f, fraction));
        if (Math.abs(progress - normalized) > 0.0005f) {
            progress = normalized;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setStateDescription(String.format(Locale.getDefault(), "Позиция %d процентов",
                        Math.round(progress * 100f)));
            }
            invalidate();
        }
    }

    public float getProgress() {
        return progress;
    }

    public void setOnSeekListener(OnSeekListener listener) {
        onSeekListener = listener;
    }

    private void seedBars(String seed) {
        Random random = new Random(seed.hashCode() * 31L + 0xB00C5EEDL);
        float previous = 0.42f;
        for (int i = 0; i < amplitudes.length; i++) {
            float wave = (float) (0.20 + 0.30 * Math.abs(Math.sin((i + seed.length()) * 0.37)));
            float noise = 0.18f + random.nextFloat() * 0.56f;
            previous = previous * 0.38f + (wave + noise) * 0.62f;
            amplitudes[i] = Math.max(0.18f, Math.min(1f, previous));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float radius = 10f * density;
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, panelPaint);

        float horizontalInset = 8f * density;
        float verticalInset = 9f * density;
        float availableWidth = Math.max(1f, getWidth() - horizontalInset * 2f);
        float availableHeight = Math.max(1f, getHeight() - verticalInset * 2f);
        float slot = availableWidth / BAR_COUNT;
        float barWidth = Math.max(1.5f * density, slot * 0.52f);
        float centerY = getHeight() / 2f;
        float playedX = horizontalInset + availableWidth * progress;

        for (int i = 0; i < BAR_COUNT; i++) {
            float x = horizontalInset + i * slot + (slot - barWidth) / 2f;
            float barHeight = Math.max(3f * density, availableHeight * amplitudes[i]);
            bar.set(x, centerY - barHeight / 2f, x + barWidth, centerY + barHeight / 2f);
            Paint paint = x + barWidth / 2f <= playedX ? playedPaint : pendingPaint;
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, paint);
        }

        if (progress > 0f && progress < 1f) {
            playheadPaint.setStrokeWidth(Math.max(1f, density));
            canvas.drawLine(playedX, verticalInset / 2f, playedX,
                    getHeight() - verticalInset / 2f, playheadPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                updateFromTouch(event.getX(), false);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) updateFromTouch(event.getX(), false);
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    updateFromTouch(event.getX(), true);
                    dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void updateFromTouch(float x, boolean finished) {
        float fraction = getWidth() <= 0 ? 0f : x / getWidth();
        setProgress(fraction);
        if (onSeekListener != null) onSeekListener.onSeek(progress, finished);
    }
}
