package com.amapbandalert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 高德车机版/AmapAuto 标准广播。
 *
 * <p>注意：手机版高德通常不发送这个广播，因此本接收器只是“车机版兼容通道”，
 * 手机版导航请使用通知监听。保留它不影响正常使用，也能兼容魔改车机版。</p>
 */
public class AmapBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!Prefs.get(context).enabled()) return;
        AlertEngine.get().init(context);
        int keyType = intent.getIntExtra("KEY_TYPE", 0);
        if (keyType != 10001) return;

        int curSpeed = getInt(intent, "CUR_SPEED");
        int limitSpeed = getInt(intent, "LIMITED_SPEED");
        int cameraType = getInt(intent, "CAMERA_TYPE");
        int cameraDist = getInt(intent, "CAMERA_DIST");
        int cameraSpeed = getInt(intent, "CAMERA_SPEED");

        AlertLogger.log(AlertLogger.Tag.BROADCAST,
                "车机广播 speed=" + curSpeed + " limit=" + limitSpeed
                        + " cam[type=" + cameraType + " dist=" + cameraDist
                        + " limit=" + cameraSpeed + "]");

        AlertEngine.get().onBroadcast(curSpeed, limitSpeed, cameraType, cameraDist, cameraSpeed);
    }

    private static int getInt(Intent i, String key) {
        return i == null ? 0 : i.getIntExtra(key, 0);
    }
}
