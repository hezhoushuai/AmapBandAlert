package com.amapbandalert;

import android.app.Notification;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取高德地图导航通知。
 *
 * <p>手机版高德没有公开导航数据接口，但导航时会在通知栏持续更新“前方xx米左转”之类的文字。
 * 本服务只读取包名为高德的通知，不读取微信等其它应用的内容。</p>
 */
public final class NavigationNotificationListener extends NotificationListenerService {

    private static volatile NavigationNotificationListener instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean connected;
    private final Runnable poll = this::refresh;

    @Override
    public void onListenerConnected() {
        instance = this;
        connected = true;
        AlertEngine.get().init(this);
        AlertLogger.log(AlertLogger.Tag.INFO, "通知监听服务已连接");
        AlertEngine.get().setListenerConnected(true);
        schedule(0);
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        AlertLogger.log(AlertLogger.Tag.INFO, "通知监听服务已断开");
        AlertEngine.get().setListenerConnected(false);
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn != null && NavigationParser.isAmapPackage(sbn.getPackageName())) {
            schedule(300);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null && NavigationParser.isAmapPackage(sbn.getPackageName())) {
            schedule(350);
        }
    }

    @Override
    public void onDestroy() {
        connected = false;
        handler.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /** 供 UI 的“立即读取”按钮使用。 */
    public static void requestRefresh() {
        NavigationNotificationListener local = instance;
        if (local == null || !local.connected) {
            AlertLogger.log(AlertLogger.Tag.ERROR, "通知监听未连接，请先授予通知使用权");
            return;
        }
        local.handler.removeCallbacks(local.poll);
        local.handler.post(local.poll);
    }

    /** 把当前所有高德通知原文写进日志，便于用户反馈解析问题。 */
    public static void dumpActive() {
        NavigationNotificationListener local = instance;
        if (local == null || !local.connected) {
            AlertLogger.log(AlertLogger.Tag.ERROR, "通知监听未连接，请先授予通知使用权");
            return;
        }
        try {
            StatusBarNotification[] active = local.getActiveNotifications();
            int count = 0;
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (!NavigationParser.isAmapPackage(sbn.getPackageName())) continue;
                    count++;
                    String[] fields = fields(sbn.getNotification());
                    AlertLogger.log(AlertLogger.Tag.BROADCAST,
                            "高德通知[" + count + "] " + sbn.getPackageName()
                                    + " ongoing=" + sbn.isOngoing()
                                    + " text=" + NavigationParser.clip(join(fields), 300));
                }
            }
            AlertLogger.log(AlertLogger.Tag.INFO, "扫描完成：高德通知 " + count + " 条");
        } catch (SecurityException | IllegalStateException e) {
            AlertLogger.log(AlertLogger.Tag.ERROR, "读取通知失败: " + e.getMessage());
        }
    }

    private void schedule(long delayMs) {
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, delayMs);
    }

    private void refresh() {
        handler.removeCallbacks(poll);
        if (!connected) return;
        if (!Prefs.get(this).enabled()) {
            handler.postDelayed(poll, 5000);
            return;
        }

        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) {
                AlertEngine.get().onListenerError("系统未返回通知列表");
                handler.postDelayed(poll, 5000);
                return;
            }

            NavigationParser.Result best = null;
            boolean bestOngoing = false;
            long bestTime = Long.MIN_VALUE;
            String unreadable = "";
            long unreadableTime = Long.MIN_VALUE;
            int amapCount = 0;

            for (StatusBarNotification sbn : active) {
                if (!NavigationParser.isAmapPackage(sbn.getPackageName())) continue;
                amapCount++;
                Notification n = sbn.getNotification();
                if (n == null) continue;
                if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;

                String[] fields = fields(n);
                String raw = join(fields);
                if (!raw.isEmpty() && sbn.getPostTime() >= unreadableTime) {
                    unreadable = raw;
                    unreadableTime = sbn.getPostTime();
                }
                NavigationParser.Result result = NavigationParser.parse(
                        sbn.getPackageName(), sbn.isOngoing(), sbn.getPostTime(), fields);
                if (result != null) {
                    // 常驻导航通知优先，避免被同应用的短时提示反复盖掉。
                    boolean better = best == null
                            || (sbn.isOngoing() && !bestOngoing)
                            || (sbn.isOngoing() == bestOngoing && sbn.getPostTime() >= bestTime);
                    if (better) {
                        best = result;
                        bestOngoing = sbn.isOngoing();
                        bestTime = sbn.getPostTime();
                    }
                }
            }

            if (best != null) {
                AlertEngine.get().onNavState(best, "高德通知");
            } else if (!unreadable.isEmpty()) {
                AlertEngine.get().onUnreadable(unreadable, "高德通知");
            } else {
                AlertEngine.get().onNoAmapNotification();
                if (amapCount > 0) {
                    AlertEngine.get().onUnreadable("高德通知存在，但没有可读取文字", "高德通知");
                }
            }
        } catch (SecurityException | IllegalStateException e) {
            AlertEngine.get().onListenerError("通知读取不可用: " + e.getMessage());
        } finally {
            long delay = AlertEngine.get().isNavActive() ? 1500 : 5000;
            handler.postDelayed(poll, delay);
        }
    }

    private static String join(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            if (field == null || field.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(field);
        }
        return sb.toString();
    }

    static String[] fields(Notification notification) {
        List<String> fields = new ArrayList<>();
        Bundle extras = notification == null ? null : notification.extras;
        if (extras != null) {
            for (String key : new String[]{
                    Notification.EXTRA_TITLE,
                    Notification.EXTRA_TEXT,
                    Notification.EXTRA_BIG_TEXT,
                    Notification.EXTRA_SUB_TEXT,
                    Notification.EXTRA_SUMMARY_TEXT}) {
                CharSequence value = extras.getCharSequence(key);
                if (value != null && value.length() > 0) fields.add(value.toString());
            }
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                for (CharSequence line : lines) {
                    if (line != null && line.length() > 0) fields.add(line.toString());
                }
            }
        }
        if (notification != null && notification.tickerText != null) {
            fields.add(notification.tickerText.toString());
        }
        return fields.toArray(new String[0]);
    }
}
