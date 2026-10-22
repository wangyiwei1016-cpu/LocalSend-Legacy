package com.legacy.localsend.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.legacy.localsend.ui.kit.BottomNav;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 高级材质：手搓的“高斯模糊”玻璃 + 细包边 + 按压回弹。
 *
 * 实现方式（全部 API 1 可用，不用 RenderScript/RenderEffect）：
 *  1. 布局稳定后把当前界面（暂时隐藏玻璃控件）画进一张 1/8 分辨率的小 Bitmap；
 *  2. 对这张小图做 3 轮 box blur，近似高斯模糊；
 *  3. 每个玻璃控件绘制时，按自己在窗口里的位置从模糊图里裁出对应区域，铺在控件底下，
 *     再叠半透明白/黑、噪点和高光，形成真实的背景模糊而不是单纯遮罩。
 * 页面底层仍保留主题色雾状渐变，玻璃透出的是“模糊后的界面 + 雾色”。
 */
public final class Material {

    public static boolean enabled;
    private static final String TAG = "LocalSendMaterial";
    private static float density = 1f;

    private static int glassFill;
    private static int glassFillPressed;
    private static int glassFillFallback;
    private static int glassFillPressedFallback;
    private static int glassStroke;
    private static int glassHighlight;
    private static int fogA;
    private static int fogB;
    private static int fogC;
    private static int noiseAlpha;

    private static BitmapShader noiseShader;

    private static final int BLUR_SCALE = 8;
    private static final int BLUR_RADIUS = 5;

    private static Bitmap blurBitmap;
    private static View blurRoot;
    private static int blurRootWidth;
    private static int blurRootHeight;

    private static final Handler BLUR_HANDLER = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> pendingActivity;

    private static final Runnable BLUR_TASK = new Runnable() {
        @Override
        public void run() {
            Activity activity = pendingActivity == null ? null : pendingActivity.get();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            captureBackdrop(activity);
        }
    };

    private Material() {
    }

    public static void load(Context context) {
        enabled = Prefs.get(context).isAdvancedMaterial();
        density = context.getResources().getDisplayMetrics().density;
        if (Theme.dark) {
            glassFill = 0x991A1F27;
            glassFillPressed = 0xB8202731;
            glassFillFallback = 0xD91A1F27;
            glassFillPressedFallback = 0xE6202731;
            glassStroke = 0x4DFFFFFF;
            glassHighlight = 0x1FFFFFFF;
            fogA = withAlpha(Theme.primary, 0x26);
            fogB = withAlpha(Theme.primaryContainer, 0xB3);
            fogC = withAlpha(Theme.primary, 0x1A);
            noiseAlpha = 14;
        } else {
            glassFill = 0x99FFFFFF;
            glassFillPressed = 0xB8FFFFFF;
            glassFillFallback = 0xE0FFFFFF;
            glassFillPressedFallback = 0xF2FFFFFF;
            glassStroke = 0x331F2937;
            glassHighlight = 0x99FFFFFF;
            fogA = withAlpha(Theme.primary, 0x26);
            fogB = withAlpha(Theme.primaryContainer, 0xAA);
            fogC = withAlpha(Theme.primary, 0x1A);
            noiseAlpha = 12;
        }
        ensureNoise();
        blurBitmap = null;
        blurRoot = null;
    }

    public static Drawable glass(int radiusDp, View host) {
        return new GlassDrawable(radiusDp, host);
    }

    public static Drawable fog() {
        return new FogDrawable();
    }

    public static boolean hasBlur() {
        return blurBitmap != null && !blurBitmap.isRecycled();
    }

