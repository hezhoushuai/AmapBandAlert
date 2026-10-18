package com.amapbandalert;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高德通知文字解析器。
 *
 * <p>只做文本启发式提取：不计算路线、不根据图标猜方向。高德没有公开手机版导航
 * 数据接口，通知文字是唯一稳定的数据来源。解析失败时保留原文供日志诊断。</p>
 */
public final class NavigationParser {

    public static final String AMAP_PHONE = "com.autonavi.minimap";
    public static final String AMAP_AUTO = "com.autonavi.amapauto";

    private static final Pattern END = Pattern.compile(
            "导航(?:已)?结束|结束导航|已到达目的地|已到达终点|本次导航已完成");
    private static final Pattern NAV = Pattern.compile(
            "导航|直行|左转|右转|掉头|调头|行驶|步行|骑行|匝道|到达|沿.{1,20}(?:路|街)|进入.{1,20}(?:路|街)");
    private static final Pattern TURN = Pattern.compile(
            "左前方|右前方|左后方|右后方|向左转|向右转|左转|右转|掉头|调头|直行|靠左|靠右|进入环岛|驶出环岛");
    private static final Pattern BEFORE = Pattern.compile(
            "(?:前方|还有|再|约|行驶)?\\s*(\\d+(?:\\.\\d+)?)\\s*(公里|千米|米|km|m)\\s*(?:后|处)?\\s*向?[，,：: ]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AFTER = Pattern.compile(
            "^[ \\t，,：:]*?(\\d+(?:\\.\\d+)?)\\s*(公里|千米|米|km|m)(?![a-z])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROAD_ENTER = Pattern.compile(
            "(?:进入|驶入|转入)\\s*([\\u4e00-\\u9fa5A-Za-z0-9·\\-]{2,18}?(?:路|街|大道|高速|快速路|环路|桥|隧道|匝道|公路|出口))");
    private static final Pattern ROAD_ALONG = Pattern.compile(
            "沿\\s*([\\u4e00-\\u9fa5A-Za-z0-9·\\-]{2,18}?(?:路|街|大道|高速|快速路|环路|桥|隧道|匝道|公路))");
    private static final Pattern SPEED = Pattern.compile(
            "(?:当前车速|车速|时速)\\s*[:：]?\\s*(\\d{1,3})");
    private static final Pattern LIMIT = Pattern.compile(
            "限速\\s*[:：]?\\s*(\\d{1,3})");
    private static final Pattern REROUTE = Pattern.compile(
            "重新规划|已偏航|路线已更新|为您重新规划");
    private static final Pattern ALERT = Pattern.compile(
            "拥堵|事故|施工|封路|限行|拍照|测速|电子眼|违章|限速|重新规划|偏航");

    private NavigationParser() {}

    public static boolean isAmapPackage(String packageName) {
        return AMAP_PHONE.equals(packageName) || AMAP_AUTO.equals(packageName);
    }

    public static final class Result {
        public final String status;      // active / ended / idle / unavailable
        public final String title;
        public final String rawText;
        public final String maneuver;    // left/right/straight/...
        public final String instruction; // 左转 中山路
        public final String road;        // 中山路
        public final int distanceM;      // -1 = 未知
        public final int speed;          // -1 = 未知
        public final int limit;          // -1 = 未知
        public final boolean rerouted;
        public final long postTime;

        public Result(String status, String title, String rawText, String maneuver,
                      String instruction, String road, int distanceM, int speed, int limit,
                      boolean rerouted, long postTime) {
            this.status = status;
            this.title = clip(title, 80);
            this.rawText = clip(rawText, 600);
            this.maneuver = maneuver;
            this.instruction = clip(instruction, 60);
            this.road = clip(road, 40);
            this.distanceM = distanceM;
            this.speed = speed;
            this.limit = limit;
            this.rerouted = rerouted;
            this.postTime = postTime;
        }

        public boolean isActive() { return "active".equals(status); }
        public boolean isEnded() { return "ended".equals(status); }

        public String fingerprint() {
            return status + '\n' + maneuver + '\n' + instruction + '\n' + road + '\n'
                    + distanceM + '\n' + speed + '\n' + limit;
        }
    }

