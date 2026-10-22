package com.legacy.localsend.ui;

import android.content.Context;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 运行期明暗主题。
 *
 * minSdk=8，不能用 AppCompat 的夜间模式，也不能依赖 Material 主题；因此在 Activity 创建前
 * 读取 Prefs 里的开关，用代码把颜色应用到布局（背景按 tag 识别，文字按原先的浅色值映射）。
 * 全部使用 API 1 就存在的 Drawable / View API。
 */
public final class Theme {

    public static boolean dark;

    public static int bg;
    public static int surface;
    public static int surfacePressed;
    public static int text;
    public static int textSecondary;
    public static int textTertiary;
    public static int primary;
    public static int primaryContainer;
    public static int stroke;
    public static int danger;
    public static int switchTrackOff;
    public static int switchThumbOff;
    public static int switchThumbOn;

    private static float density = 1f;

    static {
        applyLight();
    }

    private Theme() {
    }

    public static void load(Context context) {
        dark = Prefs.get(context).isDarkMode();
        density = context.getResources().getDisplayMetrics().density;
        if (dark) {
            applyDark();
        } else {
            applyLight();
        }
    }

    private static void applyLight() {
        bg = 0xFFFFFFFF;
        surface = 0xFFFFFFFF;
        surfacePressed = 0xFFF3F4F6;
        text = 0xFF111827;
        textSecondary = 0xFF6B7280;
        textTertiary = 0xFF9CA3AF;
        primary = 0xFF2563EB;
        primaryContainer = 0xFFE8EAFF;
        stroke = 0xFFE5E7EB;
        danger = 0xFFDC2626;
        switchTrackOff = 0xFFE8EAFF;
        switchThumbOff = 0xFF6B7280;
        switchThumbOn = 0xFFFFFFFF;
    }

    private static void applyDark() {
        bg = 0xFF101318;
        surface = 0xFF191D24;
        surfacePressed = 0xFF232833;
        text = 0xFFE8EAED;
        textSecondary = 0xFFA6ADB8;
        textTertiary = 0xFF7D8592;
        primary = 0xFF8AB4F8;
        primaryContainer = 0xFF1E3355;
        stroke = 0xFF2D333D;
        danger = 0xFFF28B82;
        switchTrackOff = 0xFF3A414D;
        switchThumbOff = 0xFF9AA3B0;
        switchThumbOn = 0xFF101318;
    }

    // ------------------------------------------------------------------
    // 应用到视图树
    // ------------------------------------------------------------------

    public static void applyTo(View root) {
        if (root == null) {
            return;
        }
        applyOne(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTo(group.getChildAt(i));
            }
        }
    }

    private static void applyOne(View view) {
        Object tag = view.getTag();
        if (tag instanceof String) {
            String key = (String) tag;
            if ("page".equals(key)) {
                view.setBackgroundColor(bg);
            } else if ("card".equals(key)) {
                view.setBackgroundDrawable(strokedRoundRect(surface, stroke, 20));
            } else if ("tile".equals(key)) {
                view.setBackgroundDrawable(pressable(surface, surfacePressed, stroke, 20));
            } else if ("pill".equals(key)) {
                view.setBackgroundDrawable(pressable(surface, surfacePressed, stroke, 24));
            } else if ("selection".equals(key)) {
                view.setBackgroundDrawable(roundRect(primaryContainer, 12));
            } else if ("chip".equals(key)) {
                view.setBackgroundDrawable(roundRect(primaryContainer, 8));
            } else if ("divider".equals(key)) {
                view.setBackgroundColor(stroke);
            }
        }
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            int current = textView.getCurrentTextColor();
            int mapped = mapTextColor(current);
            if (mapped != current) {
                textView.setTextColor(mapped);
            }
            textView.setHintTextColor(textSecondary);
        }
    }

    /** 布局里写的是浅色主题的固定颜色，按值映射到当前主题。 */
    private static int mapTextColor(int color) {
        if (color == 0xFF111827) {
            return text;
        }
        if (color == 0xFF6B7280) {
            return textSecondary;
        }
        if (color == 0xFF9CA3AF) {
            return textTertiary;
        }
        if (color == 0xFF2563EB) {
            return primary;
        }
        if (color == 0xFFDC2626) {
            return danger;
        }
        return color;
    }

    // ------------------------------------------------------------------
    // 供自绘控件 / 对话框使用的 Drawable
    // ------------------------------------------------------------------

    public static Drawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    public static Drawable strokedRoundRect(int fill, int strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setStroke(Math.max(1, dp(1)), strokeColor);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    public static Drawable pressable(int normal, int pressed, int strokeColor, int radiusDp) {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[] { android.R.attr.state_pressed },
                strokedRoundRect(pressed, primary, radiusDp));
        drawable.addState(new int[] {}, strokedRoundRect(normal, strokeColor, radiusDp));
        return drawable;
    }

    /** 应用列表 / 设备行：无边框，按下时浅色底。 */
    public static Drawable rowPressable() {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[] { android.R.attr.state_pressed },
                roundRect(primaryContainer, 12));
        drawable.addState(new int[] {}, roundRect(0x00000000, 12));
        return drawable;
    }

    public static Drawable inputDrawable() {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[] { android.R.attr.state_focused },
                strokedRoundRect(surface, primary, 12));
        drawable.addState(new int[] {}, strokedRoundRect(surface, stroke, 12));
        return drawable;
    }

    public static Drawable dialogButtonDrawable() {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[] { android.R.attr.state_pressed },
                roundRect(primaryContainer, 20));
        drawable.addState(new int[] {}, roundRect(0x00000000, 20));
        return drawable;
    }

    public static Drawable progressDrawable() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(primaryContainer);
        background.setCornerRadius(dp(4));

        GradientDrawable progress = new GradientDrawable();
        progress.setShape(GradientDrawable.RECTANGLE);
        progress.setColor(primary);
        progress.setCornerRadius(dp(4));
        ClipDrawable clip = new ClipDrawable(progress, Gravity.LEFT, ClipDrawable.HORIZONTAL);

        LayerDrawable layer = new LayerDrawable(new Drawable[] { background, clip });
        layer.setId(0, android.R.id.background);
        layer.setId(1, android.R.id.progress);
        return layer;
    }

    private static int dp(int value) {
        return (int) (value * density + 0.5f);
    }
}
