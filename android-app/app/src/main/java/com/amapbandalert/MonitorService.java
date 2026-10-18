package com.amapbandalert;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/** 前台服务：保持进程存活，让通知监听和广播接收在后台持续工作。 */
public final class MonitorService extends Service {

    public static final String ACTION_STOP = "com.amapbandalert.action.STOP";
    private static volatile boolean running = false;

    public static boolean isRunning() { return running; }

    @Override
    public void onCreate() {
        super.onCreate();
        AlertNotifier.ensureChannels(this);
        AlertEngine.get().init(this);
        Notification n = AlertNotifier.buildStatus(this, "AmapBandAlert 监听中", "等待高德导航…");
        startForeground(1, n);
        running = true;
        AlertLogger.log(AlertLogger.Tag.INFO, "前台监听服务已启动");
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
        AlertNotifier.postStatus(this, "AmapBandAlert 监听中", "等待高德导航…");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
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
