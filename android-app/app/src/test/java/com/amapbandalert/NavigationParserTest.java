package com.amapbandalert;

/**
 * 不依赖 JUnit / Android SDK 的解析器回归测试。
 * 运行：bash scripts/test_parser.sh
 */
public final class NavigationParserTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // 真实/合成的高德通知格式
        expect("前方300米左转进入中山路", "left", 300, "中山路", "active");
        expect("前方200米后右转，进入示例路", "right", 200, "示例路", "active");
        expect("前方1.2公里掉头", "uturn", 1200, "", "active");
        expect("500米后左转", "left", 500, "", "active");
        expect("沿中山路直行500米", "straight", 500, "中山路", "active");
        expect("直行152米", "straight", 152, "", "active");
        expect("直行1.2公里", "straight", 1200, "", "active");
        expect("前方300米向右前方行驶", "slight_right", 300, "", "active");
        expect("靠左行驶", "slight_left", -1, "", "active");
        expect("进入环岛", "roundabout", -1, "", "active");

        // 结束/到达
        expect("已到达目的地", "arrive", -1, "", "ended");
        expect("已到达目的地，导航结束", "arrive", -1, "", "ended");
        expect("导航结束", "arrive", -1, "", "ended");
        expect("步行导航，前方200米到达目的地", "unknown", -1, "", "active");

        // 不能把全程剩余距离当成下一个转向距离
        expect("导航中，剩余3公里，右转进入示例路", "right", -1, "示例路", "active");
        expect("导航中，剩余3公里右转", "right", -1, "", "active");
        expect("导航中，剩余3公里，前方200米右转", "right", 200, "", "active");
        expect("直行，剩余152米", "straight", -1, "", "active");
        expect("右转进入示例路，剩余152米", "right", -1, "示例路", "active");

        // 非导航文字不应误报
        assertNull("开启导航享优惠");
        assertNull("领取打车优惠券");
        assertNull("普通聊天消息");

        // 车机版广播/关键词类通知仍可被识别
        expect("前方300米有违章拍照，限速60", "unknown", -1, "", "active");

        System.out.println("NavigationParserTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void expect(String text, String maneuver, int distance,
                               String road, String status) {
        NavigationParser.Result r = NavigationParser.parse(
                NavigationParser.AMAP_PHONE, true, System.currentTimeMillis(), "高德地图", text);
        check(r != null, "parse returned null: " + text);
        if (r == null) return;
        check(status.equals(r.status), "status " + r.status + " != " + status + " for " + text);
        check(maneuver.equals(r.maneuver), "maneuver " + r.maneuver + " != " + maneuver + " for " + text);
        check(distance == r.distanceM, "distance " + r.distanceM + " != " + distance + " for " + text);
        check(road.equals(r.road), "road " + r.road + " != " + road + " for " + text);
    }

    private static void assertNull(String text) {
        NavigationParser.Result r = NavigationParser.parse(
                NavigationParser.AMAP_PHONE, false, System.currentTimeMillis(), "高德地图", text);
        check(r == null, "expected null: " + text);
    }

    private static void check(boolean ok, String message) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + message);
        }
    }
}
