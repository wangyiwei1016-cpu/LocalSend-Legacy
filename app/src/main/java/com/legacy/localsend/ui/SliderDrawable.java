package com.legacy.localsend.ui;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 主题色滑杆：8dp 细轨道 + 22dp 白色描边圆点。
 *
 * 轨道、进度和圆点都在同一个 drawable 里按同一条中线绘制，不再依赖
 * SeekBar 自己定位 thumb。老系统（Android 4.x）会把进度 drawable 拉满
 * SeekBar 高度，并把 thumb 往上放约 10px，看起来圆点就是歪的。
 */
final class SliderDrawable extends Drawable {

    private static final float DENSITY = Resources.getSystem().getDisplayMetrics().density;
    private static final float TRACK_HEIGHT_DP = 8f;
    private static final float KNOB_RADIUS_DP = 10f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int level;

    private SliderDrawable() {
    }

    static Drawable track() {
        return new SliderDrawable();
    }

    @Override
    public int getIntrinsicHeight() {
        return dp(TRACK_HEIGHT_DP);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        float centerY = bounds.exactCenterY();
        float barHalf = dp(TRACK_HEIGHT_DP) / 2f;
        float radius = Math.min(barHalf, bounds.width() / 2f);

        float ratio = level / 10000f;
        if (ratio < 0f) {
            ratio = 0f;
        } else if (ratio > 1f) {
            ratio = 1f;
        }
        float knobX = bounds.left + bounds.width() * ratio;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Theme.primaryContainer);
        rect.set(bounds.left, centerY - barHalf, bounds.right, centerY + barHalf);
        canvas.drawRoundRect(rect, radius, radius, paint);

        if (ratio > 0f) {
            paint.setColor(Theme.primary);
            rect.set(bounds.left, centerY - barHalf, knobX, centerY + barHalf);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        float knobRadius = dp(KNOB_RADIUS_DP);
        paint.setColor(Theme.surface);
        canvas.drawCircle(knobX, centerY, knobRadius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Theme.primary);
        canvas.drawCircle(knobX, centerY, knobRadius, paint);
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
