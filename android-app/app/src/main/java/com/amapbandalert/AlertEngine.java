package com.amapbandalert;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 场景引擎：把高德通知、车机版广播、GPS 车速和高德界面限速统一成手环提醒。
 *
 * <p>线程模型：通知监听、广播、无障碍、定位回调都在主线程附近，处理只做文本判断和发通知，
 * 足够轻量。</p>
 */
public final class AlertEngine {

    private static final AlertEngine INSTANCE = new AlertEngine();
    public static AlertEngine get() { return INSTANCE; }

    private static final String[] KEYWORDS = {
            "区间测速", "电子眼", "违章拍照", "测速", "拍照", "限速",
            "拥堵", "事故", "施工", "封路", "限行",
            "重新规划", "偏航"
    };

    /** 超过限速 3km/h 才认为超速，避免 GPS 抖动。 */
    private static final int OVERSPEED_TOLERANCE = 3;
    /** 同一段超速提醒后 30 秒内不重复。 */
    private static final long OVERSPEED_COOLDOWN_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Long> keywordAt = new HashMap<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private Context app;
    private Prefs prefs;
    private Runnable uiListener;

    private String lastStatus = "idle";
    private String lastSource = "";
    private String lastRaw = "";
    private String lastInstruction = "";
    private String lastManeuver = "unknown";
    private int lastDistance = -1;
    private long lastUpdateAt = 0;
    private boolean listenerConnected = false;
    private String lastFingerprint = "";
    private boolean lastRerouted = false;

    private String turnKey = "";
    private boolean turnAlerted = false;
    private boolean nearAlerted = false;
    private long lastTurnAlertAt = 0;

    // 速度/限速数据源（各自带时间戳，按新鲜度择优）
    private int broadcastSpeed = -1;
    private int broadcastLimit = -1;
    private long broadcastAt = 0;
    private int gpsSpeed = -1;
    private long gpsAt = 0;
    private int uiLimit = -1;
    private long uiAt = 0;
    private int notificationLimit = -1;
    private long notificationLimitAt = 0;
    private int notificationSpeed = -1;
    private long notificationSpeedAt = 0;

    private int currentSpeed = -1;
    private int currentLimit = -1;
    private String limitSource = "";

    private long overspeedSince = 0;
    private long lastOverspeedAt = 0;
    private int lastLimitForTimer = -1;

    private long lastCameraAt = 0;
    private int lastCameraDist = Integer.MAX_VALUE;

    private AlertEngine() {}

    public synchronized void init(Context context) {
        if (app != null) return;
        app = context.getApplicationContext();
        prefs = Prefs.get(app);
    }

    public void setUiListener(Runnable listener) {
        uiListener = listener;
    }

    public boolean isListenerConnected() { return listenerConnected; }

    public void setListenerConnected(boolean connected) {
        listenerConnected = connected;
        AlertLogger.log(AlertLogger.Tag.INFO,
                connected ? "通知监听已连接" : "通知监听已断开");
        notifyUi();
    }

    /** 通知监听服务每轮扫描后调用。 */
    public void onNavState(NavigationParser.Result result, String source) {
        if (result == null) return;
        String previousRaw = lastRaw;
        String previousStatus = lastStatus;
        long now = System.currentTimeMillis();

        lastSource = source;
        lastUpdateAt = now;
        lastRaw = result.rawText;
        lastInstruction = result.instruction;
        lastManeuver = result.maneuver;
        lastDistance = result.distanceM;
        if (result.limit > 0) {
            notificationLimit = result.limit;
            notificationLimitAt = now;
        }
        if (result.speed > 0) {
            notificationSpeed = result.speed;
            notificationSpeedAt = now;
        }
        lastRerouted = result.rerouted;

        if (!result.fingerprint().equals(lastFingerprint)) {
            lastFingerprint = result.fingerprint();
            AlertLogger.log(AlertLogger.Tag.BROADCAST,
                    "解析[" + source + "] " + result.status + " "
                            + result.instruction
                            + (result.distanceM > 0 ? " " + result.distanceM + "m" : "")
                            + (result.road.isEmpty() ? "" : " / " + result.road)
                            + (result.limit > 0 ? " 限速" + result.limit : ""));
        }

        if (result.isActive()) {
            if (!"active".equals(previousStatus)) {
                resetTurnState();
                if (prefs.startEnabled()) AlertNotifier.notifyStart(app);
            }
            handleTurn(result);
            handleKeywords(result, previousRaw);
        } else if (result.isEnded()) {
            if ("active".equals(previousStatus) && prefs.arriveEnabled()) {
                AlertNotifier.notifyArrive(app);
            }
            resetTurnState();
        }

        lastStatus = result.status;
        evaluateOverspeed(now);
        notifyUi();
    }

