package com.amapbandalert;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 环形日志缓冲: 记录广播数据/预警事件/错误, 供日志页实时显示。
 * 线程安全: 所有写入经 synchronized。
 */
public final class AlertLogger {

    public static final int MAX_LINES = 300;
    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private static Listener listener;

    public interface Listener {
        void onLogAppended(String line);
    }

    public static void setListener(Listener l) { listener = l; }

    public enum Tag { BROADCAST, ALERT, ERROR, INFO }

    public static synchronized void log(Tag tag, String msg) {
        String level = tag == Tag.ERROR ? "E" : tag == Tag.ALERT ? "!" : tag == Tag.BROADCAST ? "D" : "I";
        String line = FMT.format(new Date()) + " [" + level + "] " + msg;
        BUFFER.addLast(line);
        while (BUFFER.size() > MAX_LINES) BUFFER.removeFirst();
        if (listener != null) listener.onLogAppended(line);
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : BUFFER) sb.append(line).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() { BUFFER.clear(); }
}
