package com.netpump.engineering;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;

/** Focused FTP download tester, with a simple reference-compatible settings form. */
public final class FtpActivity extends Activity {
    private static final int BG = Color.rgb(10, 17, 28), CARD = Color.rgb(24, 35, 50);
    private static final int FG = Color.rgb(241, 249, 255), MUTED = Color.rgb(151, 171, 191);
    private static final int ACCENT = Color.rgb(74, 164, 255);
    private static final int SAVE_CSV = 41;
    private EditText host, port, user, password, file, threads, seconds, count, interval, end, delay;
    private CheckBox cyclic, delayCollection;
    private TextView rateText, summary, status, history, networkText;
    private Button start, stop;
    private SpeedChart chart;
    private BroadcastReceiver receiver;
    private boolean registered;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildScreen();
        restoreSettings();
        refreshHistory();
        updateNetwork();
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                boolean running = intent.getBooleanExtra("running", false);
                double current = intent.getDoubleExtra("rate", 0);
                rateText.setText(format(current));
                status.setText(intent.getStringExtra("status") + (running ?
                        " · 第 " + intent.getIntExtra("round", 1) + " 轮 · 剩余 " + intent.getIntExtra("remain", 0) + " 秒" : ""));
                summary.setText(String.format(Locale.CHINA,
                        "平均 %.2f Mbps     峰值 %.2f Mbps\n已接收 %.2f GB     活跃线程 %d",
                        intent.getDoubleExtra("avg", 0), intent.getDoubleExtra("peak", 0),
                        intent.getLongExtra("bytes", 0) / 1e9, intent.getIntExtra("active", 0)));
                if (running) chart.push((float) current);
                start.setEnabled(!running);
                stop.setEnabled(running);
                if (!running) refreshHistory();
            }
        };
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 55);
    }
    @Override public void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, new IntentFilter(FtpService.EVENT), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, new IntentFilter(FtpService.EVENT));
        registered = true;
        status.setText(FtpService.lastStatus);
        rateText.setText(format(FtpService.lastRate));
        summary.setText(String.format(Locale.CHINA, "平均 %.2f Mbps     峰值 %.2f Mbps\n已接收 %.2f GB     活跃线程 %d",
                FtpService.lastAvg, FtpService.lastPeak, FtpService.lastBytes / 1e9, FtpService.lastActive));
        start.setEnabled(!FtpService.running); stop.setEnabled(FtpService.running);
        updateNetwork();
        refreshHistory();
    }
    @Override public void onStop() {
        if (registered) { unregisterReceiver(receiver); registered = false; }
        super.onStop();
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius));
        return d;
    }
    private LinearLayout col() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private EditText input(String hint, boolean numeric) {
        EditText e = new EditText(this);
        e.setSingleLine(true); e.setHint(hint); e.setTextColor(FG); e.setHintTextColor(MUTED); e.setTextSize(14);
        e.setPadding(dp(12), dp(9), dp(12), dp(9));
        e.setBackground(shape(Color.rgb(38, 52, 69), 9));
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }
    private LinearLayout box() {
        LinearLayout l = col(); l.setPadding(dp(15), dp(16), dp(15), dp(16)); l.setBackground(shape(CARD, 15)); return l;
    }
    private void gap(LinearLayout p, int height) { p.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private LinearLayout field(String label, EditText value) {
        LinearLayout l = col(); l.addView(text(label, 12, MUTED, false)); gap(l, 4);
        l.addView(value, new LinearLayout.LayoutParams(-1, dp(45))); return l;
    }
    private Button button(String caption, int color) {
        Button b = new Button(this); b.setText(caption); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setTextSize(14); b.setBackground(shape(color, 11)); return b;
    }
    private void pair(LinearLayout parent, String a, EditText left, String b, EditText right) {
        LinearLayout r = row();
        r.addView(field(a, left), new LinearLayout.LayoutParams(0, -2, 1));
        View spacer = new View(this); r.addView(spacer, new LinearLayout.LayoutParams(dp(10), 1));
        r.addView(field(b, right), new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(r); gap(parent, 12);
    }
    private CheckBox check(String title) {
        CheckBox b = new CheckBox(this); b.setText(title); b.setTextColor(FG); b.setTextSize(13);
        b.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT)); return b;
    }
    private void buildScreen() {
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(BG); scroll.setFillViewport(true);
        LinearLayout root = col(); root.setPadding(dp(16), dp(24), dp(16), dp(30));
        scroll.addView(root); setContentView(scroll);
        root.addView(text("NETPUMP   FTP D", 25, FG, true)); gap(root, 4);
        root.addView(text("4G / 5G · FTP 多线程下行灌包", 13, MUTED, false)); gap(root, 16);

        LinearLayout board = box(); root.addView(board);
        status = text("等待开始", 13, ACCENT, true); board.addView(status); gap(board, 13);
        TextView title = text("实时下行速率  DL", 13, MUTED, false); board.addView(title);
        LinearLayout hero = row(); rateText = text("0.00", 43, FG, true);
        hero.addView(rateText); hero.addView(text("   Mbps", 15, ACCENT, true)); board.addView(hero);
        chart = new SpeedChart(this); board.addView(chart, new LinearLayout.LayoutParams(-1, dp(120))); gap(board, 8);
        summary = text("平均 0.00 Mbps     峰值 0.00 Mbps\n已接收 0.00 GB     活跃线程 0", 13, FG, false);
        board.addView(summary); gap(board, 9);
        networkText = text("网络检测中", 12, MUTED, false); board.addView(networkText); gap(root, 14);

        LinearLayout settings = box(); root.addView(settings);
        settings.addView(text("FTP 连接 / FtpD", 17, FG, true)); gap(settings, 11);
        host = input("FTP 服务器地址", false); port = input("8108", true);
        pair(settings, "Host · 服务器", host, "Port · 端口", port);
        user = input("用户名", false); password = input("在手机上输入密码", false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pair(settings, "User", user, "Psd · 密码（不保存）", password);
        file = input("/download/20GB.ZIP", false);
        settings.addView(field("Download file · 远程文件路径", file)); gap(settings, 11);
        settings.addView(text("FtpType   FTP      ·      Passive   true (EPSV/PASV)", 12, MUTED, false));
        gap(settings, 15);
        settings.addView(text("灌包任务", 17, FG, true)); gap(settings, 12);
        threads = input("10", true); seconds = input("120", true);
        pair(settings, "ThreadNum · 并发线程", threads, "Single Max Time · 秒", seconds);
        count = input("1", true); interval = input("10", true);
        pair(settings, "Count · 轮次", count, "Inter · 轮间间隔(秒)", interval);
        delay = input("5", true); end = input("5", true);
        pair(settings, "Delay Time · 秒", delay, "End · 结束等待(秒)", end);
        cyclic = check("Cyclic Transfer · 进行多轮测试（按 Count）");
        delayCollection = check("Delay Collection · 延时开始统计（按 Delay Time）");
        settings.addView(cyclic); settings.addView(delayCollection); gap(settings, 8);
        settings.addView(text("下载数据直接丢弃，不占用手机存储。FTP 明文传输，请只连接授权的测试服务器。", 12, MUTED, false));
        gap(settings, 15);
        LinearLayout controls = row();
        start = button("▶ 开始灌包", Color.rgb(20, 135, 123));
        stop = button("■ 停止", Color.rgb(151, 67, 80));
        stop.setEnabled(false);
        controls.addView(start, new LinearLayout.LayoutParams(0, dp(48), 2));
        controls.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        controls.addView(stop, new LinearLayout.LayoutParams(0, dp(48), 1));
        settings.addView(controls); gap(settings, 9);
        Button probe = button("检测 FTP 登录 / 检查文件", Color.rgb(43, 72, 98));
        settings.addView(probe, new LinearLayout.LayoutParams(-1, dp(43)));
        start.setOnClickListener(v -> promptStart());
        stop.setOnClickListener(v -> {
            startService(new Intent(this, FtpService.class).setAction(FtpService.STOP));
            status.setText("正在停止…");
        });
        probe.setOnClickListener(v -> runProbe(probe));
        gap(root, 14);
        LinearLayout records = box(); root.addView(records);
        LinearLayout header = row();
        header.addView(text("FTP 测试历史", 17, FG, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button export = button("导出 CSV", Color.rgb(44, 76, 105));
        header.addView(export, new LinearLayout.LayoutParams(dp(100), dp(40))); records.addView(header);
        gap(records, 12); history = text("暂无记录", 12, MUTED, false); records.addView(history);
        export.setOnClickListener(v -> {
            Intent fileChooser = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            fileChooser.addCategory(Intent.CATEGORY_OPENABLE);
            fileChooser.setType("text/csv");
            fileChooser.putExtra(Intent.EXTRA_TITLE, "NetPump-FTP-Results.csv");
            startActivityForResult(fileChooser, SAVE_CSV);
        });
        gap(root, 14);
        Button oldMode = button("切换到 TCP / UDP 自有协议灌包", Color.rgb(46, 66, 88));
        root.addView(oldMode, new LinearLayout.LayoutParams(-1, dp(46)));
        oldMode.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        gap(root, 10);
        root.addView(text("大量下行灌包将消耗数据流量；请关注运营商套餐、服务器负载与测试许可。", 12, MUTED, false));
    }
    private void restoreSettings() {
        SharedPreferences p = getSharedPreferences("ftp_settings", MODE_PRIVATE);
        // The server address from the reference image is intentionally not published in source code.
        host.setText(p.getString("host", ""));
        port.setText(p.getString("port", "8108"));
        user.setText(p.getString("user", ""));
        file.setText(p.getString("file", "/download/20GB.ZIP"));
        threads.setText(p.getString("threads", "10"));
        seconds.setText(p.getString("seconds", "120"));
        count.setText(p.getString("count", "1"));
        interval.setText(p.getString("interval", "10"));
        end.setText(p.getString("end", "5"));
        delay.setText(p.getString("delay", "5"));
        cyclic.setChecked(p.getBoolean("cyclic", false));
        delayCollection.setChecked(p.getBoolean("delayCollection", false));
    }
    private static String safe(EditText edit) { return edit.getText().toString().trim(); }
    private int numeric(EditText edit, int min, int max) {
        int value = Integer.parseInt(safe(edit));
        if (value < min || value > max) throw new IllegalArgumentException("数值范围 " + min + "~" + max);
        return value;
    }
    private FtpRunner.Config readSettings() {
        FtpRunner.Config cfg = new FtpRunner.Config();
        cfg.host = safe(host); cfg.user = safe(user); cfg.password = password.getText().toString();
        cfg.remotePath = safe(file);
        if (cfg.host.isEmpty() || cfg.host.contains("/") || cfg.host.contains(" ") || cfg.host.contains("\n"))
            throw new IllegalArgumentException("请填写正确的服务器 Host");
        if (cfg.user.isEmpty() || cfg.user.contains("\n") || cfg.user.contains("\r"))
            throw new IllegalArgumentException("请填写 FTP 用户名");
        if (cfg.password.isEmpty()) throw new IllegalArgumentException("请输入 FTP 密码");
        if (cfg.password.contains("\n") || cfg.password.contains("\r")) throw new IllegalArgumentException("密码含非法换行");
        if (cfg.remotePath.isEmpty() || cfg.remotePath.contains("\n") || cfg.remotePath.contains("\r"))
            throw new IllegalArgumentException("请输入下载文件路径");
        cfg.port = numeric(port, 1, 65535);
        cfg.threads = numeric(threads, 1, 32);
        cfg.seconds = numeric(seconds, 1, 600);
        cfg.count = numeric(count, 1, 20);
        cfg.intervalSec = numeric(interval, 0, 300);
        cfg.endSec = numeric(end, 0, 60);
        cfg.delaySec = numeric(delay, 0, 60);
        cfg.cyclic = cyclic.isChecked(); cfg.delayCollection = delayCollection.isChecked();
        return cfg;
    }
    private void persist(FtpRunner.Config c) {
        getSharedPreferences("ftp_settings", MODE_PRIVATE).edit()
                .putString("host", c.host).putString("port", String.valueOf(c.port))
                .putString("user", c.user).putString("file", c.remotePath)
                .putString("threads", String.valueOf(c.threads)).putString("seconds", String.valueOf(c.seconds))
                .putString("count", String.valueOf(c.count)).putString("interval", String.valueOf(c.intervalSec))
                .putString("end", String.valueOf(c.endSec)).putString("delay", String.valueOf(c.delaySec))
                .putBoolean("cyclic", c.cyclic).putBoolean("delayCollection", c.delayCollection).apply();
    }
    private void promptStart() {
        try {
            if (FtpService.running) { Toast.makeText(this, "FTP 测试已经运行", Toast.LENGTH_SHORT).show(); return; }
            FtpRunner.Config c = readSettings(); persist(c);
            final String net = networkType();
            new AlertDialog.Builder(this)
                    .setTitle("确认开始 FTP 灌包")
                    .setMessage("当前网络：" + net + "\n" + c.threads + " 路 FTP 下行下载，每轮 " + c.seconds
                            + " 秒。\n可能消耗数 GB 至数十 GB 手机流量，请确认服务器已授权且套餐充足。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始", (d, which) -> begin(c))
                    .show();
        } catch (Exception e) { toast("参数错误：" + e.getMessage()); }
    }
    private void begin(FtpRunner.Config cfg) {
        chart.clear(); status.setText("连接 FTP 服务端…"); start.setEnabled(false); stop.setEnabled(true);
        Intent i = new Intent(this, FtpService.class).setAction(FtpService.START);
        i.putExtra("host", cfg.host); i.putExtra("port", cfg.port);
        i.putExtra("user", cfg.user); i.putExtra("password", cfg.password);
        i.putExtra("file", cfg.remotePath); i.putExtra("threads", cfg.threads);
        i.putExtra("seconds", cfg.seconds); i.putExtra("count", cfg.count);
        i.putExtra("interval", cfg.intervalSec); i.putExtra("end", cfg.endSec);
        i.putExtra("delay", cfg.delaySec); i.putExtra("cyclic", cfg.cyclic);
        i.putExtra("delayCollection", cfg.delayCollection);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) { toast("无法启动后台服务：" + e.getMessage()); start.setEnabled(true); stop.setEnabled(false); }
    }
    private void runProbe(Button button) {
        try {
            FtpRunner.Config cfg = readSettings(); persist(cfg);
            button.setEnabled(false); status.setText("正在检测 FTP…");
            new Thread(() -> {
                String message;
                try { message = FtpRunner.probe(cfg); }
                catch (Exception e) { message = "登录或文件检查失败：" + e.getMessage(); }
                final String m = message;
                runOnUiThread(() -> { button.setEnabled(true); status.setText(m); toast(m); });
            }, "ftp-probe").start();
        } catch (Exception e) { toast("参数错误：" + e.getMessage()); }
    }
    private String networkType() {
        ConnectivityManager manager = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        Network network = manager.getActiveNetwork();
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        if (caps == null) return "未连接";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "移动数据（4G/5G）";
        return "其他";
    }
    private void updateNetwork() { networkText.setText("当前网络：" + networkType()); }
    private static String format(double n) { return String.format(Locale.US, n < 100 ? "%.2f" : "%.1f", n); }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }
    private void refreshHistory() {
        if (history == null) return;
        try {
            JSONArray list = new JSONArray(getSharedPreferences("ftp_history", MODE_PRIVATE).getString("items", "[]"));
            if (list.length() == 0) { history.setText("暂无记录"); return; }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, list.length()); i++) {
                JSONObject j = list.getJSONObject(i);
                sb.append(j.optString("time")).append("  ").append(j.optString("status"))
                        .append("\n").append(String.format(Locale.CHINA,
                                "平均 %.1f / 峰值 %.1f Mbps  ·  %.2f GB  ·  %d 线程\n",
                                j.optDouble("avg"), j.optDouble("peak"), j.optLong("bytes") / 1e9, j.optInt("threads")));
                if (i < Math.min(8, list.length()) - 1) sb.append("\n");
            }
            history.setText(sb.toString());
        } catch (Exception ignored) { history.setText("记录读取失败"); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SAVE_CSV && resultCode == RESULT_OK && data != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                String header = "time,host,port,file,threads,seconds,rounds,avg_mbps,peak_mbps,bytes,status\r\n";
                out.write(header.getBytes(StandardCharsets.UTF_8));
                JSONArray list = new JSONArray(getSharedPreferences("ftp_history", MODE_PRIVATE).getString("items", "[]"));
                for (int i = 0; i < list.length(); i++) {
                    JSONObject j = list.getJSONObject(i);
                    String[] values = { j.optString("time"), j.optString("host"), j.optString("port"), j.optString("file"),
                            j.optString("threads"), j.optString("seconds"), j.optString("rounds"),
                            j.optString("avg"), j.optString("peak"), j.optString("bytes"), j.optString("status") };
                    StringBuilder row = new StringBuilder();
                    for (int k = 0; k < values.length; k++) {
                        if (k > 0) row.append(',');
                        row.append('"').append(values[k].replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")).append('"');
                    }
                    out.write((row + "\r\n").getBytes(StandardCharsets.UTF_8));
                }
                toast("CSV 已保存");
            } catch (Exception e) { toast("导出失败：" + e.getMessage()); }
        }
    }
    private static final class SpeedChart extends View {
        private final ArrayDeque<Float> speeds = new ArrayDeque<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        SpeedChart(Context ctx) { super(ctx); }
        void push(float speed) { speeds.addLast(Math.max(0, speed)); if (speeds.size() > 120) speeds.removeFirst(); invalidate(); }
        void clear() { speeds.clear(); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth(), height = getHeight();
            paint.setColor(Color.rgb(45, 66, 88)); paint.setStrokeWidth(1);
            for (int i = 1; i <= 3; i++) canvas.drawLine(0, height * i / 4f, width, height * i / 4f, paint);
            if (speeds.size() < 2) return;
            float max = 1f; for (Float value : speeds) max = Math.max(max, value);
            paint.setColor(ACCENT); paint.setStrokeWidth(getResources().getDisplayMetrics().density * 2);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path(); int i = 0;
            for (Float value : speeds) {
                float x = (float) i / 119f * width;
                float y = height - (value / max * (height - 6));
                if (i++ == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            canvas.drawPath(path, paint); paint.setStyle(Paint.Style.FILL);
        }
    }
}
