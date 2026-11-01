package com.freddyq.diplay.colorprobe;

import android.app.Activity;
import android.os.*;
import android.widget.*;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Manual diagnostic. Launch does not connect, change settings or write any vehicle property. */
public final class MainActivity extends Activity {
    private HandlerThread thread;
    private Handler worker;
    private NativeColorAccess client;
    private TextView history;
    private Button first, second, restore;
    private final StringBuilder lines = new StringBuilder();
    private boolean destroyed;
    private int generation;
    private boolean connected;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,20,24,20); scroll.addView(root); setContentView(scroll);
        TextView title = new TextView(this); title.setTextSize(24); title.setText("原车封面色通道验证 0.2"); root.addView(title);
        TextView help = new TextView(this); help.setText("驻车手动试验。保留现有灯光/律动设置；启动不连接、不写入。\n仅测试当前颜色属性，读取成功后才能写色号4/48或恢复原色。它们是原厂已使用的色号，不预言具体RGB。此包不做自动封面换色。"); root.addView(help);
        button(root, "① 连接检查并读取原色", this::connect);
        first = button(root, "② 只写测试色号4", () -> write(4));
        second = button(root, "③ 只写测试色号48", () -> write(48));
        restore = button(root, "④ 恢复本次读取的原色", () -> { if (client != null && client.original() != null) write(client.original()); });
        setReady(false); button(root, "退出（不自动写入）", this::finish);
        history = new TextView(this); history.setTextIsSelectable(true); root.addView(history);
        log("启动；尚未连接或发送。日志保存在本App私有目录。");
    }
    private Button button(LinearLayout root, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); root.addView(button); button.setOnClickListener(view -> action.run()); return button;
    }
    private void connect() {
        closeClient(); setReady(false);
        final int ticket = generation;
        thread = new HandlerThread("cover-color-probe");
        thread.setUncaughtExceptionHandler((failed, error) -> main.post(() -> {
            if (generation == ticket && !destroyed) {
                log("SDK后台异常，停止本次连接：" + NativeColorAccess.describe(error)); closeClient(); setReady(false);
            }
        }));
        thread.start(); worker = new Handler(thread.getLooper());
        final NativeColorAccess next = new NativeColorAccess(new NativeColorAccess.Listener() {
            public void log(String text) { MainActivity.this.log(text); }
            public void ready(boolean ready) { runOnUiThread(() -> {
                if (generation == ticket) { connected = ready; setReady(ready); }
            }); }
        });
        client = next;
        final Handler handler = worker;
        handler.post(() -> { try { next.connect(getApplicationContext(), handler); } catch (Exception | LinkageError error) { log("连接失败：" + NativeColorAccess.describe(error)); } });
        main.postDelayed(() -> {
            if (!destroyed && generation == ticket && !connected) {
                log("10秒未取得可读颜色；停止本次SDK等待，不执行写入"); closeClient(); setReady(false);
            }
        }, 10000);
    }
    private void write(int value) {
        final NativeColorAccess current = client; final Handler handler = worker;
        if (current == null || handler == null) return;
        handler.post(() -> {
            if (client != current || destroyed) return;
            try {
                current.write(value);
                for (long delay : new long[]{0,500,2000}) handler.postDelayed(() -> {
                    if (client != current || destroyed) return;
                    try { log("回读 +" + delay + "ms = " + current.read() + "；实体颜色需目视确认"); }
                    catch (Exception error) { log("回读失败：" + NativeColorAccess.describe(error)); }
                }, delay);
            } catch (Exception error) { log("写入失败：" + NativeColorAccess.describe(error)); }
        });
    }
    private void setReady(boolean ready) {
        if (destroyed) return; first.setEnabled(ready); second.setEnabled(ready); restore.setEnabled(ready);
    }
    private synchronized void log(String text) {
        String row = System.currentTimeMillis() + " " + text + "\n";
        lines.append(row); if (lines.length() > 30000) lines.delete(0, lines.length() - 24000);
        try {
            if (getFileStreamPath("probe.log").length() > 131072) deleteFile("probe.log");
            try (FileOutputStream out = openFileOutput("probe.log", MODE_APPEND)) { out.write(row.getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) {}
        String shown = lines.toString(); runOnUiThread(() -> { if (!destroyed && history != null) history.setText(shown); });
    }
    private void closeClient() {
        generation++; connected = false;
        if (client != null) client.close(); client = null;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely(); worker = null; thread = null;
    }
    @Override protected void onDestroy() { destroyed = true; main.removeCallbacksAndMessages(null); closeClient(); super.onDestroy(); }
}