    /** 有高德通知但文字不可读：只记录，不改变导航状态。 */
    public void onUnreadable(String raw, String source) {
        lastSource = source;
        lastUpdateAt = System.currentTimeMillis();
        if (raw != null && !raw.equals(lastRaw)) {
            lastRaw = raw;
            AlertLogger.log(AlertLogger.Tag.BROADCAST,
                    "高德通知无可解析文字: " + NavigationParser.clip(raw, 200));
        }
        notifyUi();
    }

    /** 当前没有高德通知。持续一段时间才认为导航结束，避免通知重发间隙误判。 */
    public void onNoAmapNotification() {
        long now = System.currentTimeMillis();
        if ("active".equals(lastStatus) && now - lastUpdateAt > 45000) {
            AlertLogger.log(AlertLogger.Tag.INFO, "高德通知消失超过 45s，结束本次导航");
            lastStatus = "idle";
            resetTurnState();
            notifyUi();
        }
    }

    public void onListenerError(String message) {
        AlertLogger.log(AlertLogger.Tag.ERROR, message);
        notifyUi();
    }

    private void handleTurn(NavigationParser.Result r) {
        if (!prefs.turnEnabled()) return;
        if ("unknown".equals(r.maneuver) || "arrive".equals(r.maneuver)) return;

        String key = r.maneuver + "|" + r.instruction + "|" + r.road;
        if (!key.equals(turnKey)) {
            turnKey = key;
            turnAlerted = false;
            nearAlerted = false;
        }

        long now = System.currentTimeMillis();
        if (r.distanceM > 0) {
            if (!turnAlerted && r.distanceM <= prefs.turnDistance()
                    && now - lastTurnAlertAt > 6000) {
                turnAlerted = true;
                lastTurnAlertAt = now;
                AlertNotifier.notifyTurn(app, r);
                return;
            }
            if (prefs.nearEnabled() && turnAlerted && !nearAlerted
                    && r.distanceM <= 50 && now - lastTurnAlertAt > 6000) {
                nearAlerted = true;
                lastTurnAlertAt = now;
                AlertNotifier.notifyNear(app, r);
            }
        } else if (!turnAlerted && now - lastTurnAlertAt > 15000) {
            // 有些路口通知只有“靠左行驶/进入环岛”，没有距离，按指令变化提醒一次。
            turnAlerted = true;
            lastTurnAlertAt = now;
            AlertNotifier.notifyTurnUnknown(app, r);
        }
    }

    private void handleKeywords(NavigationParser.Result r, String previousRaw) {
        if (!prefs.keywordEnabled()) return;
        long now = System.currentTimeMillis();
        for (String keyword : KEYWORDS) {
            if (!r.rawText.contains(keyword)) continue;
            if (previousRaw != null && previousRaw.contains(keyword)) continue;
            Long last = keywordAt.get(keyword);
            if (last != null && now - last < 60000) continue;
            keywordAt.put(keyword, now);
            AlertNotifier.notifyKeyword(app, keyword, snippet(r.rawText, keyword));
            return; // 一轮最多一条，避免同一通知触发多条
        }
    }

    private static String snippet(String text, String keyword) {
        int index = text.indexOf(keyword);
        if (index < 0) return NavigationParser.clip(text, 80);
        int start = Math.max(0, index - 18);
        int end = Math.min(text.length(), index + keyword.length() + 32);
        String s = text.substring(start, end).replace('\n', ' ').trim();
        return NavigationParser.clip(s, 90);
    }

