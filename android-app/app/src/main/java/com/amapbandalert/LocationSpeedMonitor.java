package com.amapbandalert;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

/**
 * GPS 车速监听。
 *
 * <p>手机版高德不提供道路限速，但 GPS 速度是可靠的。配合“固定阈值”或从高德界面读到的
 * 限速，就能在导航时做超速提醒。</p>
 */
public final class LocationSpeedMonitor implements LocationListener {

    private final Context context;
    private final LocationManager manager;
    private boolean running;
    private Location last;
    private long lastTime;
    private float smoothed = -1f;

    public LocationSpeedMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public static boolean hasPermission(Context context) {
        return hasFinePermission(context)
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasFinePermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isRunning() { return running; }

    public void start() {
        if (running) return;
        if (manager == null) {
            AlertLogger.log(AlertLogger.Tag.ERROR, "定位服务不可用");
            return;
        }
        if (!hasPermission(context)) {
            AlertLogger.log(AlertLogger.Tag.INFO, "未授予定位权限，GPS 车速不可用");
            return;
        }
        try {
            String provider = hasFinePermission(context)
                    ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            manager.requestLocationUpdates(provider,
                    1000L, 0f, this, Looper.getMainLooper());
            running = true;
            AlertLogger.log(AlertLogger.Tag.INFO, "GPS 车速监听已启动: " + provider);
        } catch (SecurityException | IllegalArgumentException e) {
            AlertLogger.log(AlertLogger.Tag.ERROR, "GPS 车速监听失败: " + e.getMessage());
        }
    }

    public void stop() {
        if (!running) return;
        try {
            manager.removeUpdates(this);
        } catch (RuntimeException ignored) {
            // ignore
        }
        running = false;
        last = null;
        lastTime = 0;
        smoothed = -1f;
        AlertLogger.log(AlertLogger.Tag.INFO, "GPS 车速监听已停止");
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        if (location.hasAccuracy() && location.getAccuracy() > 100f) return;

        float kmh;
        if (location.hasSpeed()) {
            kmh = location.getSpeed() * 3.6f;
        } else if (last != null) {
            long dt = location.getTime() - lastTime;
            if (dt <= 0) return;
            float[] result = new float[1];
            Location.distanceBetween(last.getLatitude(), last.getLongitude(),
                    location.getLatitude(), location.getLongitude(), result);
            kmh = result[0] / (dt / 1000f) * 3.6f;
        } else {
            return;
        }

        last = location;
        lastTime = location.getTime();
        if (kmh < 1.5f) kmh = 0f;
        smoothed = smoothed < 0 ? kmh : smoothed * 0.6f + kmh * 0.4f;
        if (smoothed < 1.5f) smoothed = 0f;
        AlertEngine.get().onGpsSpeed(Math.round(smoothed));
    }

    @Override
    public void onProviderEnabled(String provider) {
        AlertLogger.log(AlertLogger.Tag.INFO, "定位 provider 可用: " + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        AlertLogger.log(AlertLogger.Tag.INFO, "定位 provider 不可用: " + provider);
        AlertEngine.get().onGpsSpeed(0);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // 旧接口，保留空实现
    }
}
