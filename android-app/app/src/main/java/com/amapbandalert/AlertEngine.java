package com.amapbandalert;

import android.os.Handler;
import android.os.Looper;

/**
 * 预警引擎: 消费高德广播数据, 判断是否触发提醒。
 * 线程模型: 广播在主线程到达, 处理轻量, 直接同步执行。
 */
public final class AlertEngine {

    // ===== 可调参数(持久化在 Prefs) =====
    private int cameraAlertDistance = 300;   // 电子眼预警距离(米)
    private boolean overspeedEnabled = true; // 超速提醒开关
    private boolean cameraEnabled = true;    // 电子眼提醒开关

    // ===== 防抖状态 =====
    private long lastCameraAlertAt = 0;
    private int lastCameraDist = Integer.MAX_VALUE;
    private static final long CAMERA_ALERT_COOLDOWN_MS = 15_000; // 同一电子眼最少间隔15s
    private static final int CAMERA_PASS_DIST = 30;              // 距离<30m视为已通过,重置防抖

    private long overspeedSince = 0;
    private static final long OVERSPEED_MIN_DURATION_MS = 5_000; // 超速持续5s才提醒

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    public interface Listener {
        void onCameraAlert(int distM, int limitSpeed, int curSpeed);
        void onOverspeed(int curSpeed, int limitSpeed);
    }

    private static final AlertEngine INSTANCE = new AlertEngine();
    public static AlertEngine get() { return INSTANCE; }
    private AlertEngine() {}

    public void setListener(Listener l) { this.listener = l; }

    public void setCameraAlertDistance(int m) { this.cameraAlertDistance = m; }
    public void setOverspeedEnabled(boolean b) { this.overspeedEnabled = b; }
    public void setCameraEnabled(boolean b) { this.cameraEnabled = b; }

    /** 每次高德广播(约1Hz)调用一次 */
    public void feed(int curSpeed, int limitSpeed, int cameraType, int cameraDist, int cameraSpeed, int icon) {
        if (cameraEnabled) checkCamera(curSpeed, cameraType, cameraDist, cameraSpeed);
        if (overspeedEnabled && limitSpeed > 0) checkOverspeed(curSpeed, limitSpeed);
    }

    /**
     * 电子眼检测。
     * CAMERA_TYPE 枚举含义需实测标定: P0阶段对所有 cameraDist>0 且类型!=0 的电子眼触发,
     * 并在通知中携带 type 值, 骑行收集后确定"限速拍照"的枚举再精确过滤。
     */
    private void checkCamera(int curSpeed, int type, int dist, int limit) {
        if (type == 0 || dist <= 0) { 
            // 无电子眼数据; 若之前接近过且现已越过, 重置防抖
            if (lastCameraDist < CAMERA_PASS_DIST && dist == 0) {
                resetCameraDebounce();
                AlertLogger.log(AlertLogger.Tag.INFO, "电子眼已通过, 防抖重置");
            }
            lastCameraDist = dist;
            return;
        }
        boolean approaching = dist <= cameraAlertDistance;
        long now = System.currentTimeMillis();
        if (approaching && now - lastCameraAlertAt > CAMERA_ALERT_COOLDOWN_MS) {
            // 距离在靠近(而非远离)才触发, 避免倒放/掉头重复
            if (dist <= lastCameraDist || lastCameraDist == Integer.MAX_VALUE) {
                lastCameraAlertAt = now;
                AlertLogger.log(AlertLogger.Tag.ALERT,
                        "⚡电子眼预警 type=" + type + " dist=" + dist + "m 限速" + limit);
                if (listener != null) listener.onCameraAlert(dist, limit, curSpeed);
            }
        }
        lastCameraDist = dist;
    }

    private void checkOverspeed(int curSpeed, int limit) {
        long now = System.currentTimeMillis();
        if (curSpeed > limit) {
            if (overspeedSince == 0) overspeedSince = now;
            else if (now - overspeedSince >= OVERSPEED_MIN_DURATION_MS && listener != null) {
                AlertLogger.log(AlertLogger.Tag.ALERT, "🚨超速 " + curSpeed + "/" + limit + " 持续超5s");
                listener.onOverspeed(curSpeed, limit);
                overspeedSince = now + 30_000; // 提醒后30s内不重复
            }
        } else {
            overspeedSince = 0;
        }
    }

    private void resetCameraDebounce() { lastCameraAlertAt = 0; lastCameraDist = Integer.MAX_VALUE; }

    /** 测试按钮用: 模拟一次电子眼预警, 不依赖高德 */
    public void fireTestAlert() {
        AlertLogger.log(AlertLogger.Tag.INFO, "手动触发测试预警");
        if (listener != null) listener.onCameraAlert(300, 60, 45);
    }
}
