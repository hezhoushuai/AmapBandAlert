package com.amapbandalert;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class LogActivity extends Activity {

    private TextView logView;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        logView = findViewById(R.id.log_text);
        scroll = findViewById(R.id.scroll);
        Button copyBtn = findViewById(R.id.btn_copy);
        Button clearBtn = findViewById(R.id.btn_clear);
        Button backBtn = findViewById(R.id.btn_back);

        logView.setText(AlertLogger.dump());
        scrollToBottom();

        AlertLogger.setListener(line -> runOnUiThread(() -> {
            logView.append(line + "\n");
            scrollToBottom();
        }));

        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("log", AlertLogger.dump()));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        });
        clearBtn.setOnClickListener(v -> {
            AlertLogger.clear();
            logView.setText("");
        });
        backBtn.setOnClickListener(v -> finish());
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AlertLogger.setListener(null);
    }
}
