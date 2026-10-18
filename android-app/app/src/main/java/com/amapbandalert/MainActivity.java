package com.amapbandalert;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final int[] DISTANCES = {100, 150, 200, 300, 500};
    private static final int[] FIXED_LIMITS = {20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120};
    private static final int REQ_NOTIFICATIONS = 101;
    private static final int REQ_LOCATION = 102;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::refreshUi;

    private TextView statusCard;
    private TextView stateText;
    private TextView sourceText;
    private TextView rawText;
    private TextView labelDistance;
    private TextView labelFixedLimit;
    private SeekBar barDistance;
    private SeekBar barFixedLimit;
    private Button btnStart;
    private Button btnListener;
    private Button btnNotifPerm;
    private Button btnBattery;
    private Button btnLocation;
    private Button btnAccessibility;

    private Switch toggleTurn;
    private Switch toggleNear;
    private Switch toggleArrive;
    private Switch toggleStart;
    private Switch toggleKeyword;
    private Switch toggleCamera;
    private Switch toggleSpeed;
    private Switch toggleFixedLimit;

    private boolean updatingSwitches;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AlertNotifier.ensureChannels(this);
        AlertEngine.get().init(this);
        setContentView(R.layout.activity_main);

        statusCard = findViewById(R.id.status_card);
        stateText = findViewById(R.id.state_text);
        sourceText = findViewById(R.id.source_text);
        rawText = findViewById(R.id.raw_text);
        labelDistance = findViewById(R.id.label_distance);
        labelFixedLimit = findViewById(R.id.label_fixed_limit);
        barDistance = findViewById(R.id.bar_distance);
        barFixedLimit = findViewById(R.id.bar_fixed_limit);
        btnStart = findViewById(R.id.btn_start);
        btnListener = findViewById(R.id.btn_listener);
        btnNotifPerm = findViewById(R.id.btn_notif_perm);
        btnBattery = findViewById(R.id.btn_battery);
        btnLocation = findViewById(R.id.btn_location);
        btnAccessibility = findViewById(R.id.btn_accessibility);

        toggleTurn = findViewById(R.id.toggle_turn);
        toggleNear = findViewById(R.id.toggle_near);
        toggleArrive = findViewById(R.id.toggle_arrive);
        toggleStart = findViewById(R.id.toggle_start);
        toggleKeyword = findViewById(R.id.toggle_keyword);
        toggleCamera = findViewById(R.id.toggle_camera);
        toggleSpeed = findViewById(R.id.toggle_speed);
        toggleFixedLimit = findViewById(R.id.toggle_fixed_limit);

        wire();
        syncSwitches();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AlertEngine.get().setUiListener(this::refreshUi);
        ui.removeCallbacks(tick);
        ui.post(tick);
        Prefs prefs = Prefs.get(this);
        if (prefs.enabled() && !MonitorService.isRunning()) {
            try {
                MonitorService.start(this);
            } catch (RuntimeException e) {
                AlertLogger.log(AlertLogger.Tag.ERROR, "恢复前台服务失败: " + e.getMessage());
            }
        }
        refreshUi();
    }

    @Override
    protected void onPause() {
        AlertEngine.get().setUiListener(null);
        ui.removeCallbacks(tick);
        super.onPause();
    }

    private void wire() {
        btnStart.setOnClickListener(v -> toggleMonitoring());
        findViewById(R.id.btn_test).setOnClickListener(v -> {
            if (!hasPostNotifications()) {
                requestNotifications();
                Toast.makeText(this, "请先允许通知，再测试手环链路", Toast.LENGTH_LONG).show();
                return;
            }
            AlertEngine.get().fireTestNotification();
            Toast.makeText(this, "已发送测试通知", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_test_turn).setOnClickListener(v -> {
            AlertEngine.get().fireTestTurn();
            Toast.makeText(this, "已模拟一次左转提醒", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_test_speed).setOnClickListener(v -> {
            AlertEngine.get().fireTestOverspeed();
            Toast.makeText(this, "已模拟超速 82/60", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_scan).setOnClickListener(v -> {
            NavigationNotificationListener.dumpActive();
            Toast.makeText(this, "已把高德通知原文写入运行日志", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_capture_ui).setOnClickListener(v -> {
            if (!hasAccessibilityAccess()) {
                Toast.makeText(this, "先开启无障碍服务，再记录界面文字", Toast.LENGTH_LONG).show();
                openAccessibilitySettings();
                return;
            }
            if (!Prefs.get(this).enabled()) {
                Toast.makeText(this, "请先启动监听", Toast.LENGTH_SHORT).show();
                return;
            }
            AmapAccessibilityService.startCapture(60);
            Toast.makeText(this, "60 秒内切到高德导航，结束后查看运行日志", Toast.LENGTH_LONG).show();
        });
        findViewById(R.id.btn_log).setOnClickListener(v ->
                startActivity(new Intent(this, LogActivity.class)));

        btnListener.setOnClickListener(v -> openNotificationAccess());
        btnNotifPerm.setOnClickListener(v -> requestNotifications());
        btnBattery.setOnClickListener(v -> requestIgnoreBattery());
        btnLocation.setOnClickListener(v -> {
            if (hasLocationPermission()) {
                MonitorService.refreshLocation();
                Toast.makeText(this, "定位已开启，GPS 车速可用", Toast.LENGTH_SHORT).show();
            } else {
                requestLocation();
            }
        });
        btnAccessibility.setOnClickListener(v -> {
            if (hasAccessibilityAccess()) {
                Toast.makeText(this, "已开启；可点“记录高德界面”诊断", Toast.LENGTH_SHORT).show();
            } else {
                openAccessibilitySettings();
            }
        });

        barDistance.setMax(DISTANCES.length - 1);
        int index = indexOf(DISTANCES, Prefs.get(this).turnDistance());
        barDistance.setProgress(index, false);
        updateDistanceLabel(DISTANCES[index]);
        barDistance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int meters = DISTANCES[Math.max(0, Math.min(DISTANCES.length - 1, progress))];
                updateDistanceLabel(meters);
                if (fromUser) Prefs.get(MainActivity.this).setTurnDistance(meters);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        barFixedLimit.setMax(FIXED_LIMITS.length - 1);
        int fixedIndex = indexOf(FIXED_LIMITS, Prefs.get(this).fixedLimitKmh());
        barFixedLimit.setProgress(fixedIndex, false);
        updateFixedLimitLabel(FIXED_LIMITS[fixedIndex]);
        barFixedLimit.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int kmh = FIXED_LIMITS[Math.max(0, Math.min(FIXED_LIMITS.length - 1, progress))];
                updateFixedLimitLabel(kmh);
                if (fromUser) {
                    Prefs.get(MainActivity.this).setFixedLimitKmh(kmh);
                    AlertEngine.get().refreshOverspeed();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        toggleTurn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingSwitches) Prefs.get(this).setTurnEnabled(isChecked);
        });
        toggleNear.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingSwitches) Prefs.get(this).setNearEnabled(isChecked);
        });
        toggleArrive.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingSwitches) Prefs.get(this).setArriveEnabled(isChecked);
        });
        toggleStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingSwitches) Prefs.get(this).setStartEnabled(isChecked);
        });
        toggleKeyword.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingSwitches) Prefs.get(this).setKeywordEnabled(isChecked);
        });
        toggleCamera.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingSwitches) Prefs.get(this).setCameraEnabled(isChecked);
        });
        toggleSpeed.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingSwitches) {
                Prefs.get(this).setOverspeedEnabled(isChecked);
                AlertEngine.get().refreshOverspeed();
            }
        });
        toggleFixedLimit.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingSwitches) {
                Prefs.get(this).setFixedLimitEnabled(isChecked);
                barFixedLimit.setEnabled(isChecked);
                labelFixedLimit.setAlpha(isChecked ? 1f : 0.5f);
                AlertEngine.get().refreshOverspeed();
            }
        });
    }

    private void toggleMonitoring() {
        Prefs prefs = Prefs.get(this);
        if (prefs.enabled() || MonitorService.isRunning()) {
            prefs.setEnabled(false);
            AlertEngine.get().pause();
            MonitorService.stop(this);
            AlertLogger.log(AlertLogger.Tag.INFO, "用户停止监听");
        } else {
            if (!hasPostNotifications()) {
                requestNotifications();
                Toast.makeText(this, "请先允许通知，再点一次“启动监听”", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.setEnabled(true);
            try {
                MonitorService.start(this);
                AlertLogger.log(AlertLogger.Tag.INFO, "用户启动监听");
            } catch (RuntimeException e) {
                AlertLogger.log(AlertLogger.Tag.ERROR, "启动前台服务失败: " + e.getMessage());
                Toast.makeText(this, "启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                prefs.setEnabled(false);
            }
            if (!hasNotificationAccess()) {
                Toast.makeText(this, "还差一步：请授予通知使用权，才能读取高德导航", Toast.LENGTH_LONG).show();
                openNotificationAccess();
            } else if (!hasLocationPermission() && prefs.overspeedEnabled()) {
                Toast.makeText(this, "未授权定位：超速只能用高德车机版广播，建议点“定位权限”", Toast.LENGTH_LONG).show();
            }
        }
        refreshUi();
    }

    private void syncSwitches() {
        Prefs p = Prefs.get(this);
        updatingSwitches = true;
        toggleTurn.setChecked(p.turnEnabled());
        toggleNear.setChecked(p.nearEnabled());
        toggleArrive.setChecked(p.arriveEnabled());
        toggleStart.setChecked(p.startEnabled());
        toggleKeyword.setChecked(p.keywordEnabled());
        toggleCamera.setChecked(p.cameraEnabled());
        toggleSpeed.setChecked(p.overspeedEnabled());
        toggleFixedLimit.setChecked(p.fixedLimitEnabled());
        barFixedLimit.setEnabled(p.fixedLimitEnabled());
        labelFixedLimit.setAlpha(p.fixedLimitEnabled() ? 1f : 0.5f);
        int idx = indexOf(FIXED_LIMITS, p.fixedLimitKmh());
        if (barFixedLimit.getProgress() != idx) barFixedLimit.setProgress(idx, false);
        updateFixedLimitLabel(FIXED_LIMITS[idx]);
        updatingSwitches = false;
    }

    private void refreshUi() {
        boolean access = hasNotificationAccess();
        boolean post = hasPostNotifications();
        boolean battery = ignoresBattery();
        boolean location = hasLocationPermission();
        boolean accessibility = hasAccessibilityAccess();
        boolean running = MonitorService.isRunning();
        boolean enabled = Prefs.get(this).enabled();

        statusCard.setText(running ? "● 监听中" : (enabled ? "● 服务未运行" : "● 未启动"));
        statusCard.setTextColor(Color.parseColor(running ? "#4CAF50" : "#FFB74D"));
        stateText.setText(AlertEngine.get().statusLine());
        sourceText.setText(AlertEngine.get().sourceLine() + "\n" + AlertEngine.get().detailLine());
        rawText.setText(AlertEngine.get().rawLine());

        btnStart.setText(enabled ? "停止监听" : "启动监听");
        btnStart.setBackgroundTintList(ColorStateList.valueOf(
                Color.parseColor(enabled ? "#B3261E" : "#2E7D32")));

        btnListener.setText(access ? "通知使用权：已开启" : "通知使用权：去开启（必需）");
        btnListener.setBackgroundTintList(ColorStateList.valueOf(
                Color.parseColor(access ? "#2E7D32" : "#B26A00")));

        if (Build.VERSION.SDK_INT >= 33) {
            btnNotifPerm.setText(post ? "通知权限：已允许" : "通知权限：去允许（必需）");
            btnNotifPerm.setBackgroundTintList(ColorStateList.valueOf(
                    Color.parseColor(post ? "#2E7D32" : "#B26A00")));
        } else {
            btnNotifPerm.setText("通知权限：系统默认允许");
            btnNotifPerm.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#37474F")));
        }

        btnBattery.setText(battery ? "电池优化：已忽略（推荐）" : "电池优化：去设置（骑行保活）");
        btnBattery.setBackgroundTintList(ColorStateList.valueOf(
                Color.parseColor(battery ? "#2E7D32" : "#37474F")));

        btnLocation.setText(location ? "定位权限：已开启（GPS 车速）" : "定位权限：去允许（超速需要）");
        btnLocation.setBackgroundTintList(ColorStateList.valueOf(
                Color.parseColor(location ? "#2E7D32" : "#B26A00")));

        btnAccessibility.setText(accessibility ? "高德界面限速：已开启" : "高德界面限速：去开启（实验性）");
        btnAccessibility.setBackgroundTintList(ColorStateList.valueOf(
                Color.parseColor(accessibility ? "#2E7D32" : "#37474F")));

        syncSwitches();
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 1000);
    }

    private void updateDistanceLabel(int meters) {
        labelDistance.setText(String.format(Locale.CHINA, "转向提醒距离：%d 米", meters));
    }

    private void updateFixedLimitLabel(int kmh) {
        labelFixedLimit.setText(String.format(Locale.CHINA,
                "固定限速阈值：%d km/h（没有道路限速时使用）", kmh));
    }

    private int indexOf(int[] values, int value) {
        int best = 0;
        int diff = Integer.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            int d = Math.abs(values[i] - value);
            if (d < diff) {
                diff = d;
                best = i;
            }
        }
        return best;
    }

    private boolean hasNotificationAccess() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            return flat != null && flat.contains(getPackageName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean hasPostNotifications() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAccessibilityAccess() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    "enabled_accessibility_services");
            if (flat == null) return false;
            return flat.contains(getPackageName() + "/" + AmapAccessibilityService.class.getName())
                    || (flat.contains(getPackageName()) && flat.contains("AmapAccessibilityService"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !hasPostNotifications()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void requestLocation() {
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, REQ_LOCATION);
    }

    private boolean ignoresBattery() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < 23 || ignoresBattery()) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
                Toast.makeText(this, "请在系统设置中允许后台运行", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException ignored) {
                Toast.makeText(this, "请到系统设置 → 通知 → 通知使用权中开启", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException e) {
            Toast.makeText(this, "请到系统设置 → 无障碍中开启", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            refreshUi();
            if (!hasPostNotifications()) {
                Toast.makeText(this, "没有通知权限，手环收不到提醒", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                MonitorService.refreshLocation();
                Toast.makeText(this, "定位已开启，GPS 车速可用", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "没有定位权限，超速只能靠车机版广播", Toast.LENGTH_LONG).show();
            }
            refreshUi();
        }
    }
}
