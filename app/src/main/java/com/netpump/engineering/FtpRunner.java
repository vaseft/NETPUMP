package com.netpump.engineering;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FTP RETR streaming throughput tester. Data is discarded in RAM and is never saved to disk. */
public final class FtpRunner {
    public static final class Config {
        public String host, user, password, remotePath;
        public int port = 8108, threads = 10, seconds = 120, count = 1, intervalSec = 10,
                endSec = 5, delaySec = 5;
        public boolean cyclic, delayCollection, passive = true;
    }
    public static final class Sample {
        public final double currentMbps, averageMbps, peakMbps;
        public final long bytes;
        public final int round, remainingSeconds, active;
        public final String phase;
        Sample(double currentMbps, double averageMbps, double peakMbps, long bytes,
               int round, int remainingSeconds, int active, String phase) {
            this.currentMbps = currentMbps; this.averageMbps = averageMbps;
            this.peakMbps = peakMbps; this.bytes = bytes; this.round = round;
            this.remainingSeconds = remainingSeconds; this.active = active; this.phase = phase;
        }
    }
    public interface Listener { void update(Sample sample); }
    public static final class Result {
        public long bytes;
        public double averageMbps, peakMbps;
        public int completedRounds;
        public long elapsedMillis;
        public String error = "";
        public boolean stopped;
    }

    private final Config cfg;
    private final Listener listener;
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicReference<String> failure = new AtomicReference<>("");
    private final List<Socket> sockets = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile ExecutorService executor;

