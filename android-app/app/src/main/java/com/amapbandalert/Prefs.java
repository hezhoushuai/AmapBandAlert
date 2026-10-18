package com.amapbandalert;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 轻量设置存储。所有开关即时读写，不依赖 Activity 生命周期。
 */
public final class Prefs {

    private static final String NAME = "amap_band_alert";
    private static volatile Prefs instance;

    public static Prefs get(Context context) {
        if (instance == null) {
            synchronized (Prefs.class) {
                if (instance == null) instance = new Prefs(context.getApplicationContext());
            }
        }
        return instance;
    }

    private final SharedPreferences sp;

    private Prefs(Context context) {
        sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public boolean enabled() { return sp.getBoolean("enabled", false); }
    public void setEnabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    /** 转向提醒总开关。 */
    public boolean turnEnabled() { return sp.getBoolean("turn_enabled", true); }
    public void setTurnEnabled(boolean v) { sp.edit().putBoolean("turn_enabled", v).apply(); }

    /** 转向提醒触发距离(米)。 */
    public int turnDistance() { return sp.getInt("turn_distance", 200); }
    public void setTurnDistance(int v) { sp.edit().putInt("turn_distance", v).apply(); }

    /** 到达/导航结束提醒。 */
    public boolean arriveEnabled() { return sp.getBoolean("arrive_enabled", true); }
    public void setArriveEnabled(boolean v) { sp.edit().putBoolean("arrive_enabled", v).apply(); }

    /** 导航开始提醒(默认关，避免每次上车先震一下)。 */
    public boolean startEnabled() { return sp.getBoolean("start_enabled", false); }
    public void setStartEnabled(boolean v) { sp.edit().putBoolean("start_enabled", v).apply(); }

    /**
     * 关键词提醒(拥堵/事故/施工/拍照/测速等)。
     * 手机高德的通知通常不含电子眼字段，只能从原文里碰运气，默认关。
     */
    public boolean keywordEnabled() { return sp.getBoolean("keyword_enabled", false); }
    public void setKeywordEnabled(boolean v) { sp.edit().putBoolean("keyword_enabled", v).apply(); }

    /** 电子眼提醒，仅在高德车机版广播源可用时生效。 */
    public boolean cameraEnabled() { return sp.getBoolean("camera_enabled", true); }
    public void setCameraEnabled(boolean v) { sp.edit().putBoolean("camera_enabled", v).apply(); }

    /** 超速提醒，仅在高德车机版广播源可用时生效。 */
    public boolean overspeedEnabled() { return sp.getBoolean("overspeed_enabled", true); }
    public void setOverspeedEnabled(boolean v) { sp.edit().putBoolean("overspeed_enabled", v).apply(); }

    /** 持续超速多少秒才提醒（默认 3 秒，避免 GPS 抖动）。 */
    public int overspeedSeconds() { return sp.getInt("overspeed_seconds", 3); }
    public void setOverspeedSeconds(int v) { sp.edit().putInt("overspeed_seconds", v).apply(); }

    /** 没有道路限速数据时，是否用固定阈值兜底。 */
    public boolean fixedLimitEnabled() { return sp.getBoolean("fixed_limit_enabled", false); }
    public void setFixedLimitEnabled(boolean v) { sp.edit().putBoolean("fixed_limit_enabled", v).apply(); }

    /** 固定限速阈值 km/h（40–120）。 */
    public int fixedLimitKmh() { return sp.getInt("fixed_limit_kmh", 60); }
    public void setFixedLimitKmh(int v) { sp.edit().putInt("fixed_limit_kmh", v).apply(); }

    /** 是否在停靠点前再补一次 50 米提醒。 */
    public boolean nearEnabled() { return sp.getBoolean("near_enabled", false); }
    public void setNearEnabled(boolean v) { sp.edit().putBoolean("near_enabled", v).apply(); }

    public void resetDefaults() {
        sp.edit()
                .putBoolean("turn_enabled", true)
                .putInt("turn_distance", 200)
                .putBoolean("arrive_enabled", true)
                .putBoolean("start_enabled", false)
                .putBoolean("keyword_enabled", false)
                .putBoolean("camera_enabled", true)
                .putBoolean("overspeed_enabled", true)
                .putInt("overspeed_seconds", 3)
                .putBoolean("fixed_limit_enabled", false)
                .putInt("fixed_limit_kmh", 60)
                .putBoolean("near_enabled", false)
                .apply();
    }
}
