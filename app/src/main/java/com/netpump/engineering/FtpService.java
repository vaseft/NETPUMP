package com.netpump.engineering;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FtpService extends Service {
    public static final String START = "com.netpump.engineering.FTP_START";
    public static final String STOP = "com.netpump.engineering.FTP_STOP";
    public static final String EVENT = "com.netpump.engineering.FTP_EVENT";
    private static final String CHANNEL = "netpump_ftp";
    public static volatile boolean running = false;
    public static volatile String lastStatus = "等待开始";
    public static volatile double lastRate = 0, lastAvg = 0, lastPeak = 0;
    public static volatile long lastBytes = 0;
    public static volatile int lastRound = 0, lastActive = 0, lastRemain = 0;
    private FtpRunner runner;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "FTP 下行灌包", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (STOP.equals(intent.getAction())) {
            if (runner != null) runner.stop();
            lastStatus = "正在停止…";
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (!START.equals(intent.getAction()) || running) return START_NOT_STICKY;
        FtpRunner.Config cfg = new FtpRunner.Config();
        cfg.host = intent.getStringExtra("host");
        cfg.port = intent.getIntExtra("port", 8108);
        cfg.user = intent.getStringExtra("user");
        cfg.password = intent.getStringExtra("password");
        cfg.remotePath = intent.getStringExtra("file");
        cfg.threads = intent.getIntExtra("threads", 10);
        cfg.seconds = intent.getIntExtra("seconds", 120);
        cfg.count = intent.getIntExtra("count", 1);
        cfg.intervalSec = intent.getIntExtra("interval", 10);
        cfg.endSec = intent.getIntExtra("end", 5);
        cfg.delaySec = intent.getIntExtra("delay", 5);
        cfg.delayCollection = intent.getBooleanExtra("delayCollection", false);
        cfg.cyclic = intent.getBooleanExtra("cyclic", false);
        cfg.passive = true;
        if (cfg.host == null || cfg.user == null || cfg.password == null || cfg.remotePath == null) { stopSelf(); return START_NOT_STICKY; }
        running = true;
        lastStatus = "FTP 登录中…";
        lastBytes = 0; lastRate = 0; lastAvg = 0; lastPeak = 0; lastRound = 0; lastActive = 0; lastRemain = cfg.seconds;
        Notification notification = makeNotification(cfg.host);
        if (Build.VERSION.SDK_INT >= 29) startForeground(8, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(8, notification);
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetPump:FtpDownload");
            wakeLock.acquire(40 * 60 * 1000L);
        } catch (Exception ignored) { }
        long started = System.currentTimeMillis();
        new Thread(() -> {
            FtpRunner.Result result = null;
            String failure = "";
            try {
                runner = new FtpRunner(cfg, sample -> {
                    lastRate = sample.currentMbps;
                    lastAvg = sample.averageMbps;
                    lastPeak = sample.peakMbps;
                    lastBytes = sample.bytes;
                    lastRound = sample.round;
                    lastActive = sample.active;
                    lastRemain = sample.remainingSeconds;
                    lastStatus = sample.phase;
                    Intent event = new Intent(EVENT).setPackage(getPackageName());
                    event.putExtra("running", true);
                    event.putExtra("status", sample.phase);
                    event.putExtra("rate", sample.currentMbps);
                    event.putExtra("avg", sample.averageMbps);
                    event.putExtra("peak", sample.peakMbps);
                    event.putExtra("bytes", sample.bytes);
                    event.putExtra("round", sample.round);
                    event.putExtra("active", sample.active);
                    event.putExtra("remain", sample.remainingSeconds);
                    sendBroadcast(event);
                });
                result = runner.run();
                failure = result.error;
            } catch (Exception e) { failure = e.getMessage() == null ? e.toString() : e.getMessage(); }
            finally {
                boolean stopped = result != null && result.stopped;
                running = false;
                lastRate = 0;
                lastStatus = stopped ? "已停止" : failure.isEmpty() ? "测试完成" : "失败：" + failure;
                if (result != null) { lastAvg = result.averageMbps; lastPeak = result.peakMbps; lastBytes = result.bytes; lastRound = result.completedRounds; }
                persist(cfg, result, started, lastStatus);
                Intent finished = new Intent(EVENT).setPackage(getPackageName());
                finished.putExtra("running", false);
                finished.putExtra("status", lastStatus);
                finished.putExtra("rate", 0d);
                finished.putExtra("avg", lastAvg);
                finished.putExtra("peak", lastPeak);
                finished.putExtra("bytes", lastBytes);
                finished.putExtra("round", lastRound);
                finished.putExtra("remain", 0);
                finished.putExtra("active", 0);
                sendBroadcast(finished);
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
                else stopForeground(true);
                runner = null;
                stopSelf();
            }
        }, "ftp-pump").start();
        return START_NOT_STICKY;
    }
    private Notification makeNotification(String host) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, FtpActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, FtpService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("NetPump FTP 下行测试")
                .setContentText("目标 " + host + " · 点击停止可中断")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_delete, "停止", stop).build())
                .setOngoing(true).build();
    }
    private void persist(FtpRunner.Config c, FtpRunner.Result r, long started, String status) {
        try {
            JSONObject item = new JSONObject();
            item.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(started)));
            item.put("host", c.host); item.put("port", c.port); item.put("file", c.remotePath);
            item.put("threads", c.threads); item.put("seconds", c.seconds);
            item.put("avg", r == null ? 0 : r.averageMbps);
            item.put("peak", r == null ? 0 : r.peakMbps);
            item.put("bytes", r == null ? 0 : r.bytes);
            item.put("rounds", r == null ? 0 : r.completedRounds);
            item.put("status", status); // Deliberately excludes user password.
            JSONArray old = new JSONArray(getSharedPreferences("ftp_history", MODE_PRIVATE).getString("items", "[]"));
            JSONArray next = new JSONArray().put(item);
            for (int i = 0; i < Math.min(99, old.length()); i++) next.put(old.getJSONObject(i));
            getSharedPreferences("ftp_history", MODE_PRIVATE).edit().putString("items", next.toString()).apply();
        } catch (Exception ignored) { }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (runner != null) runner.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
