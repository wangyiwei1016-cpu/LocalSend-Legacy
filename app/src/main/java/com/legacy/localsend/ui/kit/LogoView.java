package com.legacy.localsend.ui.kit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.legacy.localsend.ui.Theme;

/** 接收页 Logo：蓝色虚线圆环（12 段）+ 中心实心圆。 */
public class LogoView extends View {

    private final Paint paint = new Paint();
    private final RectF rect = new RectF();

    public LogoView(Context context) {
        super(context);
        init();
    }

    public LogoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0) {
            return;
        }
        float size = Math.min(width, height);
        float cx = getPaddingLeft() + width / 2f;
        float cy = getPaddingTop() + height / 2f;
        float ringRadius = size * 0.33f;
        float strokeWidth = size * 0.07f;
        float centerRadius = size * 0.185f;

        paint.setColor(Theme.primary);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        rect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius);
        for (int i = 0; i < 12; i++) {
            float start = i * 30f + 9f;
            canvas.drawArc(rect, start, 12f, false, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, centerRadius, paint);
    }
}
