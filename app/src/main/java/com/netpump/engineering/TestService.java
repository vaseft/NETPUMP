package com.netpump.engineering;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class TestService extends Service {
    public static final String ACTION_START = "com.netpump.engineering.START";
    public static final String ACTION_STOP = "com.netpump.engineering.STOP";
    public static final String EVENT = "com.netpump.engineering.RESULT";
    private static final String CHANNEL = "netpump_tests";
    public static volatile boolean running = false;
    public static volatile String lastStatus = "空闲";
    public static volatile double lastUl = 0, lastDl = 0;
    private volatile PacketRunner runner;
    private PowerManager.WakeLock wakeLock;
    private Thread testThread;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "灌包测试", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            lastStatus = "正在停止…";
            PacketRunner current = runner;
            if (current != null) current.stop();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || running) return START_NOT_STICKY;
        PacketRunner.Config config = new PacketRunner.Config();
        config.host = intent.getStringExtra("host");
        config.port = intent.getIntExtra("port", 5201);
        config.token = intent.getStringExtra("token");
        config.protocol = intent.getStringExtra("protocol");
        config.direction = intent.getStringExtra("direction");
        config.durationSeconds = intent.getIntExtra("duration", 15);
        config.streams = intent.getIntExtra("streams", 4);
        config.rateMbps = intent.getDoubleExtra("rate", 0);
        if (config.host == null || config.protocol == null || config.direction == null) {
            stopSelf(); return START_NOT_STICKY;
        }
        running = true;
        lastUl = 0;
        lastDl = 0;
        lastStatus = "连接中…";
        Notification notification = makeNotification("正在测试 " + config.host);
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(7, notification);
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetPump:TestWakeLock");
            wakeLock.acquire(11 * 60 * 1000L);
        } catch (Exception ignored) { }
        long started = System.currentTimeMillis();
        testThread = new Thread(() -> {
            PacketRunner.Result result = null;
            String error = "";
            try {
                runner = new PacketRunner(config, (ul, dl, avgU, avgD, peakU, peakD, upBytes, downBytes, loss, jitter, rtt, remain) -> {
                    lastUl = ul; lastDl = dl;
                    lastStatus = "测试中 · 剩余 " + remain + "s";
                    Intent event = new Intent(EVENT);
                    event.setPackage(getPackageName());
                    event.putExtra("running", true);
                    event.putExtra("ul", ul); event.putExtra("dl", dl);
                    event.putExtra("avgU", avgU); event.putExtra("avgD", avgD);
                    event.putExtra("peakU", peakU); event.putExtra("peakD", peakD);
                    event.putExtra("upBytes", upBytes); event.putExtra("downBytes", downBytes);
                    event.putExtra("loss", loss); event.putExtra("jitter", jitter); event.putExtra("rtt", rtt);
                    event.putExtra("remain", remain);
                    sendBroadcast(event);
                });
                result = runner.run();
                if (!result.error.isEmpty()) error = result.error;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                boolean stopped = result != null && result.stopped;
                running = false;
                lastUl = 0; lastDl = 0;
                lastStatus = stopped ? "已手动停止" : error.isEmpty() ? "测试已完成" : "失败: " + error;
                saveRecord(config, result, started, error, lastStatus);
                Intent finished = new Intent(EVENT);
                finished.setPackage(getPackageName());
                finished.putExtra("running", false);
                finished.putExtra("status", lastStatus);
                finished.putExtra("avgU", result == null ? 0 : result.avgUl);
                finished.putExtra("avgD", result == null ? 0 : result.avgDl);
                finished.putExtra("peakU", result == null ? 0 : result.peakUl);
                finished.putExtra("peakD", result == null ? 0 : result.peakDl);
                finished.putExtra("upBytes", result == null ? 0 : result.ulBytes);
                finished.putExtra("downBytes", result == null ? 0 : result.dlBytes);
                finished.putExtra("loss", result == null ? 0 : result.lossPercent);
                finished.putExtra("jitter", result == null ? 0 : result.jitterMs);
                finished.putExtra("rtt", result == null ? 0 : result.rttMs);
                sendBroadcast(finished);
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
                else stopForeground(true);
                stopSelf();
                runner = null;
            }
        }, "netpump-runner");
        testThread.start();
        return START_NOT_STICKY;
    }
    private Notification makeNotification(String message) {
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, TestService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("NetPump 工程灌包")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentIntent(pending)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                        "停止测试", stopPending).build())
                .setOngoing(true)
                .build();
    }
    private void saveRecord(PacketRunner.Config c, PacketRunner.Result r, long started, String error, String status) {
        try {
            JSONObject record = new JSONObject();
            record.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(started)));
            record.put("host", c.host); record.put("port", c.port);
            record.put("protocol", c.protocol); record.put("direction", c.direction);
            record.put("duration", c.durationSeconds); record.put("streams", c.streams);
            record.put("rate", c.rateMbps); record.put("status", status);
            record.put("avgU", r == null ? 0 : r.avgUl);
            record.put("avgD", r == null ? 0 : r.avgDl);
            record.put("peakU", r == null ? 0 : r.peakUl);
            record.put("peakD", r == null ? 0 : r.peakDl);
            record.put("upBytes", r == null ? 0 : r.ulBytes);
            record.put("downBytes", r == null ? 0 : r.dlBytes);
            record.put("loss", r == null ? 0 : r.lossPercent);
            record.put("jitter", r == null ? 0 : r.jitterMs);
            record.put("rtt", r == null ? 0 : r.rttMs);
            record.put("error", error);
            String old = getSharedPreferences("history", MODE_PRIVATE).getString("items", "[]");
            JSONArray arr = new JSONArray(old);
            JSONArray next = new JSONArray();
            next.put(record);
            for (int i = 0; i < Math.min(99, arr.length()); i++) next.put(arr.getJSONObject(i));
            getSharedPreferences("history", MODE_PRIVATE).edit().putString("items", next.toString()).apply();
        } catch (Exception ignored) { }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (runner != null) runner.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
