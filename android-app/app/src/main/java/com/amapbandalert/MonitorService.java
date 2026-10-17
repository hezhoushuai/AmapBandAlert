package com.amapbandalert;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** 前台服务: 维持进程存活, 保证广播接收器不被杀 */
public class MonitorService extends Service {
    @Override
    public void onCreate() {
        super.onCreate();
        AlertNotifier.ensureChannels(this);
        Notification n = new Notification.Builder(this, "moto_status")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("AmapBandAlert 监听中")
                .setContentText("高德导航广播监听运行中")
                .setOngoing(true)
                .build();
        startForeground(1, n);
        AlertEngine.get(); // 初始化单例
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
