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

    /** 莫奈色系：低饱和、偏灰的强调色；数值与 Prefs 里保存的序号一致。 */
    public static final int PALETTE_MIST_BLUE = 0;
    public static final int PALETTE_SAGE = 1;
    public static final int PALETTE_ROSE = 2;
    public static final int PALETTE_LAVENDER = 3;
    public static final int PALETTE_TERRACOTTA = 4;
    public static final int PALETTE_TEAL = 5;
    public static final int PALETTE_COUNT = 6;

    private static final int[] LIGHT_PRIMARY = {
            0xFF5F7A8F, // 雾蓝
            0xFF667C63, // 鼠尾草
            0xFF9C6B75, // 藕粉
            0xFF7E7099, // 薰衣草
            0xFF9A6B52, // 陶土
            0xFF557F7C  // 青灰
    };
    private static final int[] LIGHT_CONTAINER = {
            0xFFE7EEF3,
            0xFFE8EFE6,
            0xFFF5E8EA,
            0xFFEEEAF5,
            0xFFF5EAE3,
            0xFFE3EFEE
    };
    private static final int[] DARK_PRIMARY = {
            0xFFA9C0D2,
            0xFFAFC6AA,
            0xFFD6AAB2,
            0xFFBCB0D6,
            0xFFD3AB93,
            0xFF9CC4C0
    };
    private static final int[] DARK_CONTAINER = {
            0xFF27343E,
            0xFF2B3729,
            0xFF3A2B2F,
            0xFF302B3D,
            0xFF3A2E26,
            0xFF243634
    };

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

    private static int palette = PALETTE_MIST_BLUE;
    private static float density = 1f;

    static {
        applyLight();
    }

    private Theme() {
    }

    public static void load(Context context) {
        Prefs prefs = Prefs.get(context);
        dark = prefs.isDarkMode();
        palette = prefs.getThemeColor();
        density = context.getResources().getDisplayMetrics().density;
        if (dark) {
            applyDark();
        } else {
            applyLight();
        }
        applyPalette();
        Material.load(context);
        Background.load(context);
    }

    public static int getPalette() {
        return palette;
    }

    /** 指定色板在当前明暗模式下的强调色，用于设置页预览。 */
    public static int primaryFor(int index) {
        int safe = index < 0 || index >= PALETTE_COUNT ? PALETTE_MIST_BLUE : index;
        return dark ? DARK_PRIMARY[safe] : LIGHT_PRIMARY[safe];
    }

    /** 指定色板在当前明暗模式下的浅色容器，用于设置页预览。 */
    public static int containerFor(int index) {
        int safe = index < 0 || index >= PALETTE_COUNT ? PALETTE_MIST_BLUE : index;
        return dark ? DARK_CONTAINER[safe] : LIGHT_CONTAINER[safe];
    }

    private static void applyPalette() {
        int index = palette < 0 || palette >= PALETTE_COUNT ? PALETTE_MIST_BLUE : palette;
        primary = dark ? DARK_PRIMARY[index] : LIGHT_PRIMARY[index];
        primaryContainer = dark ? DARK_CONTAINER[index] : LIGHT_CONTAINER[index];
        switchTrackOff = dark ? 0xFF3A414D : primaryContainer;
    }

    private static void applyLight() {
        bg = 0xFFFFFFFF;
        surface = 0xFFFFFFFF;
        surfacePressed = 0xFFF3F4F6;
        text = 0xFF111827;
        textSecondary = 0xFF6B7280;
        textTertiary = 0xFF9CA3AF;
        primary = LIGHT_PRIMARY[PALETTE_MIST_BLUE];
        primaryContainer = LIGHT_CONTAINER[PALETTE_MIST_BLUE];
        stroke = 0xFFE5E7EB;
        danger = 0xFFDC2626;
        switchTrackOff = primaryContainer;
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
        primary = DARK_PRIMARY[PALETTE_MIST_BLUE];
        primaryContainer = DARK_CONTAINER[PALETTE_MIST_BLUE];
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
                if (Background.hasImage()) {
                    view.setBackgroundDrawable(Background.drawable());
                } else if (Material.enabled) {
                    view.setBackgroundDrawable(Material.fog());
                } else {
                    view.setBackgroundColor(bg);
                }
            } else if ("card".equals(key)) {
                view.setBackgroundDrawable(strokedRoundRect(surface, stroke, 20));
            } else if ("tile".equals(key)) {
                if (Material.enabled) {
                    view.setBackgroundDrawable(Material.glass(20));
                    Material.attachPress(view);
                } else {
                    view.setBackgroundDrawable(pressable(surface, surfacePressed, stroke, 20));
                }
            } else if ("pill".equals(key)) {
                if (Material.enabled) {
                    view.setBackgroundDrawable(Material.glass(24));
                    Material.attachPress(view);
                } else {
                    view.setBackgroundDrawable(pressable(surface, surfacePressed, stroke, 24));
                }
            } else if ("glass-icon".equals(key)) {
                if (Material.enabled) {
                    view.setBackgroundDrawable(Material.glass(20));
                    Material.attachPress(view);
                } else {
                    view.setBackgroundColor(0x00000000);
                }
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
