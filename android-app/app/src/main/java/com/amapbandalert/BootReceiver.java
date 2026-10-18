package com.amapbandalert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机/应用更新后恢复监听（部分系统会拦截后台启动，失败时只记录日志）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!Prefs.get(context).enabled()) return;
        try {
            MonitorService.start(context);
            AlertLogger.log(AlertLogger.Tag.INFO, "开机/更新后恢复监听");
        } catch (RuntimeException e) {
            AlertLogger.log(AlertLogger.Tag.ERROR, "恢复监听失败: " + e.getMessage());
        }
    }
}
