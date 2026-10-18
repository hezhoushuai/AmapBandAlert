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
 * 场景引擎：把“高德通知解析结果”和“高德车机版广播”统一成手环提醒。
 *
 * <p>线程模型：通知监听、广播、UI 都在主线程，处理只做文本判断和发通知，足够轻量。</p>
 */
public final class AlertEngine {

    private static final AlertEngine INSTANCE = new AlertEngine();
    public static AlertEngine get() { return INSTANCE; }

    private static final String[] KEYWORDS = {
            "区间测速", "电子眼", "违章拍照", "测速", "拍照", "限速",
            "拥堵", "事故", "施工", "封路", "限行",
            "重新规划", "偏航"
    };

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
    private int lastSpeed = -1;
    private int lastLimit = -1;
    private long lastUpdateAt = 0;
    private boolean listenerConnected = false;
    private String lastFingerprint = "";
    private boolean lastRerouted = false;

    private String turnKey = "";
    private boolean turnAlerted = false;
    private boolean nearAlerted = false;
    private long lastTurnAlertAt = 0;

    private long overspeedSince = 0;
    private long lastOverspeedAt = 0;
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

        lastSource = source;
        lastUpdateAt = System.currentTimeMillis();
        lastRaw = result.rawText;
        lastInstruction = result.instruction;
        lastManeuver = result.maneuver;
        lastDistance = result.distanceM;
        if (result.speed > 0) lastSpeed = result.speed;
        if (result.limit > 0) lastLimit = result.limit;
        lastRerouted = result.rerouted;

        if (!result.fingerprint().equals(lastFingerprint)) {
            lastFingerprint = result.fingerprint();
            AlertLogger.log(AlertLogger.Tag.BROADCAST,
                    "解析[" + source + "] " + result.status + " "
                            + result.instruction
                            + (result.distanceM > 0 ? " " + result.distanceM + "m" : "")
                            + (result.road.isEmpty() ? "" : " / " + result.road));
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
     * 手机版高德通常不发送该广播；此通道保留给车机版用户，也能兼容后续魔改版。
     */
    public void onBroadcast(int curSpeed, int limitSpeed, int cameraType,
                            int cameraDist, int cameraSpeed) {
        lastSource = "车机广播";
        lastUpdateAt = System.currentTimeMillis();
        if (limitSpeed > 0) lastLimit = limitSpeed;
        if (curSpeed > 0) lastSpeed = curSpeed;

        long now = System.currentTimeMillis();
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

        if (prefs.overspeedEnabled() && limitSpeed > 0 && curSpeed > limitSpeed) {
            if (overspeedSince == 0) overspeedSince = now;
            if (now - overspeedSince >= prefs.overspeedSeconds() * 1000L
                    && now - lastOverspeedAt > 30000) {
                lastOverspeedAt = now;
                overspeedSince = now;
                AlertNotifier.notifyOverspeed(app, curSpeed, limitSpeed);
            }
        } else if (limitSpeed > 0) {
            overspeedSince = 0;
        }
        notifyUi();
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
        lastSpeed = -1;
        lastLimit = -1;
        lastUpdateAt = 0;
        lastFingerprint = "";
        lastRerouted = false;
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
        if (!listenerConnected) return "通知监听：未连接（请授予通知使用权）";
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
        if (lastSpeed > 0) sb.append("当前 ").append(lastSpeed);
        if (lastLimit > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("限速 ").append(lastLimit);
        }
        if (lastRerouted) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("路线已重新规划");
        }
        return sb.length() == 0 ? "车速/限速：通知未提供" : sb.toString();
    }

    public boolean isNavActive() { return "active".equals(lastStatus); }

    public String getLastRaw() { return lastRaw; }
}
