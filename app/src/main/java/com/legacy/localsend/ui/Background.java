package com.legacy.localsend.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 自定义背景图：选图后压缩保存在应用私有目录，背景模糊和亮度可调。
 * 模糊只在设置变化时处理一次（缩小 + box blur），绘制时直接放大，避免每帧计算。
 */
final class Background {

    static final int DEFAULT_BLUR = 30;
    static final int DEFAULT_BRIGHTNESS = 100;
    private static final int MAX_EDGE = 1600;

    private static Bitmap original;
    private static Bitmap blurred;
    private static String loadedPath = "";
    private static int blur = DEFAULT_BLUR;
    private static int brightness = DEFAULT_BRIGHTNESS;
    private static int generatedBlur = -1;

    private Background() {
    }

    static void load(Context context) {
        Prefs prefs = Prefs.get(context);
        blur = prefs.getBackgroundBlur();
        brightness = prefs.getBackgroundBrightness();
        String path = prefs.getBackgroundImage();
        if (path == null || path.length() == 0 || !new File(path).isFile()) {
            if (!loadedPath.equals("")) {
                original = null;
                blurred = null;
                loadedPath = "";
                generatedBlur = -1;
            }
            return;
        }
        if (original == null || !path.equals(loadedPath)) {
            original = decodeFile(path);
            loadedPath = path;
            blurred = null;
            generatedBlur = -1;
        }
        if (original != null && (blurred == null || generatedBlur != blur)) {
            regenerateBlur();
        }
    }

    static boolean hasImage() {
        return blurred != null;
    }

    static int getBlur() {
        return blur;
    }

    static int getBrightness() {
        return brightness;
    }

    static void setBlur(Context context, int value) {
        int safe = value < 0 ? 0 : (value > 100 ? 100 : value);
        blur = safe;
        Prefs.get(context).setBackgroundBlur(safe);
        regenerateBlur();
    }

    static void previewBrightness(int value) {
        brightness = value < 0 ? 0 : (value > 200 ? 200 : value);
    }

    static void commitBrightness(Context context) {
        Prefs.get(context).setBackgroundBrightness(brightness);
    }

    static void setImage(Context context, String path) {
        Prefs.get(context).setBackgroundImage(path);
        loadedPath = "";
        original = null;
        blurred = null;
        generatedBlur = -1;
        load(context);
    }

    static void clear(Context context) {
        Prefs.get(context).setBackgroundImage("");
        if (loadedPath != null && loadedPath.length() > 0) {
            try {
                new File(loadedPath).delete();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        loadedPath = "";
        original = null;
        blurred = null;
        generatedBlur = -1;
    }

    /** 把相册图片缩小、压缩成私有目录里的 background.jpg，返回路径。 */
    static String importImage(Context context, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new IOException("无法打开图片");
        }
        try {
            BitmapFactory.decodeStream(input, null, bounds);
        } finally {
            closeQuietly(input);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("无法识别图片格式");
        }
        int sample = 1;
        while (bounds.outWidth / sample > MAX_EDGE
                || bounds.outHeight / sample > MAX_EDGE) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        input = context.getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new IOException("无法打开图片");
        }
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        } finally {
            closeQuietly(input);
        }
        if (bitmap == null) {
            throw new IOException("图片解码失败");
        }
        File target = new File(context.getFilesDir(), "background.jpg");
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(target);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                throw new IOException("图片保存失败");
            }
        } finally {
            closeQuietly(output);
            bitmap.recycle();
        }
        return target.getAbsolutePath();
    }

    static Drawable drawable() {
        return new ImageDrawable();
    }

    /** 设置变化后，把背景重新套到所有带 page tag 的页面上。 */
    static void refreshPages(View root) {
        if (root == null) {
            return;
        }
        Object tag = root.getTag();
        if (tag instanceof String && "page".equals(tag)) {
            if (hasImage()) {
                root.setBackgroundDrawable(drawable());
            } else if (Material.enabled) {
                root.setBackgroundDrawable(Material.fog());
            } else {
                root.setBackgroundColor(Theme.bg);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                refreshPages(group.getChildAt(i));
            }
        }
    }

    static void invalidatePages(View root) {
        if (root == null) {
            return;
        }
        Object tag = root.getTag();
        if (tag instanceof String && "page".equals(tag)) {
            root.invalidate();
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                invalidatePages(group.getChildAt(i));
            }
        }
    }

    // ------------------------------------------------------------------

    private static Bitmap decodeFile(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            while (bounds.outWidth / sample > MAX_EDGE
                    || bounds.outHeight / sample > MAX_EDGE) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void regenerateBlur() {
        if (original == null) {
            blurred = null;
            generatedBlur = -1;
            return;
        }
        generatedBlur = blur;
        if (blur <= 0) {
            blurred = original;
            return;
        }
        try {
            int scale = 1 + blur / 8;
            int width = Math.max(1, original.getWidth() / scale);
            int height = Math.max(1, original.getHeight() / scale);
            Bitmap small = Bitmap.createScaledBitmap(original, width, height, true);
            if (small == original) {
                small = original.copy(Bitmap.Config.ARGB_8888, true);
            }
            boxBlur(small, 1 + blur / 14);
            blurred = small;
        } catch (Throwable t) {
            blurred = original;
        }
    }

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

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 先居中裁剪铺满，再做亮度色阵。 */
    private static final class ImageDrawable extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();
        private final ColorMatrix colorMatrix = new ColorMatrix();
        private final Rect bounds = new Rect();

        @Override
        public void draw(Canvas canvas) {
            Bitmap bitmap = blurred;
            Rect rect = getBounds();
            bounds.set(rect);
            if (bitmap == null || bitmap.isRecycled() || rect.width() <= 0 || rect.height() <= 0) {
                Paint fill = new Paint();
                fill.setColor(Theme.bg);
                canvas.drawRect(rect, fill);
                return;
            }
            float scale = Math.max(rect.width() / (float) bitmap.getWidth(),
                    rect.height() / (float) bitmap.getHeight());
            float dx = rect.left + (rect.width() - bitmap.getWidth() * scale) / 2f;
            float dy = rect.top + (rect.height() - bitmap.getHeight() * scale) / 2f;
            matrix.setScale(scale, scale);
            matrix.postTranslate(dx, dy);

            float offset = (brightness - 100) * 0.85f;
            colorMatrix.set(new float[] {
                    1f, 0f, 0f, 0f, offset,
                    0f, 1f, 0f, 0f, offset,
                    0f, 0f, 1f, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
            });
            paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
            canvas.drawBitmap(bitmap, matrix, paint);

            // 极淡的主题色压一层，保证小字在复杂照片上也能看清。
            paint.setColorFilter(null);
            paint.setColor((Theme.bg & 0x00FFFFFF) | 0x22000000);
            canvas.drawRect(rect, paint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
