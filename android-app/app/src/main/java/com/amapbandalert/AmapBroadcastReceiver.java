package com.amapbandalert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 监听高德地图 AUTONAVI_STANDARD_BROADCAST_SEND 广播。
 * 参考协议: AmapAuto标准广播协议 (KEY_TYPE=10001 引导信息)
 * 字段参考: Navi-Link AmapNaviReceiver / amap-companion BroadcastProtocol
 */
public class AmapBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        int keyType = intent.getIntExtra("KEY_TYPE", 0);
        if (keyType != 10001) return; // 只关心引导信息

        AlertEngine engine = AlertEngine.get();
        engine.feed(
            getInt(intent, "CUR_SPEED"),
            getInt(intent, "LIMITED_SPEED"),
            getInt(intent, "CAMERA_TYPE"),
            getInt(intent, "CAMERA_DIST"),
            getInt(intent, "CAMERA_SPEED"),
            getInt(intent, "ICON")
        );
    }

    private static int getInt(Intent i, String key) {
        return i == null ? 0 : i.getIntExtra(key, 0);
    }
}