    /**
     * 高德车机版/AmapAuto 广播入口。
     * 手机版高德通常不发送该广播；此通道保留给车机版用户。
     */
    public void onBroadcast(int curSpeed, int limitSpeed, int cameraType,
                            int cameraDist, int cameraSpeed) {
        long now = System.currentTimeMillis();
        lastSource = "车机广播";
        lastUpdateAt = now;
        if (curSpeed > 0) {
            broadcastSpeed = curSpeed;
            broadcastAt = now;
        }
        if (limitSpeed > 0) {
            broadcastLimit = limitSpeed;
            broadcastAt = now;
        }

        if (prefs.cameraEnabled() && cameraType != 0 && cameraDist > 0) {
            if (cameraDist <= 300 && now - lastCameraAt > 15000
                    && (cameraDist <= lastCameraDist || lastCameraDist == Integer.MAX_VALUE)) {
                lastCameraAt = now;
                AlertNotifier.notifyCamera(app, cameraDist,
                        cameraSpeed > 0 ? cameraSpeed : limitSpeed, curSpeed);
            }
            lastCameraDist = cameraDist;
        } else if (cameraDist <= 0) {
            lastCameraDist = Integer.MAX_VALUE;
        }

        evaluateOverspeed(now);
        notifyUi();
    }

    /** GPS 速度（km/h），由前台定位监听回调。 */
    public void onGpsSpeed(int kmh) {
        long now = System.currentTimeMillis();
        gpsSpeed = kmh;
        gpsAt = now;
        evaluateOverspeed(now);
        notifyUi();
    }

    /** 固定阈值等设置变化后立即重算一次。 */
    public void refreshOverspeed() {
        evaluateOverspeed(System.currentTimeMillis());
        notifyUi();
    }

    /** 高德导航界面读到的道路限速（无障碍，实验性）。 */
    public void onUiLimit(int limit) {
        if (limit <= 0) return;
        uiLimit = limit;
        uiAt = System.currentTimeMillis();
        AlertLogger.log(AlertLogger.Tag.BROADCAST, "高德界面限速=" + limit);
        evaluateOverspeed(uiAt);
        notifyUi();
    }

    /**
     * 统一超速判断。
     * 速度优先级：车机广播 > GPS > 通知里的“当前车速”。
     * 限速优先级：车机广播 > 高德界面 > 通知文字 > 固定阈值。
     */
    private void evaluateOverspeed(long now) {
        if (prefs == null) return;
        if (!prefs.overspeedEnabled()) {
            overspeedSince = 0;
            return;
        }

        int speed = -1;
        String speedSource = "";
        if (broadcastAt > 0 && now - broadcastAt < 6000 && broadcastSpeed > 0) {
            speed = broadcastSpeed;
            speedSource = "车机广播";
        } else if (gpsAt > 0 && now - gpsAt < 5000 && gpsSpeed > 0) {
            speed = gpsSpeed;
            speedSource = "GPS";
        } else if (notificationSpeedAt > 0 && now - notificationSpeedAt < 15000
                && notificationSpeed > 0) {
            speed = notificationSpeed;
            speedSource = "通知";
        }

        int limit = -1;
        String limitSrc = "";
        if (broadcastAt > 0 && now - broadcastAt < 10000 && broadcastLimit > 0) {
            limit = broadcastLimit;
            limitSrc = "车机广播";
        } else if (uiAt > 0 && now - uiAt < 15000 && uiLimit > 0) {
            limit = uiLimit;
            limitSrc = "高德界面";
        } else if (notificationLimitAt > 0 && now - notificationLimitAt < 20000
                && notificationLimit > 0) {
            limit = notificationLimit;
            limitSrc = "通知";
        } else if (prefs.fixedLimitEnabled()) {
            limit = prefs.fixedLimitKmh();
            limitSrc = "固定阈值";
        }

        currentSpeed = speed;
        currentLimit = limit;
        limitSource = speedSource.isEmpty() && limitSrc.isEmpty()
                ? "" : (speedSource + (limitSrc.isEmpty() ? "" : " · " + limitSrc));

        if (speed <= 0 || limit <= 0) {
            overspeedSince = 0;
            lastLimitForTimer = limit;
            return;
        }
        if (limit != lastLimitForTimer) {
            overspeedSince = 0;
            lastLimitForTimer = limit;
        }

        if (speed > limit + OVERSPEED_TOLERANCE) {
            if (overspeedSince == 0) overspeedSince = now;
            if (now - overspeedSince >= prefs.overspeedSeconds() * 1000L
                    && now - lastOverspeedAt > OVERSPEED_COOLDOWN_MS) {
                lastOverspeedAt = now;
                overspeedSince = now;
                AlertNotifier.notifyOverspeed(app, speed, limit);
            }
        } else {
            overspeedSince = 0;
        }
    }

    private void resetTurnState() {
        turnKey = "";
        turnAlerted = false;
        nearAlerted = false;
        lastTurnAlertAt = 0;
    }

