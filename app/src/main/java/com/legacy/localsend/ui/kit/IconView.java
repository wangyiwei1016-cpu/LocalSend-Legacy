package com.legacy.localsend.ui.kit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 纯 Canvas 手绘图标（无图片资源、无 vector drawable，API 8 可用）。
 * 在 24x24 逻辑坐标里绘制，再按控件可用区域缩放，保证各密度清晰。
 */
public class IconView extends View {

    public static final String WIFI = "wifi";
    public static final String SEND = "send";
    public static final String GEAR = "gear";
    public static final String FILE = "file";
    public static final String IMAGE = "image";
    public static final String CLIPBOARD = "clipboard";
    public static final String TEXT = "text";
    public static final String FOLDER = "folder";
    public static final String APPS = "apps";
    public static final String LAPTOP = "laptop";
    public static final String PHONE = "phone";
    public static final String REFRESH = "refresh";
    public static final String TARGET = "target";
    public static final String HEART = "heart";
    public static final String INFO = "info";
    public static final String GLOBE = "globe";
    public static final String CHEVRON = "chevron";
    public static final String CLOSE = "close";
    public static final String CHECK = "check";
    public static final String ALERT = "alert";

    private final Paint strokePaint = new Paint();
    private final Paint fillPaint = new Paint();
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private String iconName = "";
    private int iconColor = 0xFF111827;

    public IconView(Context context) {
        super(context);
        init();
    }

    public IconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        strokePaint.setAntiAlias(true);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setAntiAlias(true);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setIcon(String name) {
        this.iconName = name == null ? "" : name;
        invalidate();
    }

    public void setIcon(String name, int color) {
        this.iconName = name == null ? "" : name;
        this.iconColor = color;
        invalidate();
    }

    public void setIconColor(int color) {
        this.iconColor = color;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int defaultSize = dp(24);
        int width = resolveSize(defaultSize, widthMeasureSpec);
        int height = resolveSize(defaultSize, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (iconName.length() == 0) {
            return;
        }
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();
        int availableWidth = getWidth() - paddingLeft - getPaddingRight();
        int availableHeight = getHeight() - paddingTop - getPaddingBottom();
        if (availableWidth <= 0 || availableHeight <= 0) {
            return;
        }
        float size = Math.min(availableWidth, availableHeight);
        float scale = size / 24f;
        float left = paddingLeft + (availableWidth - size) / 2f;
        float top = paddingTop + (availableHeight - size) / 2f;

        strokePaint.setColor(iconColor);
        fillPaint.setColor(iconColor);

        canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);
        drawIcon(canvas);
        canvas.restore();
    }

