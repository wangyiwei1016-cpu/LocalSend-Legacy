package com.legacy.localsend.ui;

import android.app.Application;

/** 应用入口：初始化设置与通知渠道。 */
public class App extends Application {

    private static volatile App instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 初始化指纹/默认别名等设置
        Prefs.get(this);
        try {
            Notifications.ensureChannel(this);
        } catch (Throwable ignored) {
            // 通知渠道创建失败不应导致启动崩溃
        }
    }

    public static App instance() {
        return instance;
    }
}
