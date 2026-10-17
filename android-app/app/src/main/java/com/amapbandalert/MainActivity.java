package com.amapbandalert;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ToggleButton;

public class MainActivity extends Activity implements AlertEngine.Listener {

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        AlertNotifier.ensureChannels(this);

        statusText = findViewById(R.id.status);
        Button testBtn = findViewById(R.id.btn_test);
        Button startBtn = findViewById(R.id.btn_start);
        SeekBar distBar = findViewById(R.id.bar_dist);
        final TextView distLabel = findViewById(R.id.label_dist);
        ToggleButton cameraToggle = findViewById(R.id.toggle_camera);
        ToggleButton speedToggle = findViewById(R.id.toggle_speed);

        testBtn.setOnClickListener(v -> {
            AlertNotifier.ensureChannels(this);
            AlertNotifier.notifyTest(this);
            statusText.setText("测试通知已发出, 看手环是否震动");
        });

        startBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, MonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            statusText.setText("监听服务已启动");
        });

        distBar.setMax(4); // 100/200/300/500
        distBar.setProgress(2);
        distLabel.setText("预警距离: 300m");
        distBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int[] d = {100, 200, 300, 500};
                distLabel.setText("预警距离: " + d[p] + "m");
                AlertEngine.get().setCameraAlertDistance(d[p]);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        cameraToggle.setChecked(true);
        cameraToggle.setOnCheckedChangeListener((b, checked) -> AlertEngine.get().setCameraEnabled(checked));
        speedToggle.setChecked(true);
        speedToggle.setOnCheckedChangeListener((b, checked) -> AlertEngine.get().setOverspeedEnabled(checked));

        AlertEngine.get().setListener(this);
    }

    @Override
    public void onCameraAlert(int distM, int limitSpeed, int curSpeed) {
        runOnUiThread(() -> {
            AlertNotifier.notifyCamera(this, distM, limitSpeed, curSpeed);
            statusText.setText("⚠️ 触发: 限速拍照 " + distM + "m 限" + limitSpeed);
        });
    }

    @Override
    public void onOverspeed(int curSpeed, int limitSpeed) {
        runOnUiThread(() -> {
            AlertNotifier.notifyOverspeed(this, curSpeed, limitSpeed);
            statusText.setText("🚨 触发: 超速 " + curSpeed + "/" + limitSpeed);
        });
    }
}
