package com.amapbandalert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * 通知触发器。
 * P0链路: 高优先级通知 → 小米运动健康转发 → 手环9Pro震动(系统级)
 * 要点:
 *  - CHANNEL_IMPORTANCE_HIGH: 横幅+声音+手环震动
 *  - CATEGORY_ALARM: 穿透勿扰(如手环侧允许)
 *  - 每次预警用不同notificationId, 避免被小米运动健康去重吞掉
 */
public final class AlertNotifier {

    private static final String CHANNEL_ID = "moto_alerts";
    private static final String CHANNEL_ID_SILENT = "moto_status";
    private static int seq = 1000;

    private AlertNotifier() {}

    public static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel alert = new NotificationChannel(CHANNEL_ID, "骑行预警",
                NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("限速拍照/超速预警, 同步到手环震动");
        alert.enableVibration(true);
        alert.setBypassDnd(false);
        nm.createNotificationChannel(alert);

        NotificationChannel status = new NotificationChannel(CHANNEL_ID_SILENT, "服务状态",
                NotificationManager.IMPORTANCE_MIN);
        nm.createNotificationChannel(status);
    }

    /** 电子眼预警通知 → 手环震动 */
    public static void notifyCamera(Context ctx, int distM, int limitSpeed, int curSpeed) {
        String limit = limitSpeed > 0 ? ("限速" + limitSpeed) : "未知限速";
        String text = distM + "m · " + limit + (curSpeed > 0 ? (" · 当前" + curSpeed) : "");
        post(ctx, "⚠️限速拍照", text, true);
    }

    /** 超速提醒 */
    public static void notifyOverspeed(Context ctx, int curSpeed, int limitSpeed) {
        post(ctx, "🚨超速提醒", "当前" + curSpeed + " · 限速" + limitSpeed, true);
    }

    /** 测试通知 */
    public static void notifyTest(Context ctx) {
        post(ctx, "✅AmapBandAlert测试", "如果你在手环上看到这条并震动, 链路OK", true);
    }

    private static void post(Context ctx, String title, String text, boolean alert) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        Notification.Builder b = new Notification.Builder(ctx, alert ? CHANNEL_ID : CHANNEL_ID_SILENT)
                .setSmallIcon(android.R.drawable.stat_notify_error) // TODO 换自定义图标
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true);
        if (alert) {
            b.setPriority(Notification.PRIORITY_MAX);
            if (Build.VERSION.SDK_INT >= 21) b.setVibrate(new long[]{0, 300, 150, 300, 150, 300});
        }
        // 每次不同id: 防止小米运动健康对同id通知去重导致手环不震
        nm.notify(alert ? (++seq) : 1, b.build());
    }
}
