package io.github.oppoairpods.popup;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.widget.SeekBar;

import java.lang.ref.WeakReference;

/** Small COUI-style tick dot: white on the active track, grey on the remainder. */
final class SliderTickDrawable extends Drawable {
    private static final int ACTIVE_COLOR = 0xFFFFFFFF;
    private static final int INACTIVE_COLOR = 0xFF8D939B;
    private static final int TRACK_ACTIVE_COLOR = 0xFF3478F6;
    private static final int TRACK_INACTIVE_COLOR = 0xFFD8DCE2;

    private final WeakReference<SeekBar> seekBar;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int radius;
    private final int trackHeight;
    private int alpha = 255;

    SliderTickDrawable(SeekBar seekBar, int radius, int trackHeight) {
        this.seekBar = new WeakReference<>(seekBar);
        this.radius = radius;
        this.trackHeight = trackHeight;
    }

    @Override
    public void draw(Canvas canvas) {
        SeekBar bar = seekBar.get();
        if (bar == null) return;
        int left = getBounds().left + bar.getPaddingLeft();
        int right = getBounds().right - bar.getPaddingRight();
        if (right <= left) return;
        float centerY = getBounds().centerY();
        float ratio = bar.getMax() <= 0 ? 0.0f : bar.getProgress() / (float) bar.getMax();
        float thumbX = left + (right - left) * ratio;
        float halfTrack = trackHeight / 2.0f;

        paint.setColor(TRACK_INACTIVE_COLOR);
        paint.setAlpha(alpha);
        canvas.drawRoundRect(new RectF(left, centerY - halfTrack, right, centerY + halfTrack),
                halfTrack, halfTrack, paint);

        paint.setColor(TRACK_ACTIVE_COLOR);
        float activeRight = Math.max(left + halfTrack, thumbX);
        canvas.drawRoundRect(new RectF(left, centerY - halfTrack, activeRight, centerY + halfTrack),
                halfTrack, halfTrack, paint);

        for (int i = 0; i <= 4; i++) {
            float x = left + (right - left) * (i / 4.0f);
            paint.setColor(x <= thumbX ? ACTIVE_COLOR : INACTIVE_COLOR);
            canvas.drawCircle(x, centerY, radius, paint);
        }
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
