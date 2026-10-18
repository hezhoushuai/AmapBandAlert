package com.amapbandalert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.Locale;

/**
 * 通知出口。
 *
 * <p>P0 链路：高优先级通知 -> 小米运动健康“应用通知”转发 -> 手环 9 Pro 震动/显示。
 * 每次提醒使用不同 notificationId，避免部分穿戴端把同 id 更新当成静默刷新。</p>
 */
public final class AlertNotifier {

    public static final String CHANNEL_ALERT = "nav_alerts";
    public static final String CHANNEL_STATUS = "nav_status";
    private static final int STATUS_ID = 1;
    private static int seq = 2000;

    private AlertNotifier() {}

    public static void ensureChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel alert = new NotificationChannel(
                CHANNEL_ALERT, "导航场景提醒", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("转向、到达、电子眼、超速等提醒，转发到手环震动");
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 400, 150, 400, 150, 700});
        alert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alert.setShowBadge(true);
        nm.createNotificationChannel(alert);

        NotificationChannel status = new NotificationChannel(
                CHANNEL_STATUS, "监听状态", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("前台服务状态，不震动");
        status.setShowBadge(false);
        nm.createNotificationChannel(status);
    }

    public static Notification buildStatus(Context context, String title, String text) {
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(context, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(context, MonitorService.class).setAction(MonitorService.ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(context, 11, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_nav)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_stat_nav),
                        "停止监听", stopPi).build());
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    public static void notifyTest(Context context) {
        post(context, "✅ 手环链路测试", "看到这条通知并震动，说明转发链路已通", "test");
    }

    public static void notifyTurn(Context context, NavigationParser.Result r) {
        post(context, turnTitle(r), turnText(r), "turn");
    }

    public static void notifyNear(Context context, NavigationParser.Result r) {
        post(context, "即将" + NavigationParser.maneuverLabel(r.maneuver),
                r.road.isEmpty() ? "请准备转向" : "进入 " + r.road, "near");
    }

    public static void notifyTurnUnknown(Context context, NavigationParser.Result r) {
        post(context, NavigationParser.maneuverLabel(r.maneuver),
                r.road.isEmpty() ? "请查看导航" : "进入 " + r.road, "turn");
    }

    public static void notifyStart(Context context) {
        post(context, "导航开始", "高德导航已开始，手环提醒已就绪", "start");
    }

    public static void notifyArrive(Context context) {
        post(context, "已到达目的地", "本次导航结束", "arrive");
    }

    public static void notifyCamera(Context context, int distM, int limit, int speed) {
        StringBuilder text = new StringBuilder();
        text.append(formatDistance(distM));
        if (limit > 0) text.append(" · 限速").append(limit);
        if (speed > 0) text.append(" · 当前").append(speed);
        post(context, "⚠️ 前方电子眼", text.toString(), "camera");
    }

    public static void notifyOverspeed(Context context, int speed, int limit) {
        post(context, "🚨 超速 " + speed + "/" + limit,
                "已持续超过限速，请减速", "overspeed");
    }

    public static void notifyKeyword(Context context, String keyword, String snippet) {
        String title;
        if (keyword.contains("拍照") || keyword.contains("测速") || keyword.contains("电子眼")
                || keyword.contains("违章")) {
            title = "⚠️ 导航提示：" + keyword;
        } else if (keyword.contains("拥堵") || keyword.contains("事故")
                || keyword.contains("施工") || keyword.contains("封路")) {
            title = "⚠️ 前方" + keyword;
        } else {
            title = "⚠️ 导航提示：" + keyword;
        }
        post(context, title, snippet, "keyword");
    }

    private static void post(Context context, String title, String text, String kind) {
        AlertLogger.log(AlertLogger.Tag.ALERT, "发通知[" + kind + "]: " + title + " · " + text);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        int id;
        synchronized (AlertNotifier.class) {
            id = ++seq;
        }
        Notification.Builder b = new Notification.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_stat_nav)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setVibrate(new long[]{0, 400, 150, 400, 150, 700});
        try {
            nm.notify(id, b.build());
        } catch (SecurityException e) {
            AlertLogger.log(AlertLogger.Tag.ERROR, "通知权限不足: " + e.getMessage());
        }
    }

    private static String turnTitle(NavigationParser.Result r) {
        String label = NavigationParser.maneuverLabel(r.maneuver);
        if (r.distanceM > 0) return label + " " + formatDistance(r.distanceM);
        return label;
    }

    private static String turnText(NavigationParser.Result r) {
        StringBuilder sb = new StringBuilder();
        if (r.road.isEmpty()) {
            sb.append("高德导航转向提醒");
        } else {
            sb.append("进入 ").append(r.road);
        }
        if (r.speed > 0) {
            sb.append(" · 当前").append(r.speed);
        }
        if (r.limit > 0) {
            sb.append(" · 限速").append(r.limit);
        }
        return sb.toString();
    }

    public static String formatDistance(int meters) {
        if (meters < 0) return "距离未知";
        if (meters >= 1000) {
            return String.format(Locale.CHINA, "%.1f公里", meters / 1000.0);
        }
        return meters + "米";
    }

    public static void postStatus(Context context, String title, String text) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.notify(STATUS_ID, buildStatus(context, title, text));
        } catch (SecurityException e) {
            AlertLogger.log(AlertLogger.Tag.ERROR, "状态通知失败: " + e.getMessage());
        }
    }
}