    /** 主题切换/页面切换后延迟重新截屏模糊，避免频繁绘制。 */
    public static void scheduleCapture(Activity activity) {
        if (!enabled || activity == null) {
            return;
        }
        pendingActivity = new WeakReference<Activity>(activity);
        BLUR_HANDLER.removeCallbacks(BLUR_TASK);
        BLUR_HANDLER.postDelayed(BLUR_TASK, 140L);
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
    // 截屏 + 手搓模糊
    // ------------------------------------------------------------------

    private static void captureBackdrop(Activity activity) {
        if (!enabled || activity == null || activity.isFinishing()) {
            return;
        }
        final View root = activity.findViewById(android.R.id.content);
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0
                || root.getWindowToken() == null) {
            return;
        }
        List<View> hidden = new ArrayList<View>();
        List<View> glassViews = new ArrayList<View>();
        List<Drawable> glassBackgrounds = new ArrayList<Drawable>();
        Bitmap bitmap = null;
        try {
            prepareGlass(root, hidden, glassViews, glassBackgrounds);
            int width = Math.max(1, root.getWidth() / BLUR_SCALE);
            int height = Math.max(1, root.getHeight() / BLUR_SCALE);
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(width / (float) root.getWidth(), height / (float) root.getHeight());
            root.draw(canvas);
        } catch (Throwable t) {
            Log.w(TAG, "capture failed", t);
            if (bitmap != null) {
                bitmap.recycle();
            }
            bitmap = null;
        } finally {
            restoreHidden(hidden);
            restoreBackgrounds(glassViews, glassBackgrounds);
        }
        if (bitmap == null) {
            return;
        }
        boxBlur(bitmap, BLUR_RADIUS);
        Log.d(TAG, "blur ready " + bitmap.getWidth() + "x" + bitmap.getHeight());
        blurBitmap = bitmap;
        blurRoot = root;
        blurRootWidth = root.getWidth();
        blurRootHeight = root.getHeight();
        invalidateGlass(root);
    }