    public static Result parse(String packageName, boolean ongoing, long postTime, String... fields) {
        if (!isAmapPackage(packageName)) return null;
        Set<String> unique = new LinkedHashSet<>();
        for (String field : fields) {
            if (field != null) {
                String cleaned = field.trim().replaceAll("[\\t ]+", " ");
                if (!cleaned.isEmpty()) unique.add(cleaned);
            }
        }
        if (unique.isEmpty()) return null;

        String text = String.join("\n", unique);
        String title = unique.iterator().next();

        if (END.matcher(text).find()) {
            return new Result("ended", title, text, "arrive", "导航已结束", "",
                    -1, -1, -1, false, postTime);
        }

        Matcher turn = TURN.matcher(text);
        boolean hasTurn = turn.find();
        boolean looksLikeNav = NAV.matcher(text).find();
        boolean looksLikeAlert = ALERT.matcher(text).find();
        // 高德在前台时通知可能只有"正在导航"一行；仍然认为导航活跃，只是没有转向数据。
        // 非 ongoing 的纯“导航”字样（如“开启导航享优惠”）不算导航，避免误报。
        boolean navActive = looksLikeNav && (ongoing || text.contains("导航中")
                || text.contains("剩余") || hasTurn);
        // 电子眼/拥堵类通知可能不带转向，也保留下来供关键词模式使用。
        if (!navActive && !hasTurn && !looksLikeAlert) return null;

        String instruction = hasTurn ? turn.group() : (looksLikeNav ? "导航中" : "导航提示");
        String maneuver = hasTurn ? maneuver(instruction) : "unknown";
        int distance = hasTurn ? distance(text, turn.start(), turn.end()) : -1;
        String road = hasTurn ? road(text) : "";
        if (hasTurn && !road.isEmpty()) instruction = instruction + " " + road;

        int speed = number(SPEED, text);
        int limit = number(LIMIT, text);
        boolean rerouted = REROUTE.matcher(text).find();

        return new Result("active", title, text, maneuver, instruction, road,
                distance, speed, limit, rerouted, postTime);
    }

    private static int distance(String text, int turnStart, int turnEnd) {
        String before = text.substring(Math.max(0, turnStart - 40), turnStart);
        Matcher bm = BEFORE.matcher(before);
        String lastValue = null;
        String lastUnit = null;
        int lastStart = -1;
        while (bm.find()) {
            lastValue = bm.group(1);
            lastUnit = bm.group(2);
            lastStart = bm.start();
        }
        if (lastValue != null && lastStart >= 0) {
            String prefix = before.substring(0, lastStart).trim();
            // "剩余 3 公里" 是全程剩余，不是下一个转向的距离。
            if (!prefix.endsWith("剩余") && !prefix.endsWith("总里程")) {
                int meters = toMeters(lastValue, lastUnit);
                if (meters > 0) return meters;
            }
        }
        Matcher am = AFTER.matcher(text.substring(turnEnd));
        if (am.find()) {
            int meters = toMeters(am.group(1), am.group(2));
            if (meters > 0) return meters;
        }
        return -1;
    }

    private static String road(String text) {
        Matcher enter = ROAD_ENTER.matcher(text);
        if (enter.find()) return cleanRoad(enter.group(1));
        Matcher along = ROAD_ALONG.matcher(text);
        if (along.find()) return cleanRoad(along.group(1));
        return "";
    }

    private static String cleanRoad(String value) {
        if (value == null) return "";
        String s = value.trim();
        if (s.endsWith("行驶")) s = s.substring(0, s.length() - 2);
        return s;
    }

    private static int number(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return -1;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return -1; }
    }

    private static int toMeters(String value, String unit) {
        try {
            double v = Double.parseDouble(value);
            String u = unit.toLowerCase(Locale.ROOT);
            if (u.startsWith("公里") || u.equals("千米") || u.equals("km")) v *= 1000;
            if (v <= 0 || v > 200000) return -1;
            return (int) Math.round(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String maneuver(String text) {
        if (text.contains("掉头") || text.contains("调头")) return "uturn";
        if (text.contains("环岛")) return "roundabout";
        if (text.contains("左前") || text.contains("靠左")) return "slight_left";
        if (text.contains("右前") || text.contains("靠右")) return "slight_right";
        if (text.contains("左后")) return "sharp_left";
        if (text.contains("右后")) return "sharp_right";
        if (text.contains("左")) return "left";
        if (text.contains("右")) return "right";
        if (text.contains("直行")) return "straight";
        return "unknown";
    }

    public static String maneuverLabel(String maneuver) {
        if ("left".equals(maneuver)) return "左转";
        if ("right".equals(maneuver)) return "右转";
        if ("slight_left".equals(maneuver)) return "左前方";
        if ("slight_right".equals(maneuver)) return "右前方";
        if ("sharp_left".equals(maneuver)) return "左后方";
        if ("sharp_right".equals(maneuver)) return "右后方";
        if ("uturn".equals(maneuver)) return "掉头";
        if ("roundabout".equals(maneuver)) return "环岛";
        if ("straight".equals(maneuver)) return "直行";
        if ("arrive".equals(maneuver)) return "到达";
        return "导航";
    }

    public static String clip(String value, int limit) {
        if (value == null) return "";
        if (value.length() <= limit) return value;
        int end = limit - 1;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }
}
