package com.legacy.localsend.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * 高级材质：低饱和雾透玻璃 + 细包边 + 缓慢的按压回弹。
 *
 * 不使用 API 11+ 的 RenderScript / API 31 的 RenderEffect，全部用
 * API 1 的 Canvas 渐变、噪点纹理和半透明填充模拟“雾透”质感，因此在 Android 2.2 上也能跑。
 * 页面底层铺一层主题色的雾状渐变，玻璃控件浮在上面透出颜色。
 */
public final class Material {

    public static boolean enabled;
    private static float density = 1f;

    private static int glassFill;
    private static int glassFillPressed;
    private static int glassStroke;
    private static int glassHighlight;
    private static int fogA;
    private static int fogB;
    private static int fogC;
    private static int noiseAlpha;

    private static BitmapShader noiseShader;

    private Material() {
    }

    public static void load(Context context) {
        enabled = Prefs.get(context).isAdvancedMaterial();
        density = context.getResources().getDisplayMetrics().density;
        if (Theme.dark) {
            glassFill = 0xCC1A1F27;
            glassFillPressed = 0xE6202731;
            glassStroke = 0x4DFFFFFF;
            glassHighlight = 0x1FFFFFFF;
            fogA = withAlpha(Theme.primary, 0x26);
            fogB = withAlpha(Theme.primaryContainer, 0xB3);
            fogC = withAlpha(Theme.primary, 0x1A);
            noiseAlpha = 14;
        } else {
            glassFill = 0xD0FFFFFF;
            glassFillPressed = 0xE6FFFFFF;
            glassStroke = 0x331F2937;
            glassHighlight = 0x99FFFFFF;
            fogA = withAlpha(Theme.primary, 0x26);
            fogB = withAlpha(Theme.primaryContainer, 0xAA);
            fogC = withAlpha(Theme.primary, 0x1A);
            noiseAlpha = 12;
        }
        ensureNoise();
    }

    public static Drawable glass(int radiusDp) {
        return new GlassDrawable(radiusDp);
    }

    public static Drawable fog() {
        return new FogDrawable();
    }

    /** 只有点击区域才需要按压回弹；返回 false 不拦截正常点击。 */
    public static void attachPress(final View view) {
        if (Build.VERSION.SDK_INT < 12 || view == null) {
            return;
        }
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90L).start();
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150L).start();
                }
                return false;
            }
        });
    }

    // ------------------------------------------------------------------
    // 玻璃背景
    // ------------------------------------------------------------------

    private static final class GlassDrawable extends Drawable {

        private final float radius;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint noisePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean pressed;

        GlassDrawable(int radiusDp) {
            this.radius = dp(radiusDp);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(Math.max(1f, dp(1f)));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return;
            }
            rect.set(bounds);
            float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);

            fillPaint.setColor(pressed ? glassFillPressed : glassFill);
            canvas.drawRoundRect(rect, r, r, fillPaint);

            glossPaint.setShader(new LinearGradient(0f, rect.top, 0f,
                    rect.top + rect.height() * 0.55f,
                    glassHighlight, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, r, r, glossPaint);
            glossPaint.setShader(null);

            if (noiseShader != null) {
                noisePaint.setShader(noiseShader);
                noisePaint.setAlpha(noiseAlpha);
                canvas.drawRoundRect(rect, r, r, noisePaint);
                noisePaint.setShader(null);
            }

            borderPaint.setColor(glassStroke);
            rect.inset(dp(0.5f), dp(0.5f));
            canvas.drawRoundRect(rect, r, r, borderPaint);
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean nowPressed = false;
            for (int i = 0; i < state.length; i++) {
                if (state[i] == android.R.attr.state_pressed) {
                    nowPressed = true;
                    break;
                }
            }
            if (nowPressed != pressed) {
                pressed = nowPressed;
                invalidateSelf();
                return true;
            }
            return false;
        }

        @Override
        public void setAlpha(int alpha) {
            fillPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------
    // 页面雾底
    // ------------------------------------------------------------------

    private static final class FogDrawable extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint noisePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect rect = new Rect();
        private int cachedWidth;
        private int cachedHeight;
        private int cachedPrimary;
        private RadialGradient glare;
        private RadialGradient bloom;
        private RadialGradient floor;

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return;
            }
            rect.set(bounds);
            buildShaders(rect.width(), rect.height());

            paint.setShader(null);
            paint.setColor(Theme.bg);
            canvas.drawRect(rect, paint);

            float cx = rect.left + rect.width() * 0.16f;
            float cy = rect.top + rect.height() * 0.10f;
            paint.setShader(glare);
            canvas.drawCircle(cx, cy, rect.width() * 0.72f, paint);

            cx = rect.left + rect.width() * 0.90f;
            cy = rect.top + rect.height() * 0.30f;
            paint.setShader(bloom);
            canvas.drawCircle(cx, cy, rect.width() * 0.62f, paint);

            cx = rect.left + rect.width() * 0.40f;
            cy = rect.top + rect.height() * 0.98f;
            paint.setShader(floor);
            canvas.drawCircle(cx, cy, rect.width() * 0.78f, paint);

            paint.setShader(null);
            if (noiseShader != null) {
                noisePaint.setShader(noiseShader);
                noisePaint.setAlpha(noiseAlpha / 2);
                canvas.drawRect(rect, noisePaint);
                noisePaint.setShader(null);
            }
        }

        private void buildShaders(int width, int height) {
            if (glare != null && width == cachedWidth && height == cachedHeight
                    && Theme.primary == cachedPrimary) {
                return;
            }
            cachedWidth = width;
            cachedHeight = height;
            cachedPrimary = Theme.primary;
            float w = width;
            float h = height;
            glare = new RadialGradient(w * 0.16f, h * 0.10f, w * 0.72f,
                    fogA, 0x00FFFFFF, Shader.TileMode.CLAMP);
            bloom = new RadialGradient(w * 0.90f, h * 0.30f, w * 0.62f,
                    fogB, 0x00FFFFFF, Shader.TileMode.CLAMP);
            floor = new RadialGradient(w * 0.40f, h * 0.98f, w * 0.78f,
                    fogC, 0x00FFFFFF, Shader.TileMode.CLAMP);
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
    }

    // ------------------------------------------------------------------

    private static void ensureNoise() {
        if (noiseShader != null) {
            return;
        }
        try {
            int size = 64;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[size * size];
            Random random = new Random(20261022L);
            for (int i = 0; i < pixels.length; i++) {
                int value = 190 + random.nextInt(65);
                int alpha = random.nextInt(18);
                pixels[i] = (alpha << 24) | (value << 16) | (value << 8) | value;
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
            noiseShader = new BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        } catch (Throwable ignored) {
            noiseShader = null;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    static int dp(float value) {
        return (int) (value * density + 0.5f);
    }
}