    /**
     * 截屏时：底栏整体隐藏；其他玻璃控件只摘掉背景、保留图标/文字，
     * 这样模糊图里会有控件自身的淡色重影，玻璃上能明显看到“模糊”而不是纯遮罩。
     */
    private static void prepareGlass(View view, List<View> hidden,
                                     List<View> glassViews, List<Drawable> glassBackgrounds) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof BottomNav) {
            hidden.add(view);
            view.setVisibility(View.INVISIBLE);
            return;
        }
        Object tag = view.getTag();
        if (tag instanceof String && isGlassTag((String) tag)) {
            if ("glass-icon".equals(tag)) {
                // 小图标按钮如果把自己的图标也糊进背景，会像脏污；直接隐藏，只保留底层雾色。
                hidden.add(view);
                view.setVisibility(View.INVISIBLE);
            } else {
                glassViews.add(view);
                glassBackgrounds.add(view.getBackground());
                view.setBackgroundDrawable(null);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                prepareGlass(group.getChildAt(i), hidden, glassViews, glassBackgrounds);
            }
        }
    }

    private static boolean isGlassTag(String tag) {
        return "tile".equals(tag) || "pill".equals(tag) || "glass-icon".equals(tag);
    }

    private static void restoreHidden(List<View> hidden) {
        for (int i = 0; i < hidden.size(); i++) {
            hidden.get(i).setVisibility(View.VISIBLE);
        }
    }

    private static void restoreBackgrounds(List<View> views, List<Drawable> backgrounds) {
        for (int i = 0; i < views.size(); i++) {
            views.get(i).setBackgroundDrawable(backgrounds.get(i));
        }
    }

    private static void invalidateGlass(View view) {
        if (view instanceof BottomNav) {
            view.invalidate();
            return;
        }
        Object tag = view.getTag();
        if (tag instanceof String && isGlassTag((String) tag)) {
            view.invalidate();
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                invalidateGlass(group.getChildAt(i));
            }
        }
    }

    /** 3 轮横向+纵向 box blur，近似高斯。 */
    private static void boxBlur(Bitmap bitmap, int radius) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 2 || height <= 2 || radius <= 0) {
            return;
        }
        int[] pixels = new int[width * height];
        int[] temp = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int pass = 0; pass < 3; pass++) {
            blurHorizontal(pixels, temp, width, height, radius);
            blurVertical(temp, pixels, width, height, radius);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private static void blurHorizontal(int[] src, int[] dst, int width, int height, int radius) {
        int div = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int sumR = 0;
            int sumG = 0;
            int sumB = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = src[row + clamp(i, 0, width - 1)];
                sumR += (color >> 16) & 0xFF;
                sumG += (color >> 8) & 0xFF;
                sumB += color & 0xFF;
            }
            for (int x = 0; x < width; x++) {
                dst[row + x] = 0xFF000000
                        | ((sumR / div) << 16)
                        | ((sumG / div) << 8)
                        | (sumB / div);
                int out = src[row + clamp(x - radius, 0, width - 1)];
                int in = src[row + clamp(x + radius + 1, 0, width - 1)];
                sumR += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sumG += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sumB += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static void blurVertical(int[] src, int[] dst, int width, int height, int radius) {
        int div = radius * 2 + 1;
        for (int x = 0; x < width; x++) {
            int sumR = 0;
            int sumG = 0;
            int sumB = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = src[clamp(i, 0, height - 1) * width + x];
                sumR += (color >> 16) & 0xFF;
                sumG += (color >> 8) & 0xFF;
                sumB += color & 0xFF;
            }
            for (int y = 0; y < height; y++) {
                dst[y * width + x] = 0xFF000000
                        | ((sumR / div) << 16)
                        | ((sumG / div) << 8)
                        | (sumB / div);
                int out = src[clamp(y - radius, 0, height - 1) * width + x];
                int in = src[clamp(y + radius + 1, 0, height - 1) * width + x];
                sumR += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sumG += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sumB += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    // ------------------------------------------------------------------
    // 玻璃背景
    // ------------------------------------------------------------------

    private static final class GlassDrawable extends Drawable {

        private final float radius;
        private final View host;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint noisePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Rect srcRect = new Rect();
        private boolean pressed;

        GlassDrawable(int radiusDp, View host) {
            this.radius = dp(radiusDp);
            this.host = host;
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

            boolean blurred = drawBlurredBackdrop(canvas, r);
            int fill = blurred
                    ? (pressed ? glassFillPressed : glassFill)
                    : (pressed ? glassFillPressedFallback : glassFillFallback);
            fillPaint.setColor(fill);
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

        /** 从整屏模糊图里裁出控件所在区域，画在控件底下。 */
        private boolean drawBlurredBackdrop(Canvas canvas, float r) {
            Bitmap bitmap = blurBitmap;
            View root = blurRoot;
            if (bitmap == null || root == null || host == null
                    || host.getWidth() <= 0 || host.getHeight() <= 0
                    || blurRootWidth <= 0 || blurRootHeight <= 0) {
                return false;
            }
            try {
                int[] hostLocation = new int[2];
                int[] rootLocation = new int[2];
                host.getLocationInWindow(hostLocation);
                root.getLocationInWindow(rootLocation);
                float scaleX = bitmap.getWidth() / (float) blurRootWidth;
                float scaleY = bitmap.getHeight() / (float) blurRootHeight;
                int left = Math.round((hostLocation[0] - rootLocation[0]) * scaleX);
                int top = Math.round((hostLocation[1] - rootLocation[1]) * scaleY);
                int right = Math.round((hostLocation[0] - rootLocation[0] + host.getWidth()) * scaleX);
                int bottom = Math.round((hostLocation[1] - rootLocation[1] + host.getHeight()) * scaleY);
                srcRect.set(left, top, right, bottom);
                if (!srcRect.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) {
                    return false;
                }
                // 先铺模糊图，再用 DST_IN 圆角蒙版裁掉四角。
                // 比 clipPath 在 API 17 的硬件加速上更稳。
                int layer = canvas.saveLayer(rect, null, Canvas.ALL_SAVE_FLAG);
                canvas.drawBitmap(bitmap, srcRect, rect, blurPaint);
                maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
                maskPaint.setColor(0xFF000000);
                canvas.drawRoundRect(rect, r, r, maskPaint);
                maskPaint.setXfermode(null);
                canvas.restoreToCount(layer);
                return true;
            } catch (Throwable t) {
                return false;
            }
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
