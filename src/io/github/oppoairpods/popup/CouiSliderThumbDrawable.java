package io.github.oppoairpods.popup;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.AccelerateDecelerateInterpolator;

/** COUI Slider thumb: white 6dp circle, 8dp while pressed, with a 4dp shadow. */
final class CouiSliderThumbDrawable extends Drawable {
    private static final int THUMB_COLOR = 0xFFFFFFFF;
    private static final int SHADOW_COLOR = 0x1A000000;

    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float restRadius;
    private final float pressedRadius;
    private final float shadowRadius;
    private final float shadowOffset;
    private float pressFraction;
    private ValueAnimator pressAnimator;

    CouiSliderThumbDrawable(float density) {
        this.restRadius = density * 6.0f;
        this.pressedRadius = density * 8.0f;
        this.shadowRadius = density * 4.0f;
        this.shadowOffset = density * 2.0f;
        thumbPaint.setColor(THUMB_COLOR);
    }

    @Override
    public void draw(Canvas canvas) {
        float radius = restRadius + (pressedRadius - restRadius) * pressFraction;
        float centerX = getBounds().exactCenterX();
        float centerY = getBounds().exactCenterY();
        float shadowOuter = radius + shadowRadius;
        float shadowCenterY = centerY + shadowOffset;

        shadowPaint.setShader(new RadialGradient(centerX, shadowCenterY, shadowOuter,
                new int[]{SHADOW_COLOR, SHADOW_COLOR, 0x00000000},
                new float[]{0.0f, radius / shadowOuter, 1.0f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, shadowCenterY, shadowOuter, shadowPaint);
        canvas.drawCircle(centerX, centerY, radius, thumbPaint);
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
        animatePress(pressed ? 1.0f : 0.0f);
        return true;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    private void animatePress(float target) {
        if (pressAnimator != null) pressAnimator.cancel();
        pressAnimator = ValueAnimator.ofFloat(pressFraction, target);
        pressAnimator.setDuration(120L);
        pressAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pressAnimator.addUpdateListener(animation -> {
            pressFraction = (float) animation.getAnimatedValue();
            invalidateSelf();
        });
        pressAnimator.start();
    }

    @Override
    public void jumpToCurrentState() {
        super.jumpToCurrentState();
        if (pressAnimator != null) pressAnimator.cancel();
    }

    @Override
    public void setAlpha(int alpha) {
        thumbPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        thumbPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(pressedRadius * 2.0f + shadowRadius * 2.0f);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(pressedRadius * 2.0f + shadowRadius * 2.0f + shadowOffset);
    }
}
