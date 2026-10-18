package com.amapbandalert;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实验性：从高德导航界面读取“限速 XX”文字。
 *
 * <p>手机版高德没有公开限速接口，限速牌是否可被无障碍读取取决于高德版本和 ROM。
 * 读不到时会退回“GPS 车速 + 固定阈值”。本服务只监听高德包名，不读取其它应用。</p>
 */
public final class AmapAccessibilityService extends AccessibilityService {

    private static final Pattern LIMIT_WORD = Pattern.compile(
            "限速\\s*[:：]?\\s*(\\d{2,3})");
    private static final Pattern LIMIT_UNIT = Pattern.compile(
            "(\\d{2,3})\\s*(?:km/h|公里/小时|码)");
    private static final int MAX_NODES = 400;

    private static volatile AmapAccessibilityService instance;
    private static volatile long captureUntil = 0;
    private static volatile long lastCaptureLog = 0;
    private int lastLimit = -1;
    private long lastLimitAt = 0;
    private long lastParseAt = 0;

    public static boolean isRunning() { return instance != null; }

    public static void startCapture(int seconds) {
        captureUntil = System.currentTimeMillis() + seconds * 1000L;
        AlertLogger.log(AlertLogger.Tag.INFO,
                "开始记录高德界面文字 " + seconds + " 秒，请切到高德导航");
        AmapAccessibilityService local = instance;
        if (local != null) local.dump("开始记录");
    }

    public static void dumpCurrent() {
        AmapAccessibilityService local = instance;
        if (local == null) {
            AlertLogger.log(AlertLogger.Tag.ERROR, "无障碍服务未开启，无法读取高德界面");
            return;
        }
        local.dump("手动");
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        AlertEngine.get().init(this);
        AlertLogger.log(AlertLogger.Tag.INFO, "高德界面读取服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (!Prefs.get(this).enabled()) return;
        if (!NavigationParser.AMAP_PHONE.equals(event.getPackageName())) return;
        AlertEngine.get().init(this);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            parse(root);
        } catch (RuntimeException e) {
            AlertLogger.log(AlertLogger.Tag.ERROR, "读取高德界面失败: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
        // no-op
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private void parse(AccessibilityNodeInfo root) {
        long now = System.currentTimeMillis();
        if (now - lastParseAt < 500) return;
        lastParseAt = now;

        List<NodeText> nodes = new ArrayList<>();
        collect(root, nodes, 0);
        if (nodes.isEmpty()) return;

        int limit = findLimit(nodes);
        if (limit > 0 && (limit != lastLimit || now - lastLimitAt > 3000)) {
            lastLimit = limit;
            lastLimitAt = now;
            AlertEngine.get().onUiLimit(limit);
        }

        if (captureUntil > now && now - lastCaptureLog > 5000) {
            lastCaptureLog = now;
            logNodes("自动", nodes);
        }
    }

    private void dump(String reason) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                AlertLogger.log(AlertLogger.Tag.ERROR, "当前没有可读窗口（请先打开高德导航）");
                return;
            }
            List<NodeText> nodes = new ArrayList<>();
            collect(root, nodes, 0);
            logNodes(reason, nodes);
        } catch (RuntimeException e) {
            AlertLogger.log(AlertLogger.Tag.ERROR, "读取高德界面失败: " + e.getMessage());
        }
    }

    private void logNodes(String reason, List<NodeText> nodes) {
        AlertLogger.log(AlertLogger.Tag.BROADCAST,
                "高德界面[" + reason + "] 节点=" + nodes.size());
        int count = 0;
        for (NodeText node : nodes) {
            if (node.text.isEmpty() && node.desc.isEmpty() && node.id.isEmpty()) continue;
            AlertLogger.log(AlertLogger.Tag.BROADCAST,
                    "ui text=" + NavigationParser.clip(node.text, 40)
                            + " desc=" + NavigationParser.clip(node.desc, 40)
                            + " id=" + NavigationParser.clip(node.id, 60)
                            + " bounds=" + node.bounds);
            if (++count >= 25) break;
        }
    }

    private int findLimit(List<NodeText> nodes) {
        for (NodeText node : nodes) {
            int value = match(LIMIT_WORD, node.text);
            if (value < 0) value = match(LIMIT_WORD, node.desc);
            if (value > 0) return value;
        }
        for (NodeText node : nodes) {
            String id = node.id == null ? "" : node.id.toLowerCase(Locale.ROOT);
            String desc = node.desc == null ? "" : node.desc.toLowerCase(Locale.ROOT);
            if (id.contains("limit") || desc.contains("限速")) {
                int value = number(node.text);
                if (value < 0) value = number(node.desc);
                if (value > 0) return value;
            }
        }
        for (NodeText node : nodes) {
            int value = match(LIMIT_UNIT, node.text);
            if (value > 0) return value;
        }
        return -1;
    }

    private static int match(Pattern pattern, String text) {
        if (text == null || text.isEmpty()) return -1;
        Matcher m = pattern.matcher(text);
        if (!m.find()) return -1;
        return valid(m.group(1));
    }

    private static int number(String text) {
        if (text == null) return -1;
        String s = text.trim();
        if (!s.matches("\\d{2,3}")) return -1;
        return valid(s);
    }

    private static int valid(String value) {
        try {
            int v = Integer.parseInt(value);
            return v >= 20 && v <= 120 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void collect(AccessibilityNodeInfo node, List<NodeText> out, int depth) {
        if (node == null || depth > 40 || out.size() >= MAX_NODES) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String id = node.getViewIdResourceName();
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if ((text != null && text.length() > 0)
                || (desc != null && desc.length() > 0)
                || (id != null && !id.isEmpty())) {
            out.add(new NodeText(
                    text == null ? "" : text.toString(),
                    desc == null ? "" : desc.toString(),
                    id == null ? "" : id,
                    bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collect(child, out, depth + 1);
        }
    }

    private static final class NodeText {
        final String text;
        final String desc;
        final String id;
        final String bounds;

        NodeText(String text, String desc, String id, String bounds) {
            this.text = text;
            this.desc = desc;
            this.id = id;
            this.bounds = bounds;
        }
    }
}
