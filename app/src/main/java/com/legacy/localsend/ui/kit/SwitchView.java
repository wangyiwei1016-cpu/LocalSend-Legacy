package com.legacy.localsend.ui.kit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.legacy.localsend.ui.Theme;

/**
 * 自绘开关：52x32dp 轨道 + 24dp 滑块 + 180ms 位移动画（用 postDelayed 实现，API 8 可用）。
 */
public class SwitchView extends View implements View.OnClickListener {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(SwitchView view, boolean checked);
    }

    private final Paint paint = new Paint();
    private final RectF rect = new RectF();

    private boolean checked;
    private float progress;
    private long animationStart;
    private boolean animating;
    private OnCheckedChangeListener listener;

    private final Runnable animation = new Runnable() {
        @Override
        public void run() {
            long elapsed = System.currentTimeMillis() - animationStart;
            float t = Math.min(1f, elapsed / 180f);
            float target = checked ? 1f : 0f;
            if (checked) {
                progress = t;
            } else {
                progress = 1f - t;
            }
            invalidate();
            if (progress != target && t < 1f) {
                postDelayed(this, 16L);
            } else {
                progress = target;
                animating = false;
                invalidate();
            }
        }
    };

    public SwitchView(Context context) {
        super(context);
        init();
    }

    public SwitchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setAntiAlias(true);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(this);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        this.listener = l;
    }

    public boolean isChecked() {
        return checked;
    }

    /** 程序化设置状态，不触发回调。 */
    public void setChecked(boolean value) {
        this.checked = value;
        this.progress = value ? 1f : 0f;
        this.animating = false;
        removeCallbacks(animation);
        invalidate();
    }

    @Override
    public void onClick(View view) {
        checked = !checked;
        animateToChecked();
        if (listener != null) {
            listener.onCheckedChanged(this, checked);
        }
    }

    private void animateToChecked() {
        animationStart = System.currentTimeMillis();
        animating = true;
        removeCallbacks(animation);
        post(animation);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(dp(52), widthMeasureSpec);
        int height = resolveSize(dp(32), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float radius = height / 2f;
        int trackColor = blend(Theme.switchTrackOff, Theme.primary, progress);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(trackColor);
        rect.set(0f, 0f, width, height);
        canvas.drawRoundRect(rect, radius, radius, paint);

        float thumbRadius = height * 0.375f;
        float minX = thumbRadius + height * 0.0625f;
        float maxX = width - minX;
        float thumbX = minX + (maxX - minX) * progress;
        paint.setColor(blend(Theme.switchThumbOff, Theme.switchThumbOn, progress));
        canvas.drawCircle(thumbX, height / 2f, thumbRadius, paint);
    }

    private static int blend(int from, int to, float ratio) {
        float r = ratio;
        if (r < 0f) {
            r = 0f;
        }
        if (r > 1f) {
            r = 1f;
        }
        int fr = (from >> 16) & 0xFF;
        int fg = (from >> 8) & 0xFF;
        int fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF;
        int tg = (to >> 8) & 0xFF;
        int tb = to & 0xFF;
        int r1 = (int) (fr + (tr - fr) * r);
        int g1 = (int) (fg + (tg - fg) * r);
        int b1 = (int) (fb + (tb - fb) * r);
        return 0xFF000000 | (r1 << 16) | (g1 << 8) | b1;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(animation);
        super.onDetachedFromWindow();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
