package com.legacy.localsend.ui;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.legacy.localsend.R;

/**
 * 通知构造。兼容 API 8：
 *  - API 11+ 用 Notification.Builder
 *  - API 8~10 用已废弃的 Notification 构造器，并反射调用 setLatestEventInfo 填标题/正文
 *  - API 26+ 必须先创建通知渠道并用带 channelId 的 Builder
 */
final class Notifications {

    static final int ID_FOREGROUND = 1;
    static final int ID_EVENT = 2;

    private static final String CHANNEL_ID = "localsend_service";

    private Notifications() {
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            Api26.ensureChannel(context);
        }
    }

    static Notification build(Context context, String title, String text, boolean ongoing) {
        PendingIntent contentIntent = contentIntent(context);
        if (Build.VERSION.SDK_INT >= 26) {
            return Api26.build(context, title, text, ongoing, contentIntent);
        }
        if (Build.VERSION.SDK_INT >= 11) {
            return Api11.build(context, title, text, ongoing, contentIntent);
        }
        return legacy(context, title, text, ongoing, contentIntent);
    }

    static void notifyEvent(Context context, String title, String text) {
        if (context == null) {
            return;
        }
        try {
            ensureChannel(context);
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(ID_EVENT, build(context, title, text, false));
            }
        } catch (Throwable ignored) {
            // 通知失败不影响主流程
        }
    }

    static void cancelEvent(Context context) {
        if (context == null) {
            return;
        }
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(ID_EVENT);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    static PendingIntent contentIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, 0, intent, flags);
    }

    /** API 8~10 的老式通知。setLatestEventInfo 在新 SDK 中已删除，只能反射调用。 */
    private static Notification legacy(Context context, String title, String text, boolean ongoing, PendingIntent contentIntent) {
        Notification notification = new Notification(R.drawable.ic_launcher, text, System.currentTimeMillis());
        notification.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
        if (ongoing) {
            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        } else {
            notification.flags |= Notification.FLAG_AUTO_CANCEL;
        }
        try {
            java.lang.reflect.Method method = Notification.class.getMethod(
                    "setLatestEventInfo",
                    new Class[] { Context.class, CharSequence.class, CharSequence.class, PendingIntent.class });
            method.invoke(notification, new Object[] { context, title, text, contentIntent });
        } catch (Throwable ignored) {
            // 老机器上若没有该方法，通知只能显示 ticker，可接受
        }
        return notification;
    }

    @TargetApi(11)
    private static final class Api11 {

        private Api11() {
        }

        static Notification build(Context context, String title, String text, boolean ongoing, PendingIntent contentIntent) {
            Notification.Builder builder = new Notification.Builder(context);
            builder.setSmallIcon(Build.VERSION.SDK_INT >= 21 ? R.drawable.ic_stat : R.drawable.ic_launcher);
            builder.setTicker(text);
            builder.setContentTitle(title);
            builder.setContentText(text);
            builder.setContentIntent(contentIntent);
            builder.setOngoing(ongoing);
            builder.setOnlyAlertOnce(true);
            builder.setAutoCancel(!ongoing);
            return builder.getNotification();
        }
    }

    @TargetApi(26)
    private static final class Api26 {

        private Api26() {
        }

        static void ensureChannel(Context context) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(context.getString(R.string.notif_channel_desc));
            manager.createNotificationChannel(channel);
        }

        static Notification build(Context context, String title, String text, boolean ongoing, PendingIntent contentIntent) {
            Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID);
            builder.setSmallIcon(R.drawable.ic_stat);
            builder.setTicker(text);
            builder.setContentTitle(title);
            builder.setContentText(text);
            builder.setContentIntent(contentIntent);
            builder.setOngoing(ongoing);
            builder.setOnlyAlertOnce(true);
            builder.setAutoCancel(!ongoing);
            return builder.getNotification();
        }
    }
}
