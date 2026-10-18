package com.amapbandalert;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** 前台服务：保持进程存活，并持续获取 GPS 车速。 */
public final class MonitorService extends Service {

    public static final String ACTION_STOP = "com.amapbandalert.action.STOP";
    private static volatile boolean running = false;
    private static volatile MonitorService instance;

    private LocationSpeedMonitor speedMonitor;

    public static boolean isRunning() { return running; }

    @Override
    public void onCreate() {
        super.onCreate();
        AlertNotifier.ensureChannels(this);
        AlertEngine.get().init(this);
        Notification n = AlertNotifier.buildStatus(this, "AmapBandAlert 监听中", "等待高德导航…");
        startAsForeground(n);
        running = true;
        instance = this;
        AlertLogger.log(AlertLogger.Tag.INFO, "前台监听服务已启动");

        speedMonitor = new LocationSpeedMonitor(this);
        if (LocationSpeedMonitor.hasPermission(this)) {
            speedMonitor.start();
        } else {
            AlertLogger.log(AlertLogger.Tag.INFO, "未授权定位，GPS 车速不可用；可继续使用车机广播");
        }
    }

    private void startAsForeground(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                if (LocationSpeedMonitor.hasPermission(this)) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                }
                startForeground(1, notification, type);
                return;
            }
            if (Build.VERSION.SDK_INT >= 29 && LocationSpeedMonitor.hasPermission(this)) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                return;
            }
        } catch (RuntimeException e) {
            AlertLogger.log(AlertLogger.Tag.ERROR, "前台类型声明失败，回退: " + e.getMessage());
        }
        try {
            startForeground(1, notification);
        } catch (RuntimeException e) {
            AlertLogger.log(AlertLogger.Tag.ERROR, "前台服务启动失败: " + e.getMessage());
            stopSelf();
        }
    }

    /** 用户刚授予定位权限后调用，让当前服务立即开始 GPS 监听。 */
    public static void refreshLocation() {
        MonitorService local = instance;
        if (local == null) return;
        if (local.speedMonitor == null) local.speedMonitor = new LocationSpeedMonitor(local);
        if (Build.VERSION.SDK_INT >= 29 && LocationSpeedMonitor.hasPermission(local)) {
            try {
                // 重新声明 location 类型，否则 Android 14 在后台可能拒绝定位。
                local.startAsForeground(AlertNotifier.buildStatus(local,
                        "AmapBandAlert 监听中", "GPS 车速已开启"));
            } catch (RuntimeException e) {
                AlertLogger.log(AlertLogger.Tag.ERROR, "切换定位前台类型失败: " + e.getMessage());
            }
        }
        local.speedMonitor.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AlertLogger.log(AlertLogger.Tag.INFO, "用户从通知栏停止监听");
            Prefs.get(this).setEnabled(false);
            AlertEngine.get().pause();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Prefs.get(this).enabled()) {
            // 系统重启服务但用户已关闭监听，不要留下僵尸前台通知。
            stopSelf();
            return START_NOT_STICKY;
        }
        if (speedMonitor != null) speedMonitor.start();
        AlertNotifier.postStatus(this, "AmapBandAlert 监听中", "等待高德导航…");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (speedMonitor != null) {
            speedMonitor.stop();
            speedMonitor = null;
        }
        if (instance == this) instance = null;
        AlertLogger.log(AlertLogger.Tag.INFO, "前台监听服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static void start(Context context) {
        Intent i = new Intent(context, MonitorService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, MonitorService.class));
    }
}
