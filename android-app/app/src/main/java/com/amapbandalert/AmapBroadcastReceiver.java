package com.amapbandalert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 监听高德地图 AUTONAVI_STANDARD_BROADCAST_SEND 广播。
 * 协议: AmapAuto标准广播协议, KEY_TYPE=10001 为引导信息。
 * 说明: 高德的"模拟导航"也会发送同样的广播(状态值不同), 因此可用模拟导航在家测试全链路。
 */
public class AmapBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        int keyType = intent.getIntExtra("KEY_TYPE", 0);
        if (keyType != 10001) return; // 只关心引导信息

        int curSpeed = getInt(intent, "CUR_SPEED");
        int limitSpeed = getInt(intent, "LIMITED_SPEED");
        int cameraType = getInt(intent, "CAMERA_TYPE");
        int cameraDist = getInt(intent, "CAMERA_DIST");
        int cameraSpeed = getInt(intent, "CAMERA_SPEED");
        int icon = getInt(intent, "ICON");

        AlertLogger.log(AlertLogger.Tag.BROADCAST,
                "speed=" + curSpeed + " limit=" + limitSpeed
                        + " cam[type=" + cameraType + " dist=" + cameraDist + " limit=" + cameraSpeed + "]"
                        + " icon=" + icon);

        AlertEngine.get().feed(curSpeed, limitSpeed, cameraType, cameraDist, cameraSpeed, icon);
    }

    private static int getInt(Intent i, String key) {
        return i == null ? 0 : i.getIntExtra(key, 0);
    }
}