    /** 用户停止监听时清空当前导航状态，避免 UI 仍显示“导航中”。 */
    public void pause() {
        lastStatus = "idle";
        lastSource = "";
        lastRaw = "";
        lastInstruction = "";
        lastManeuver = "unknown";
        lastDistance = -1;
        lastUpdateAt = 0;
        lastFingerprint = "";
        lastRerouted = false;
        broadcastSpeed = -1;
        broadcastLimit = -1;
        broadcastAt = 0;
        gpsSpeed = -1;
        gpsAt = 0;
        uiLimit = -1;
        uiAt = 0;
        notificationLimit = -1;
        notificationLimitAt = 0;
        notificationSpeed = -1;
        notificationSpeedAt = 0;
        currentSpeed = -1;
        currentLimit = -1;
        limitSource = "";
        overspeedSince = 0;
        lastLimitForTimer = -1;
        resetTurnState();
        notifyUi();
    }

    public void fireTestTurn() {
        if (app == null) return;
        AlertLogger.log(AlertLogger.Tag.INFO, "手动触发模拟左转");
        NavigationParser.Result r = new NavigationParser.Result(
                "active", "测试", "前方200米左转进入测试路", "left",
                "左转 测试路", "测试路", 200, 45, 60, false, System.currentTimeMillis());
        AlertNotifier.notifyTurn(app, r);
    }

    public void fireTestOverspeed() {
        if (app == null) return;
        AlertLogger.log(AlertLogger.Tag.INFO, "手动触发模拟超速 82/60");
        currentSpeed = 82;
        currentLimit = 60;
        limitSource = "测试";
        AlertNotifier.notifyOverspeed(app, 82, 60);
        notifyUi();
    }

    public void fireTestNotification() {
        if (app == null) return;
        AlertLogger.log(AlertLogger.Tag.INFO, "手动触发手环链路测试");
        AlertNotifier.notifyTest(app);
    }

    private void notifyUi() {
        if (uiListener == null) return;
        main.post(uiListener);
    }

    public String statusLine() {
        if ("active".equals(lastStatus)) {
            StringBuilder sb = new StringBuilder("导航中");
            if (!"unknown".equals(lastManeuver)) {
                sb.append(" · ").append(lastInstruction);
                if (lastDistance > 0) sb.append(" · ").append(AlertNotifier.formatDistance(lastDistance));
            } else {
                sb.append(" · 等待转向信息");
            }
            return sb.toString();
        }
        if ("ended".equals(lastStatus)) return "导航已结束";
        if (broadcastAt > 0 && System.currentTimeMillis() - broadcastAt < 5000) {
            return "车机广播导航中";
        }
        if (!listenerConnected) return "通知监听：未连接（请授予通知使用权）";
        return "等待高德导航…";
    }

    public String sourceLine() {
        if (lastUpdateAt == 0) return "尚未收到高德数据";
        return "来源：" + (lastSource.isEmpty() ? "未知" : lastSource)
                + " · " + timeFmt.format(new Date(lastUpdateAt));
    }

    public String rawLine() {
        return lastRaw == null || lastRaw.isEmpty() ? "（原始通知为空）" : lastRaw;
    }

    public String detailLine() {
        StringBuilder sb = new StringBuilder();
        if (currentSpeed > 0 || currentLimit > 0) {
            sb.append("车速 ").append(currentSpeed > 0 ? currentSpeed + " km/h" : "--");
            sb.append(" · 限速 ").append(currentLimit > 0 ? currentLimit + " km/h" : "--");
            if (!limitSource.isEmpty()) sb.append(" · ").append(limitSource);
        } else {
            sb.append("车速/限速：尚未获取");
            if (prefs != null && !prefs.fixedLimitEnabled()) {
                sb.append("（可开启固定阈值）");
            }
        }
        if (lastRerouted) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("路线已重新规划");
        }
        return sb.toString();
    }

    public boolean isNavActive() { return "active".equals(lastStatus); }

    public boolean isOverspeeding() {
        return currentSpeed > 0 && currentLimit > 0
                && currentSpeed > currentLimit + OVERSPEED_TOLERANCE;
    }

    public int currentSpeedKmh() { return currentSpeed; }
    public int currentLimitKmh() { return currentLimit; }
    public String limitSource() { return limitSource; }

    public String getLastRaw() { return lastRaw; }
}
