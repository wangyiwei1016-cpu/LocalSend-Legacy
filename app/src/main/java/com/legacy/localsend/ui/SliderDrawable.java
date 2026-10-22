package com.legacy.localsend.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.content.res.Resources;

/** 主题色滑杆：轨道 + 白色描边圆点，替代各厂商系统自带的彩色 SeekBar。 */
final class SliderDrawable extends Drawable {

    private static final float DENSITY = Resources.getSystem().getDisplayMetrics().density;
    private final boolean thumb;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int level;

    private SliderDrawable(boolean thumb) {
        this.thumb = thumb;
    }

    static Drawable track() {
        return new SliderDrawable(false);
    }

    static Drawable thumb() {
        return new SliderDrawable(true);
    }

    @Override
    public int getIntrinsicWidth() {
        return thumb ? dp(22) : -1;
    }

    @Override
    public int getIntrinsicHeight() {
        return thumb ? dp(22) : dp(8);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        if (thumb) {
            float cx = bounds.exactCenterX();
            float cy = bounds.exactCenterY();
            float radius = Math.min(bounds.width(), bounds.height()) / 2f - dp(1);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Theme.surface);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Theme.primary);
            canvas.drawCircle(cx, cy, radius, paint);
            return;
        }
        float radius = bounds.height() / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Theme.primaryContainer);
        rect.set(bounds);
        canvas.drawRoundRect(rect, radius, radius, paint);

        float ratio = level / 10000f;
        if (ratio <= 0f) {
            return;
        }
        if (ratio > 1f) {
            ratio = 1f;
        }
        paint.setColor(Theme.primary);
        rect.set(bounds.left, bounds.top,
                bounds.left + bounds.width() * ratio, bounds.bottom);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    @Override
    protected boolean onLevelChange(int level) {
        this.level = level;
        invalidateSelf();
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static int dp(float value) {
        return (int) (value * DENSITY + 0.5f);
    }
}
