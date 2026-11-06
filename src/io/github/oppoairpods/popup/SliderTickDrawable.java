package io.github.oppoairpods.popup;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.SeekBar;

import java.lang.ref.WeakReference;

/** COUI Slider track: 20dp capsule, 1.4x pressed growth and 3dp key points. */
final class SliderTickDrawable extends Drawable {
    private static final int ACTIVE_COLOR = 0xFFFFFFFF;
    private static final int INACTIVE_COLOR = 0xFF8D939B;
    private static final int TRACK_ACTIVE_COLOR = 0xFF3478F6;
    private static final int TRACK_INACTIVE_COLOR = 0xFFD8DCE2;

    private final WeakReference<SeekBar> seekBar;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int trackHeight;
    private final int thumbWidth;
    private final int keyPointRadius;
    private float growFraction;
    private int alpha = 255;
    private ValueAnimator growAnimator;

    SliderTickDrawable(SeekBar seekBar, float density) {
        this.seekBar = new WeakReference<>(seekBar);
        this.trackHeight = Math.max(12, Math.round(density * 20.0f));
        this.thumbWidth = Math.max(20, Math.round(density * 24.0f));
        this.keyPointRadius = Math.max(2, Math.round(density * 3.0f));
    }

    @Override
    public void draw(Canvas canvas) {
        SeekBar bar = seekBar.get();
        if (bar == null) return;
        Rect bounds = getBounds();
        if (bounds.width() <= 0) return;

        float ratio = bar.getMax() <= 0 ? 0.0f : bar.getProgress() / (float) bar.getMax();
        float capInset = trackHeight * 0.7f;
        float availableWidth = Math.max(0f, bounds.width() - capInset * 2.0f);
        float thumbCenterX = bounds.left + bar.getPaddingLeft() + thumbWidth / 2.0f
                + availableWidth * ratio;
        float centerY = bounds.centerY();
        float backgroundHeight = trackHeight * (1.0f + 0.4f * growFraction);
        float backgroundLeft = bounds.left + capInset - backgroundHeight / 2.0f;
        float backgroundRight = bounds.right - capInset + backgroundHeight / 2.0f;

        setPaintColor(TRACK_INACTIVE_COLOR);
        canvas.drawRoundRect(new RectF(backgroundLeft, centerY - backgroundHeight / 2.0f,
                backgroundRight, centerY + backgroundHeight / 2.0f),
                backgroundHeight / 2.0f, backgroundHeight / 2.0f, paint);

        float halfTrack = trackHeight / 2.0f;
        float activeLeft = bounds.left + capInset - halfTrack;
        float activeRight = Math.max(activeLeft, thumbCenterX + halfTrack);
        setPaintColor(TRACK_ACTIVE_COLOR);
        canvas.drawRoundRect(new RectF(activeLeft, centerY - halfTrack,
                activeRight, centerY + halfTrack), halfTrack, halfTrack, paint);

        for (int i = 0; i <= 4; i++) {
            float x = bounds.left + capInset + availableWidth * (i / 4.0f);
            setPaintColor(x <= thumbCenterX ? ACTIVE_COLOR : INACTIVE_COLOR);
            canvas.drawCircle(x, centerY, keyPointRadius, paint);
        }
    }

    /** setColor() also overwrites the alpha channel, so it has to be reapplied afterwards. */
    private void setPaintColor(int color) {
        paint.setColor(color);
        paint.setAlpha(alpha);
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean pressed = false;
        for (int value : state) {
            if (value == android.R.attr.state_pressed) {
                pressed = true;
                break;
            }
        }
        animateGrow(pressed ? 1.0f : 0.0f);
        return true;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    private void animateGrow(float target) {
        if (growAnimator != null) growAnimator.cancel();
        growAnimator = ValueAnimator.ofFloat(growFraction, target);
        growAnimator.setDuration(183L);
        growAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        growAnimator.addUpdateListener(animation -> {
            growFraction = (float) animation.getAnimatedValue();
            invalidateSelf();
        });
        growAnimator.start();
    }

    @Override
    public void jumpToCurrentState() {
        super.jumpToCurrentState();
        SeekBar bar = seekBar.get();
        growFraction = bar != null && bar.isPressed() ? 1.0f : 0.0f;
        if (growAnimator != null) growAnimator.cancel();
        invalidateSelf();
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(trackHeight * 1.4f);
    }
}