    private void drawIcon(Canvas canvas) {
        if (WIFI.equals(iconName)) {
            strokePaint.setStrokeWidth(2f);
            rect.set(3f, 8f, 21f, 26f);
            canvas.drawArc(rect, 225f, 90f, false, strokePaint);
            rect.set(6f, 11f, 18f, 23f);
            canvas.drawArc(rect, 225f, 90f, false, strokePaint);
            rect.set(9f, 14f, 15f, 20f);
            canvas.drawArc(rect, 225f, 90f, false, strokePaint);
            canvas.drawCircle(12f, 18.5f, 1.6f, fillPaint);
        } else if (SEND.equals(iconName)) {
            path.reset();
            path.moveTo(3f, 4f);
            path.lineTo(21f, 12f);
            path.lineTo(3f, 20f);
            path.lineTo(7f, 12f);
            path.close();
            canvas.drawPath(path, fillPaint);
        } else if (GEAR.equals(iconName)) {
            canvas.drawCircle(12f, 12f, 6.2f, strokePaint);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4.0;
                float x1 = 12f + (float) Math.cos(angle) * 7.4f;
                float y1 = 12f + (float) Math.sin(angle) * 7.4f;
                float x2 = 12f + (float) Math.cos(angle) * 10f;
                float y2 = 12f + (float) Math.sin(angle) * 10f;
                canvas.drawLine(x1, y1, x2, y2, strokePaint);
            }
            canvas.drawCircle(12f, 12f, 2.6f, fillPaint);
        } else if (FILE.equals(iconName)) {
            path.reset();
            path.moveTo(7f, 2.5f);
            path.lineTo(14f, 2.5f);
            path.lineTo(18.5f, 7f);
            path.lineTo(18.5f, 21.5f);
            path.lineTo(5.5f, 21.5f);
            path.lineTo(5.5f, 2.5f);
            path.close();
            canvas.drawPath(path, strokePaint);
            path.reset();
            path.moveTo(14f, 2.5f);
            path.lineTo(14f, 7f);
            path.lineTo(18.5f, 7f);
            canvas.drawPath(path, strokePaint);
        } else if (IMAGE.equals(iconName)) {
            rect.set(3f, 4f, 21f, 20f);
            canvas.drawRoundRect(rect, 2.2f, 2.2f, strokePaint);
            canvas.drawCircle(8.5f, 9f, 1.8f, fillPaint);
            path.reset();
            path.moveTo(4.5f, 17.5f);
            path.lineTo(9.5f, 12f);
            path.lineTo(13.5f, 16f);
            path.lineTo(16f, 13.5f);
            path.lineTo(19.5f, 17.5f);
            canvas.drawPath(path, strokePaint);
        } else if (CLIPBOARD.equals(iconName)) {
            rect.set(5.5f, 5f, 18.5f, 21.5f);
            canvas.drawRoundRect(rect, 1.8f, 1.8f, strokePaint);
            rect.set(9f, 2.5f, 15f, 6.5f);
            canvas.drawRoundRect(rect, 1.2f, 1.2f, fillPaint);
        } else if (TEXT.equals(iconName)) {
            canvas.drawLine(4f, 6f, 20f, 6f, strokePaint);
            canvas.drawLine(4f, 10f, 20f, 10f, strokePaint);
            canvas.drawLine(4f, 14f, 16.5f, 14f, strokePaint);
            canvas.drawLine(4f, 18f, 12f, 18f, strokePaint);
        } else if (FOLDER.equals(iconName)) {
            path.reset();
            path.moveTo(2.5f, 7f);
            path.lineTo(9f, 7f);
            path.lineTo(11f, 9.5f);
            path.lineTo(21.5f, 9.5f);
            path.lineTo(21.5f, 19.5f);
            path.lineTo(2.5f, 19.5f);
            path.close();
            canvas.drawPath(path, strokePaint);
        } else if (APPS.equals(iconName)) {
            rect.set(4f, 4f, 10f, 10f);
            canvas.drawRoundRect(rect, 1.4f, 1.4f, fillPaint);
            rect.set(14f, 4f, 20f, 10f);
            canvas.drawRoundRect(rect, 1.4f, 1.4f, fillPaint);
            rect.set(4f, 14f, 10f, 20f);
            canvas.drawRoundRect(rect, 1.4f, 1.4f, fillPaint);
            rect.set(14f, 14f, 20f, 20f);
            canvas.drawRoundRect(rect, 1.4f, 1.4f, fillPaint);
        } else if (LAPTOP.equals(iconName)) {
            rect.set(4f, 4.5f, 20f, 15.5f);
            canvas.drawRoundRect(rect, 1.4f, 1.4f, strokePaint);
            Paint basePaint = strokePaint;
            basePaint.setStrokeWidth(2.4f);
            canvas.drawLine(2f, 19.5f, 22f, 19.5f, basePaint);
            basePaint.setStrokeWidth(2f);
        } else if (PHONE.equals(iconName)) {
            rect.set(7.5f, 2.5f, 16.5f, 21.5f);
            canvas.drawRoundRect(rect, 2f, 2f, strokePaint);
            canvas.drawCircle(12f, 18.3f, 1.2f, fillPaint);
        } else if (REFRESH.equals(iconName)) {
            rect.set(3f, 3f, 21f, 21f);
            canvas.drawArc(rect, 50f, 280f, false, strokePaint);
            path.reset();
            path.moveTo(19.5f, 3.5f);
            path.lineTo(21.5f, 9.5f);
            path.lineTo(15.5f, 8.5f);
            path.close();
            canvas.drawPath(path, fillPaint);
        } else if (TARGET.equals(iconName)) {
            canvas.drawCircle(12f, 12f, 8.5f, strokePaint);
            canvas.drawCircle(12f, 12f, 5f, strokePaint);
            canvas.drawCircle(12f, 12f, 1.8f, fillPaint);
        } else if (HEART.equals(iconName)) {
            path.reset();
            path.moveTo(12f, 20.5f);
            path.cubicTo(2.5f, 14f, 2.5f, 7f, 7f, 5.5f);
            path.cubicTo(9.5f, 4.7f, 11.4f, 6f, 12f, 7.2f);
            path.cubicTo(12.6f, 6f, 14.5f, 4.7f, 17f, 5.5f);
            path.cubicTo(21.5f, 7f, 21.5f, 14f, 12f, 20.5f);
            path.close();
            canvas.drawPath(path, fillPaint);
        } else if (INFO.equals(iconName)) {
            canvas.drawCircle(12f, 12f, 9.2f, strokePaint);
            canvas.drawCircle(12f, 7.2f, 1.2f, fillPaint);
            canvas.drawLine(12f, 11f, 12f, 17.2f, strokePaint);
        } else if (GLOBE.equals(iconName)) {
            canvas.drawCircle(12f, 12f, 9.2f, strokePaint);
            rect.set(7f, 2.8f, 17f, 21.2f);
            canvas.drawOval(rect, strokePaint);
            canvas.drawLine(2.8f, 12f, 21.2f, 12f, strokePaint);
        } else if (CHEVRON.equals(iconName)) {
            Paint p = strokePaint;
            p.setStrokeWidth(2.2f);
            path.reset();
            path.moveTo(9f, 4.5f);
            path.lineTo(16.5f, 12f);
            path.lineTo(9f, 19.5f);
            canvas.drawPath(path, p);
            p.setStrokeWidth(2f);
        } else if (CLOSE.equals(iconName)) {
            canvas.drawLine(6f, 6f, 18f, 18f, strokePaint);
            canvas.drawLine(18f, 6f, 6f, 18f, strokePaint);
        } else if (CHECK.equals(iconName)) {
            Paint p = strokePaint;
            p.setStrokeWidth(2.4f);
            path.reset();
            path.moveTo(4.5f, 12.5f);
            path.lineTo(10f, 18f);
            path.lineTo(19.5f, 6f);
            canvas.drawPath(path, p);
            p.setStrokeWidth(2f);
        } else if (ALERT.equals(iconName)) {
            path.reset();
            path.moveTo(12f, 3f);
            path.lineTo(22f, 20.5f);
            path.lineTo(2f, 20.5f);
            path.close();
            canvas.drawPath(path, strokePaint);
            canvas.drawLine(12f, 9f, 12f, 14.5f, strokePaint);
            canvas.drawCircle(12f, 17.4f, 1.3f, fillPaint);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
