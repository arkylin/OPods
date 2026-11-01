package io.github.oppoairpods.popup;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.widget.SeekBar;

import java.lang.ref.WeakReference;

/** Small COUI-style tick dot: white on the active track, grey on the remainder. */
final class SliderTickDrawable extends Drawable {
    private static final int ACTIVE_COLOR = 0xFFFFFFFF;
    private static final int INACTIVE_COLOR = 0xFF8D939B;

    private final WeakReference<SeekBar> seekBar;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int radius;
    private int alpha = 255;

    SliderTickDrawable(SeekBar seekBar, int radius) {
        this.seekBar = new WeakReference<>(seekBar);
        this.radius = radius;
    }

    @Override
    public void draw(Canvas canvas) {
        SeekBar bar = seekBar.get();
        Drawable thumb = bar == null ? null : bar.getThumb();
        boolean active = thumb != null
                && getBounds().centerX() <= thumb.getBounds().centerX();
        paint.setColor(active ? ACTIVE_COLOR : INACTIVE_COLOR);
        paint.setAlpha(alpha);
        canvas.drawCircle(getBounds().centerX(), getBounds().centerY(), radius, paint);
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
    public int getIntrinsicWidth() {
        return radius * 2;
    }

    @Override
    public int getIntrinsicHeight() {
        return radius * 2;
    }
}
