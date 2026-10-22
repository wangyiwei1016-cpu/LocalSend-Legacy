package com.legacy.localsend.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import com.legacy.localsend.core.FileMeta;

import java.security.SecureRandom;

/**
 * 应用设置：别名、端口、指纹、自动接收开关、接收目录展示。
 *
 * 注意：minSdk=8，SharedPreferences.Editor.apply() 是 API 9 才有的，必须用 commit()。
 */
@SuppressLint("ApplySharedPref")
public final class Prefs {

    private static final String FILE_NAME = "localsend_settings";

    private static final String KEY_ALIAS = "alias";
    private static final String KEY_PORT = "port";
    private static final String KEY_FINGERPRINT = "fingerprint";
    private static final String KEY_AUTO_ACCEPT = "autoAccept";
    private static final String KEY_DARK_MODE = "darkMode";
    private static final String KEY_THEME_COLOR = "themeColor";
    private static final String KEY_SHOW_SYSTEM_APPS = "showSystemApps";

    public static final int DEFAULT_PORT = 53317;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    private static volatile Prefs instance;

    private final Context app;
    private final SharedPreferences preferences;
    private volatile String receiveDirCache;

    private Prefs(Context context) {
        this.app = context.getApplicationContext();
        this.preferences = app.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public static Prefs get(Context context) {
        Prefs local = instance;
        if (local == null) {
            synchronized (Prefs.class) {
                local = instance;
                if (local == null) {
                    local = new Prefs(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    // ---------- 别名 ----------

    public String getAlias() {
        String alias = preferences.getString(KEY_ALIAS, "");
        if (alias == null || alias.trim().length() == 0) {
            return defaultAlias();
        }
        return alias.trim();
    }

    public void setAlias(String alias) {
        String value = alias == null ? "" : alias.trim();
        if (value.length() == 0) {
            value = defaultAlias();
        }
        preferences.edit().putString(KEY_ALIAS, value).commit();
    }

    public static String defaultAlias() {
        String model = Build.MODEL;
        if (model == null || model.trim().length() == 0) {
            return "Android 老机";
        }
        return model.trim();
    }

    public String getDeviceModel() {
        String model = Build.MODEL;
        return model == null ? "" : model;
    }

    // ---------- 端口 ----------

    public int getPort() {
        int port = preferences.getInt(KEY_PORT, DEFAULT_PORT);
        if (port < MIN_PORT || port > MAX_PORT) {
            return DEFAULT_PORT;
        }
        return port;
    }

    public void setPort(int port) {
        int value = port;
        if (value < MIN_PORT || value > MAX_PORT) {
            value = DEFAULT_PORT;
        }
        preferences.edit().putInt(KEY_PORT, value).commit();
    }

    // ---------- 指纹 ----------

    public String getFingerprint() {
        String fingerprint = preferences.getString(KEY_FINGERPRINT, "");
        if (fingerprint == null || fingerprint.length() != 32) {
            fingerprint = generateFingerprint();
            preferences.edit().putString(KEY_FINGERPRINT, fingerprint).commit();
        }
        return fingerprint;
    }

    private static String generateFingerprint() {
        byte[] bytes = new byte[16];
        try {
            SecureRandom random = new SecureRandom();
            random.nextBytes(bytes);
        } catch (Throwable ignored) {
            long seed = System.currentTimeMillis() ^ (Runtime.getRuntime().freeMemory() << 7);
            java.util.Random fallback = new java.util.Random(seed);
            fallback.nextBytes(bytes);
        }
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            if (value < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(value));
        }
        return sb.toString();
    }

    // ---------- 自动接收 ----------

    public boolean isAutoAccept() {
        return preferences.getBoolean(KEY_AUTO_ACCEPT, false);
    }

    public void setAutoAccept(boolean autoAccept) {
        preferences.edit().putBoolean(KEY_AUTO_ACCEPT, autoAccept).commit();
    }

    // ---------- 深色模式 ----------

    public boolean isDarkMode() {
        return preferences.getBoolean(KEY_DARK_MODE, false);
    }

    public void setDarkMode(boolean darkMode) {
        preferences.edit().putBoolean(KEY_DARK_MODE, darkMode).commit();
    }

    // ---------- 主题色（莫奈低饱和色板） ----------

    public int getThemeColor() {
        int index = preferences.getInt(KEY_THEME_COLOR, Theme.PALETTE_MIST_BLUE);
        if (index < 0 || index >= Theme.PALETTE_COUNT) {
            return Theme.PALETTE_MIST_BLUE;
        }
        return index;
    }

    public void setThemeColor(int index) {
        int value = index;
        if (value < 0 || value >= Theme.PALETTE_COUNT) {
            value = Theme.PALETTE_MIST_BLUE;
        }
        preferences.edit().putInt(KEY_THEME_COLOR, value).commit();
    }

    // ---------- 应用列表：是否显示系统应用 ----------

    public boolean isShowSystemApps() {
        return preferences.getBoolean(KEY_SHOW_SYSTEM_APPS, false);
    }

    public void setShowSystemApps(boolean show) {
        preferences.edit().putBoolean(KEY_SHOW_SYSTEM_APPS, show).commit();
    }

    // ---------- 接收目录 ----------

    /** 供设置页/主界面展示的接收目录。 */
    public String getReceiveDirectory() {
        String cached = receiveDirCache;
        if (cached != null) {
            return cached;
        }
        String dir;
        try {
            dir = new AndroidStorage(app).describeDirectory();
        } catch (Throwable t) {
            dir = "Download/" + "LocalSendLegacy";
        }
        receiveDirCache = dir;
        return dir;
    }

    /** 接收目录可能因为权限/存储变化而改变，重新计算。 */
    public String refreshReceiveDirectory() {
        receiveDirCache = null;
        return getReceiveDirectory();
    }

    /** 把不可信文件名清洗成安全文件名（统一走 core 的实现）。 */
    public static String safeFileName(String name) {
        return FileMeta.sanitizeFileName(name);
    }
}