    public FtpRunner(Config cfg, Listener listener) { this.cfg = cfg; this.listener = listener; }
    public void stop() {
        stop.set(true);
        synchronized (sockets) {
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            sockets.clear();
        }
        ExecutorService ex = executor;
        if (ex != null) ex.shutdownNow();
    }
    private Socket connect(String host, int port, int timeout) throws IOException {
        Socket s = new Socket();
        sockets.add(s);
        try {
            s.connect(new InetSocketAddress(host, port), timeout);
            s.setSoTimeout(12000);
            s.setTcpNoDelay(true);
            return s;
        } catch (IOException e) {
            sockets.remove(s);
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
    }
    private void release(Socket s) {
        if (s != null) { sockets.remove(s); try { s.close(); } catch (IOException ignored) { } }
    }
    private static final class Reply {
        final int code;
        final String body;
        Reply(int code, String body) { this.code = code; this.body = body; }
    }
    private static final class Control implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader input;
        private final OutputStream output;
        private final FtpRunner runner;
        Control(FtpRunner runner, Config cfg) throws IOException {
            this.runner = runner;
            socket = runner.connect(cfg.host, cfg.port, 10000);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            output = socket.getOutputStream();
            Reply greeting = readReply();
            require(greeting, 220);
            Reply login = command("USER " + cfg.user);
            if (login.code == 331 || login.code == 332) login = command("PASS " + cfg.password);
            require(login, 230, 202);
            require(command("TYPE I"), 200);
        }
        Reply command(String command) throws IOException {
            // Avoid CRLF injection into FTP control protocol.
            if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) throw new IOException("FTP 参数包含非法换行");
            output.write((command + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            return readReply();
        }
        Reply readReply() throws IOException {
            String first = input.readLine();
            if (first == null) throw new IOException("FTP 服务端断开控制连接");
            if (first.length() < 3 || !Character.isDigit(first.charAt(0)) ||
                    !Character.isDigit(first.charAt(1)) || !Character.isDigit(first.charAt(2))) {
                throw new IOException("FTP 响应格式错误");
            }
            int code = Integer.parseInt(first.substring(0, 3));
            StringBuilder body = new StringBuilder(first);
            if (first.length() > 3 && first.charAt(3) == '-') {
                for (int i = 0; i < 100; i++) {
                    String line = input.readLine();
                    if (line == null) throw new IOException("FTP 响应意外结束");
                    body.append('\n').append(line);
                    if (line.startsWith(first.substring(0, 3) + " ")) break;
                    if (i == 99) throw new IOException("FTP 响应过长");
                }
            }
            return new Reply(code, body.toString());
        }
        static void require(Reply r, int... allowed) throws IOException {
            for (int expected : allowed) if (r.code == expected) return;
            throw new IOException("FTP " + r.code + ": " + r.body.replace('\n', ' '));
        }
        int passivePort() throws IOException {
            Reply epsv = command("EPSV");
            if (epsv.code == 229) {
                Matcher m = Pattern.compile("\\(\\|\\|\\|(\\d+)\\|\\)").matcher(epsv.body);
                if (m.find()) {
                    int port = Integer.parseInt(m.group(1));
                    if (port > 0 && port < 65536) return port;
                }
            }
            Reply pasv = command("PASV");
            require(pasv, 227);
            Matcher m = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").matcher(pasv.body);
            if (!m.find()) throw new IOException("服务器 PASV 响应无法解析: " + pasv.body);
            int port = Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));
            if (port <= 0 || port > 65535) throw new IOException("PASV 数据端口无效");
            return port;
        }
        public void close() { runner.release(socket); }
    }
    public static String probe(Config cfg) throws IOException {
        FtpRunner runner = new FtpRunner(cfg, null);
        try (Control control = new Control(runner, cfg)) {
            Reply size = control.command("SIZE " + cfg.remotePath);
            if (size.code == 213) {
                return "FTP 登录成功，文件大小 " + size.body.substring(4).trim() + " 字节";
            }
            if (size.code == 550 || size.code == 553) throw new IOException("文件不存在或无读取权限：FTP " + size.code);
            return "FTP 登录成功（该服务器未返回 SIZE：" + size.code + "；可开始下载测试）";
        } finally { runner.stop(); }
    }

    public Result run() {
        Result result = new Result();
        long taskStart = System.nanoTime();
        double peak = 0.0;
        long measuredNanos = 0L;
        long measuredBytes = 0L;
        try {
            final int cycles = cfg.cyclic ? cfg.count : 1;
            for (int cycle = 1; cycle <= cycles && !stop.get(); cycle++) {
                final int round = cycle;
                final long beginningBytes = bytes.get();
                final long roundStart = System.nanoTime();
                final long deadline = roundStart + TimeUnit.SECONDS.toNanos(cfg.seconds);
                final CountDownLatch begin = new CountDownLatch(1);
                final AtomicLong workersLive = new AtomicLong(0);
                executor = Executors.newFixedThreadPool(cfg.threads);
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < cfg.threads; i++) {
                    futures.add(executor.submit(() -> {
                        workersLive.incrementAndGet();
                        try {
                            begin.await();
                            if (!stop.get()) downloadWorker(deadline);
                        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        finally { workersLive.decrementAndGet(); }
                    }));
                }
                begin.countDown();
                long prevTime = System.nanoTime(), prevBytes = bytes.get();
                long collectionStart = cfg.delayCollection ? -1L : roundStart;
                long baselineBytes = beginningBytes;
                while (!stop.get() && System.nanoTime() < deadline) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); stop.set(true); break; }
                    long now = System.nanoTime(), total = bytes.get();
                    double instant = (total - prevBytes) * 8.0 / Math.max(1, now - prevTime) * 1000.0;
                    prevBytes = total; prevTime = now;
                    long elapsed = now - roundStart;
                    if (collectionStart < 0 && elapsed >= TimeUnit.SECONDS.toNanos(cfg.delaySec)) {
                        collectionStart = now;
                        baselineBytes = total;
                    }
                    double avg = collectionStart < 0 ? 0 :
                            (total - baselineBytes) * 8.0 / Math.max(1, now - collectionStart) * 1000.0;
                    if (collectionStart >= 0) peak = Math.max(peak, instant);
                    if (listener != null) listener.update(new Sample(instant, avg, peak, total, round,
                            Math.max(0, (int)Math.ceil((deadline - now) / 1e9)), (int) workersLive.get(), "FTP 下载中"));
                    if (workersLive.get() == 0) break;
                }
                for (Future<?> f : futures) f.cancel(true);
                stopConnectionsForRound();
                executor.shutdownNow();
                executor.awaitTermination(2, TimeUnit.SECONDS);
                executor = null;
                long end = System.nanoTime();
                if (!stop.get()) result.completedRounds++;
                if (collectionStart >= 0) {
                    measuredNanos += Math.max(1, end - collectionStart);
                    measuredBytes += bytes.get() - baselineBytes;
                }
                if (bytes.get() == beginningBytes && !failure.get().isEmpty()) break;
                if (cycle < cycles && !stop.get() && cfg.intervalSec > 0) {
                    if (listener != null) listener.update(new Sample(0, 0, peak, bytes.get(), round, cfg.intervalSec, 0, "间隔等待"));
                    sleepInterruptible(cfg.intervalSec);
                }
            }
            if (result.completedRounds > 0 && cfg.endSec > 0 && !stop.get()) {
                if (listener != null) listener.update(new Sample(0, 0, peak, bytes.get(), result.completedRounds, cfg.endSec, 0, "结束等待"));
                sleepInterruptible(cfg.endSec);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); stop.set(true);
        } catch (Exception e) {
            failure.compareAndSet("", e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            stopConnectionsForRound();
            ExecutorService ex = executor;
            if (ex != null) ex.shutdownNow();
            result.bytes = bytes.get();
            result.peakMbps = peak;
            result.elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - taskStart);
            result.averageMbps = measuredNanos <= 0 ? 0 : measuredBytes * 8.0 / measuredNanos * 1000.0;
            result.stopped = stop.get();
            if (result.bytes == 0 && !result.stopped) result.error = failure.get().isEmpty() ? "没有接收到文件数据" : failure.get();
            else if (!failure.get().isEmpty() && result.completedRounds == 0 && !result.stopped) result.error = failure.get();
        }
        return result;
    }
    private void sleepInterruptible(int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!stop.get() && System.nanoTime() < end) Thread.sleep(Math.min(250, Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()))));
    }
    private void stopConnectionsForRound() {
        synchronized (sockets) {
            for (Socket s : sockets) try { s.close(); } catch (IOException ignored) { }
            sockets.clear();
        }
    }
    private void downloadWorker(long deadline) {
        Socket data = null;
        try (Control ctrl = new Control(this, cfg)) {
            if (!cfg.passive) throw new IOException("蜂窝网络暂仅支持被动 FTP（PASV/EPSV）");
            int dataPort = ctrl.passivePort();
            // For public-facing FTP with NAT, the configured host is more reliable than the PASV-advertised IP.
            data = connect(cfg.host, dataPort, 10000);
            Reply starting = ctrl.command("RETR " + cfg.remotePath);
            Control.require(starting, 125, 150);
            byte[] buffer = new byte[128 * 1024];
            InputStream in = data.getInputStream();
            while (!stop.get() && System.nanoTime() < deadline) {
                int n;
                try { n = in.read(buffer); }
                catch (java.net.SocketTimeoutException timeout) { continue; }
                if (n < 0) break;
                if (n > 0) bytes.addAndGet(n);
            }
            // No local file writes. Closing the connection cancels the rest of the 20GB transfer.
        } catch (Exception e) {
            if (!stop.get()) failure.compareAndSet("", e.getMessage() == null ? e.toString() : e.getMessage());
        } finally { release(data); }
    }
}
