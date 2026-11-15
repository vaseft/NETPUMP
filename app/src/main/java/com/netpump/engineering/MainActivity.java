package com.netpump.engineering;

import android.Manifest;
import android.app.Activity;
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
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private final int bg = Color.rgb(11, 16, 23), card = Color.rgb(20, 28, 39);
    private final int fg = Color.rgb(237, 246, 251), muted = Color.rgb(145, 160, 177);
    private final int turquoise = Color.rgb(41, 215, 190), blue = Color.rgb(74, 151, 255);
    private EditText host, port, duration, streams, rate, token;
    private Spinner protocol, direction;
    private TextView statusText, cellText, ulText, dlText, avgText, extraText, historyText;
    private Button startButton, stopButton;
    private TrendGraph graph;
    private BroadcastReceiver receiver;
    private boolean registered = false;
    private static final int EXPORT_CSV = 22;
    private static final int ASK_PERMS = 42;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        loadConfig();
        refreshHistory();
        refreshCellInfo();
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                boolean running = intent.getBooleanExtra("running", false);
                statusText.setText(running ? "● 测试中 / 剩余 " + intent.getIntExtra("remain", 0) + " 秒"
                        : intent.getStringExtra("status"));
                double u = intent.getDoubleExtra("ul", 0), d = intent.getDoubleExtra("dl", 0);
                if (!running) {
                    u = intent.getDoubleExtra("avgU", 0);
                    d = intent.getDoubleExtra("avgD", 0);
                }
                ulText.setText(fmt(u));
                dlText.setText(fmt(d));
                avgText.setText(String.format(Locale.CHINA,
                        "平均 ↑ %.1f   ↓ %.1f Mbps\n峰值 ↑ %.1f   ↓ %.1f Mbps",
                        intent.getDoubleExtra("avgU", 0), intent.getDoubleExtra("avgD", 0),
                        intent.getDoubleExtra("peakU", 0), intent.getDoubleExtra("peakD", 0)));
                extraText.setText(String.format(Locale.CHINA,
                        "已发送 %.1f MB    已接收 %.1f MB\nUDP 丢包 %.2f%%    抖动 %.2f ms\n连接探测耗时 %.1f ms",
                        intent.getLongExtra("upBytes", 0) / 1e6,
                        intent.getLongExtra("downBytes", 0) / 1e6,
                        intent.getDoubleExtra("loss", 0), intent.getDoubleExtra("jitter", 0),
                        intent.getDoubleExtra("rtt", 0)));
                if (running) graph.add((float) u, (float) d);
                startButton.setEnabled(!running);
                stopButton.setEnabled(running);
                if (!running) refreshHistory();
            }
        };
        requestOptionalPermissions();
    }
    @Override public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(TestService.EVENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        registered = true;
        statusText.setText(TestService.lastStatus);
        startButton.setEnabled(!TestService.running);
        stopButton.setEnabled(TestService.running);
        refreshCellInfo();
        refreshHistory();
    }
    @Override public void onStop() {
        if (registered) { unregisterReceiver(receiver); registered = false; }
        super.onStop();
    }
    private static String fmt(double rate) {
        return String.format(Locale.US, rate < 100 ? "%.2f" : "%.1f", rate);
    }
    private int dp(float value) { return (int) (getResources().getDisplayMetrics().density * value + 0.5f); }
    private GradientDrawable shape(int fill, int radius, int border) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radius));
        if (border != 0) d.setStroke(dp(1), border);
        return d;
    }
    private TextView label(String content, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(content); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }
    private void space(LinearLayout p, int h) { p.addView(new View(this), lp(1, h)); }
    private LinearLayout card() {
        LinearLayout l = column();
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackground(shape(card, 16, Color.rgb(35, 47, 63)));
        return l;
    }
    private EditText edit(String value, boolean number) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value); e.setTextSize(15); e.setTextColor(fg);
        e.setSelectAllOnFocus(false);
        e.setPadding(dp(11), dp(7), dp(11), dp(7));
        e.setBackground(shape(Color.rgb(30, 41, 56), 9, Color.rgb(51, 64, 81)));
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }
    private LinearLayout field(String text, EditText e) {
        LinearLayout wrapper = column();
        wrapper.addView(label(text, 12, muted, false));
        space(wrapper, 5);
        wrapper.addView(e, lp(-1, 42));
        return wrapper;
    }
    private Button button(String name, int color) {
        Button b = new Button(this);
        b.setText(name); b.setAllCaps(false);
        b.setTextSize(14); b.setTextColor(Color.WHITE);
        b.setBackground(shape(color, 10, 0));
        return b;
    }
    private Spinner dropdown(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(fg);
                tv.setPadding(dp(12), dp(8), dp(8), dp(8));
                return tv;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                tv.setTextColor(fg);
                tv.setBackgroundColor(card);
                tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                return tv;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackground(shape(Color.rgb(30, 41, 56), 9, Color.rgb(51, 64, 81)));
        return spinner;
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(bg);
        LinearLayout root = column();
        root.setPadding(dp(18), dp(24), dp(18), dp(28));
        root.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);
        TextView brand = label("NETPUMP  /  工程灌包", 24, fg, true);
        root.addView(brand);
        space(root, 5);
        root.addView(label("4G / 5G TCP · UDP 吞吐量测试", 13, muted, false));
        space(root, 18);
        LinearLayout dashboard = card();
        root.addView(dashboard, lp(-1, -2));
        statusText = label("● 空闲", 13, turquoise, true);
        dashboard.addView(statusText);
        space(dashboard, 16);
        LinearLayout speeds = row();
        dashboard.addView(speeds);
        LinearLayout ul = column(), dl = column();
        ul.addView(label("↑  UL 上行", 13, turquoise, true));
        space(ul, 3);
        ulText = label("0.00", 36, fg, true);
        ul.addView(ulText);
        ul.addView(label("Mbps", 12, muted, false));
        dl.addView(label("↓  DL 下行", 13, blue, true));
        space(dl, 3);
        dlText = label("0.00", 36, fg, true);
        dl.addView(dlText);
        dl.addView(label("Mbps", 12, muted, false));
        speeds.addView(ul, new LinearLayout.LayoutParams(0, -2, 1));
        speeds.addView(dl, new LinearLayout.LayoutParams(0, -2, 1));
        space(dashboard, 13);
        graph = new TrendGraph(this, turquoise, blue);
        dashboard.addView(graph, lp(-1, 122));
        space(dashboard, 12);
        avgText = label("平均 ↑ 0.0   ↓ 0.0 Mbps\n峰值 ↑ 0.0   ↓ 0.0 Mbps", 13, fg, false);
        dashboard.addView(avgText);
        space(dashboard, 8);
        extraText = label("已发送 0 MB    已接收 0 MB\nUDP 丢包 0.00%    抖动 0.00 ms\n连接探测耗时 0.0 ms", 12, muted, false);
        dashboard.addView(extraText);
        space(root, 14);
        LinearLayout settings = card();
        root.addView(settings, lp(-1, -2));
        settings.addView(label("测试参数", 17, fg, true));
        space(settings, 12);
        host = edit("192.168.1.10", false);
        port = edit("5201", true);
        LinearLayout hostRow = row();
        LinearLayout hwrap = field("服务端 IP / 域名", host), pwrap = field("端口", port);
        hostRow.addView(hwrap, new LinearLayout.LayoutParams(0, -2, 2.1f));
        hostRow.addView(new View(this), lp(10, 1));
        hostRow.addView(pwrap, new LinearLayout.LayoutParams(0, -2, 1f));
        settings.addView(hostRow);
        space(settings, 10);
        protocol = dropdown(new String[]{"TCP", "UDP"});
        direction = dropdown(new String[]{"UL", "DL", "BOTH"});
        LinearLayout modeRow = row();
        LinearLayout pr = column(), dr = column();
        pr.addView(label("协议", 12, muted, false)); space(pr, 4); pr.addView(protocol, lp(-1, 42));
        dr.addView(label("方向", 12, muted, false)); space(dr, 4); dr.addView(direction, lp(-1, 42));
        modeRow.addView(pr, new LinearLayout.LayoutParams(0, -2, 1));
        modeRow.addView(new View(this), lp(10, 1));
        modeRow.addView(dr, new LinearLayout.LayoutParams(0, -2, 1));
        settings.addView(modeRow);
        space(settings, 10);
        duration = edit("15", true); streams = edit("4", true); rate = edit("0", true);
        LinearLayout params = row();
        LinearLayout dw = field("时长(秒)", duration), sw = field("并发流", streams), rw = field("速率(Mbps)", rate);
        params.addView(dw, new LinearLayout.LayoutParams(0, -2, 1));
        params.addView(new View(this), lp(8, 1));
        params.addView(sw, new LinearLayout.LayoutParams(0, -2, 1));
        params.addView(new View(this), lp(8, 1));
        params.addView(rw, new LinearLayout.LayoutParams(0, -2, 1.3f));
        settings.addView(params);
        space(settings, 8);
        settings.addView(label("TCP 速率填 0 可不限速；UDP 必须指定速率。BOTH 双向同时运行。", 11, muted, false));
        space(settings, 10);
        token = edit("", false);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        settings.addView(field("连接口令 (可选，公网推荐设置)", token));
        space(settings, 13);
        LinearLayout actions = row();
        startButton = button("▶ 开始测试", Color.rgb(21, 139, 127));
        stopButton = button("■ 停止", Color.rgb(161, 64, 77));
        stopButton.setEnabled(false);
        actions.addView(startButton, new LinearLayout.LayoutParams(0, dp(46), 1.7f));
        actions.addView(new View(this), lp(10, 1));
        actions.addView(stopButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        settings.addView(actions);
        startButton.setOnClickListener(v -> startTest());
        stopButton.setOnClickListener(v -> stopTest());
        space(root, 14);
        LinearLayout info = card();
        root.addView(info, lp(-1, -2));
        LinearLayout infoTitle = row();
        TextView header = label("移动网络信息", 17, fg, true);
        infoTitle.addView(header, new LinearLayout.LayoutParams(0, -2, 1));
        Button refresh = button("刷新", Color.rgb(43, 69, 94));
        infoTitle.addView(refresh, lp(70, 36));
        refresh.setOnClickListener(v -> refreshCellInfo());
        info.addView(infoTitle);
        space(info, 9);
        cellText = label("读取中…", 12, muted, false);
        info.addView(cellText);
        space(root, 14);
        LinearLayout hist = card();
        root.addView(hist, lp(-1, -2));
        LinearLayout histTitle = row();
        histTitle.addView(label("最近测试记录", 17, fg, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button export = button("导出 CSV", Color.rgb(43, 69, 94));
        histTitle.addView(export, lp(105, 36));
        export.setOnClickListener(v -> {
            Intent file = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            file.addCategory(Intent.CATEGORY_OPENABLE);
            file.setType("text/csv");
            file.putExtra(Intent.EXTRA_TITLE, "NetPump-Tests.csv");
            startActivityForResult(file, EXPORT_CSV);
        });
        hist.addView(histTitle);
        space(hist, 10);
        historyText = label("暂无记录", 12, muted, false);
        hist.addView(historyText);
        space(root, 17);
        root.addView(label("工程测试会消耗手机流量。请使用已授权的测试网络和服务器。", 12, muted, false));
    }
    private void loadConfig() {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        host.setText(p.getString("host", "192.168.1.10"));
        port.setText(p.getString("port", "5201"));
        duration.setText(p.getString("duration", "15"));
        streams.setText(p.getString("streams", "4"));
        rate.setText(p.getString("rate", "0"));
        token.setText(p.getString("token", ""));
        protocol.setSelection(p.getInt("protocol", 0));
        direction.setSelection(p.getInt("direction", 0));
    }
    private static int parseInt(EditText t, int min, int max) {
        int n = Integer.parseInt(t.getText().toString().trim());
        if (n < min || n > max) throw new IllegalArgumentException("数值范围：" + min + "~" + max);
        return n;
    }
    private void startTest() {
        try {
            PacketRunner.Config c = new PacketRunner.Config();
            c.host = host.getText().toString().trim();
            if (c.host.length() < 1 || c.host.contains("/") || c.host.contains(" "))
                throw new IllegalArgumentException("服务端地址格式错误");
            c.port = parseInt(port, 1, 65535);
            c.durationSeconds = parseInt(duration, 1, 600);
            c.streams = parseInt(streams, 1, 16);
            c.protocol = protocol.getSelectedItem().toString();
            c.direction = direction.getSelectedItem().toString();
            c.rateMbps = Double.parseDouble(rate.getText().toString());
            if (!Double.isFinite(c.rateMbps) || c.rateMbps < 0 || c.rateMbps > 2000)
                throw new IllegalArgumentException("速率范围：0~2000 Mbps");
            if (c.protocol.equals("UDP") && c.rateMbps <= 0)
                throw new IllegalArgumentException("UDP 测试请设置目标速率，例如 100 Mbps");
            c.token = token.getText().toString().trim();
            if (!c.token.matches("[A-Za-z0-9_-]{0,64}"))
                throw new IllegalArgumentException("口令只允许英文字母、数字、_、-，最多 64 位");
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("host", c.host).putString("port", String.valueOf(c.port))
                    .putString("duration", String.valueOf(c.durationSeconds))
                    .putString("streams", String.valueOf(c.streams))
                    .putString("rate", String.valueOf(c.rateMbps))
                    .putString("token", c.token)
                    .putInt("protocol", protocol.getSelectedItemPosition())
                    .putInt("direction", direction.getSelectedItemPosition()).apply();
            graph.reset();
            statusText.setText("● 正在连接…");
            startButton.setEnabled(false); stopButton.setEnabled(true);
            Intent run = new Intent(this, TestService.class).setAction(TestService.ACTION_START);
            run.putExtra("host", c.host); run.putExtra("port", c.port);
            run.putExtra("token", c.token); run.putExtra("duration", c.durationSeconds);
            run.putExtra("streams", c.streams); run.putExtra("protocol", c.protocol);
            run.putExtra("direction", c.direction); run.putExtra("rate", c.rateMbps);
            startForegroundService(run);
        } catch (Exception e) {
            Toast.makeText(this, "参数错误：" + e.getMessage(), Toast.LENGTH_LONG).show();
            startButton.setEnabled(true); stopButton.setEnabled(false);
        }
    }
    private void stopTest() {
        startService(new Intent(this, TestService.class).setAction(TestService.ACTION_STOP));
        statusText.setText("● 正在停止…");
    }
    private void requestOptionalPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, ASK_PERMS);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, ASK_PERMS);
        }
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ASK_PERMS) refreshCellInfo();
    }
    private void refreshCellInfo() {
        if (cellText == null) return;
        StringBuilder builder = new StringBuilder();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network net = cm.getActiveNetwork();
            NetworkCapabilities cp = cm.getNetworkCapabilities(net);
            if (cp != null) {
                builder.append("承载网络：");
                if (cp.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) builder.append("移动数据");
                else if (cp.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) builder.append("Wi-Fi（当前测试会走 Wi-Fi）");
                else builder.append("其他网络");
                builder.append("\n");
            } else builder.append("未发现可用网络\n");
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null) {
                builder.append("运营商：").append(tm.getNetworkOperatorName()).append("\n");
                if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    int t = tm.getDataNetworkType();
                    builder.append("注册类型：").append(t == TelephonyManager.NETWORK_TYPE_NR ? "5G NR" :
                            t == TelephonyManager.NETWORK_TYPE_LTE ? "4G LTE（NSA 可能显示 LTE）" : "网络类型 " + t).append("\n");
                }
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    List<CellInfo> infos = tm.getAllCellInfo();
                    if (infos != null) {
                        int number = 0;
                        for (CellInfo info : infos) {
                            if (!info.isRegistered()) continue;
                            if (Build.VERSION.SDK_INT >= 29 && info instanceof CellInfoNr) {
                                CellInfoNr nr = (CellInfoNr) info;
                                CellIdentityNr id = (CellIdentityNr) nr.getCellIdentity();
                                CellSignalStrengthNr sig = (CellSignalStrengthNr) nr.getCellSignalStrength();
                                builder.append("NR  PCI ").append(id.getPci())
                                        .append(" · NRARFCN ").append(id.getNrarfcn())
                                        .append("\nSS-RSRP ").append(sig.getSsRsrp())
                                        .append(" dBm · SS-SINR ").append(sig.getSsSinr()).append(" dB\n");
                                number++;
                            } else if (info instanceof CellInfoLte) {
                                CellInfoLte lte = (CellInfoLte) info;
                                CellIdentityLte id = lte.getCellIdentity();
                                CellSignalStrengthLte sig = lte.getCellSignalStrength();
                                builder.append("LTE  PCI ").append(id.getPci())
                                        .append(" · EARFCN ").append(id.getEarfcn())
                                        .append("\nRSRP ").append(sig.getRsrp())
                                        .append(" dBm · RSSNR ").append(sig.getRssnr()).append(" (0.1dB)\n");
                                number++;
                            }
                        }
                        if (number == 0) builder.append("暂无可读取的驻留小区参数（设备可能限制）");
                    }
                } else builder.append("允许位置权限可读取 RSRP、PCI 等可用小区参数");
            }
        } catch (SecurityException e) {
            builder.append("系统未授权读取无线参数");
        } catch (Exception e) {
            builder.append("小区信息暂不可用：").append(e.getClass().getSimpleName());
        }
        cellText.setText(builder.toString());
    }
    private JSONArray history() {
        try { return new JSONArray(getSharedPreferences("history", MODE_PRIVATE).getString("items", "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }
    private void refreshHistory() {
        if (historyText == null) return;
        JSONArray items = history();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(5, items.length()); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r == null) continue;
            if (i > 0) b.append("\n──────────────────\n");
            b.append(r.optString("time")).append("  ").append(r.optString("protocol"))
                    .append(" / ").append(r.optString("direction"))
                    .append("\n").append(r.optString("host"))
                    .append(String.format(Locale.CHINA, "  ↑ %.1f  ↓ %.1f Mbps",
                            r.optDouble("avgU"), r.optDouble("avgD")));
        }
        historyText.setText(b.length() > 0 ? b.toString() : "暂无记录");
    }
    private static String csv(String val) { return "\"" + val.replace("\"", "\"\"") + "\""; }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_CSV || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new IllegalStateException("保存失败");
            String[] keys = {"time", "host", "port", "protocol", "direction", "duration", "streams", "rate",
                    "avgU", "avgD", "peakU", "peakD", "upBytes", "downBytes", "loss", "jitter", "rtt", "status", "error"};
            StringBuilder csvData = new StringBuilder("\ufeff");
            csvData.append(String.join(",", keys)).append("\r\n");
            JSONArray arr = history();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject row = arr.optJSONObject(i);
                if (row == null) continue;
                for (int j = 0; j < keys.length; j++) {
                    if (j > 0) csvData.append(',');
                    csvData.append(csv(row.optString(keys[j], "")));
                }
                csvData.append("\r\n");
            }
            out.write(csvData.toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "CSV 已导出", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private static class TrendGraph extends View {
        private final int colorUp, colorDown;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayDeque<Float> ups = new ArrayDeque<>(), downs = new ArrayDeque<>();
        TrendGraph(Context c, int u, int d) { super(c); colorUp = u; colorDown = d; reset(); }
        void reset() {
            ups.clear(); downs.clear();
            ups.add(0f); downs.add(0f);
            invalidate();
        }
        void add(float ul, float dl) {
            if (ups.size() >= 45) { ups.removeFirst(); downs.removeFirst(); }
            ups.addLast(ul); downs.addLast(dl); invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth(), height = getHeight();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(getResources().getDisplayMetrics().density);
            paint.setColor(Color.rgb(44, 57, 71));
            for (int i = 1; i <= 3; i++) canvas.drawLine(0, i * height / 4f, width, i * height / 4f, paint);
            float max = 10;
            for (float v : ups) max = Math.max(max, v);
            for (float v : downs) max = Math.max(max, v);
            drawLine(canvas, ups, colorUp, max, width, height);
            drawLine(canvas, downs, colorDown, max, width, height);
        }
        private void drawLine(Canvas canvas, ArrayDeque<Float> values, int color, float max, int width, int height) {
            Path p = new Path(); int n = 0;
            for (float v : values) {
                float x = width * (n++ / 44f);
                float y = height - (height - 5f) * Math.min(1, v / max) - 2f;
                if (n == 1) p.moveTo(x, y); else p.lineTo(x, y);
            }
            paint.setColor(color); paint.setStrokeWidth(getResources().getDisplayMetrics().density * 2.3f);
            paint.setStyle(Paint.Style.STROKE); canvas.drawPath(p, paint);
        }
    }
}
