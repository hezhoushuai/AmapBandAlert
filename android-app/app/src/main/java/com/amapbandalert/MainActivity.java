package com.amapbandalert;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

public class MainActivity extends Activity implements AlertEngine.Listener {

    private TextView statusCard;
    private TextView lastAlertText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        AlertNotifier.ensureChannels(this);
        AlertEngine.get().setListener(this);

        statusCard = findViewById(R.id.status_card);
        lastAlertText = findViewById(R.id.last_alert);
        Button startBtn = findViewById(R.id.btn_start);
        Button testBtn = findViewById(R.id.btn_test);
        Button logBtn = findViewById(R.id.btn_log);
        SeekBar distBar = findViewById(R.id.bar_dist);
        TextView distLabel = findViewById(R.id.label_dist);
        ToggleButton cameraToggle = findViewById(R.id.toggle_camera);
        ToggleButton speedToggle = findViewById(R.id.toggle_speed);

        startBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, MonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            statusCard.setText("● 监听中");
            Toast.makeText(this, "监听已启动", Toast.LENGTH_SHORT).show();
        });

        testBtn.setOnClickListener(v -> AlertEngine.get().fireTestAlert());

        logBtn.setOnClickListener(v ->
                startActivity(new Intent(this, LogActivity.class)));

        int[] dists = {100, 200, 300, 500};
        distBar.setMax(4);
        distBar.setProgress(2);
        distBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean b) {
                distLabel.setText("电子眼预警距离: " + dists[p] + "m");
                AlertEngine.get().setCameraAlertDistance(dists[p]);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        cameraToggle.setOnCheckedChangeListener((b, on) ->
                AlertEngine.get().setCameraEnabled(on));
        speedToggle.setOnCheckedChangeListener((b, on) ->
                AlertEngine.get().setOverspeedEnabled(on));

        updateStatus("待机 · 点启动开始监听");
    }

    private void updateStatus(String s) {
        statusCard.setText("● " + s);
    }

    @Override
    public void onCameraAlert(int distM, int limitSpeed, int curSpeed) {
        runOnUiThread(() -> {
            AlertNotifier.notifyCamera(this, distM, limitSpeed, curSpeed);
            lastAlertText.setText("最近预警: 限速拍照 " + distM + "m" +
                    (limitSpeed > 0 ? " · 限" + limitSpeed : ""));
            updateStatus("⚠️ 已触发电子眼预警");
        });
    }

    @Override
    public void onOverspeed(int curSpeed, int limitSpeed) {
        runOnUiThread(() -> {
            AlertNotifier.notifyOverspeed(this, curSpeed, limitSpeed);
            lastAlertText.setText("最近预警: 超速 " + curSpeed + "/" + limitSpeed);
        });
    }
}
