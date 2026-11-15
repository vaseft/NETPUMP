package com.netpump.engineering;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/** Protocol documented in README; standard Java sockets, no hidden API or Root. */
public final class PacketRunner {
    public static class Config {
        public String host = "192.168.1.10";
        public int port = 5201;
        public String token = "";
        public String protocol = "TCP";
        public String direction = "UL";
        public int durationSeconds = 15;
        public int streams = 4;
        public double rateMbps = 0;
    }
    public interface Listener {
        void onProgress(double ulMbps, double dlMbps, double avgUl, double avgDl,
                        double peakUl, double peakDl, long ulBytes, long dlBytes,
                        double lossPercent, double jitterMs, double rttMs, int secondsLeft);
    }
    public static class Result {
        public boolean stopped;
        public String error = "";
        public long ulBytes, dlBytes;
        public double avgUl, avgDl, peakUl, peakDl, lossPercent, jitterMs, rttMs;
        public long elapsedMs;
    }
    private final Config cfg;
    private final Listener listener;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicLong ulBytes = new AtomicLong();
    private final AtomicLong dlBytes = new AtomicLong();
    private final AtomicLong udpPackets = new AtomicLong();
    private final AtomicLong udpLost = new AtomicLong();
    private final AtomicLong jitterWeighted = new AtomicLong(); // microseconds * packet count
    private final AtomicLong jitterCount = new AtomicLong();
    private final List<Socket> openSockets = new ArrayList<>();
    private final List<DatagramSocket> openDatagrams = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final AtomicLong successes = new AtomicLong();
    private ExecutorService pool;
    private double peakUl, peakDl;
    private long deadlineNs;
    private InetAddress serverIp;
    private double probeRttMs = 0;

    public PacketRunner(Config config, Listener listener) {
        cfg = config;
        this.listener = listener;
    }
    public void stop() {
        active.set(false);
        synchronized (openSockets) {
            for (Socket s : openSockets) try { s.close(); } catch (IOException ignored) { }
            openSockets.clear();
        }
        synchronized (openDatagrams) {
            for (DatagramSocket d : openDatagrams) d.close();
            openDatagrams.clear();
        }
        if (pool != null) pool.shutdownNow();
    }
    private void register(Socket s) { synchronized (openSockets) { openSockets.add(s); } }
    private void unregister(Socket s) { synchronized (openSockets) { openSockets.remove(s); } }
    private void register(DatagramSocket d) { synchronized (openDatagrams) { openDatagrams.add(d); } }
    private void unregister(DatagramSocket d) { synchronized (openDatagrams) { openDatagrams.remove(d); } }
    private void recordError(Exception ex) {
        if (active.get() && (deadlineNs == 0 || ns() < deadlineNs - 250_000_000L)) synchronized (errors) {
            if (errors.size() < 4) errors.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }
    private Socket connect() throws IOException {
        Socket s = new Socket();
        register(s);
        try {
            s.setReceiveBufferSize(1 << 20);
            s.setSendBufferSize(1 << 20);
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(serverIp, cfg.port), 3500);
            s.setSoTimeout(2000);
            return s;
        } catch (IOException e) {
            unregister(s);
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
    }
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (b.size() < 256) {
            int next = in.read();
            if (next < 0) throw new IOException("服务器断开了连接");
            if (next == '\n') return b.toString("UTF-8").trim();
            if (next != '\r') b.write(next);
        }
        throw new IOException("服务端响应过长");
    }
    private static void sendLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
    private static void requireOk(InputStream in) throws IOException {
        String response = readLine(in);
        if (!"OK".equals(response)) throw new IOException("服务端回复: " + response);
    }
    private static long ns() { return SystemClock.elapsedRealtimeNanos(); }
    private static class RateLimiter {
        private final double bytesPerNs;
        private final double maxTokens;
        private double tokens;
        private long previousNs;
        RateLimiter(double mbps) {
            bytesPerNs = mbps <= 0 ? 0 : mbps * 1_000_000.0 / 8.0 / 1_000_000_000.0;
            maxTokens = Math.max(65536, mbps * 1_000_000.0 / 8 / 10); // 100 ms burst
            tokens = 0;
            previousNs = ns();
        }
        void acquire(int n, AtomicBoolean active, long deadline) {
            if (bytesPerNs == 0) return;
            while (active.get() && ns() < deadline) {
                long sleepNs;
                synchronized (this) {
                    long now = ns();
                    tokens = Math.min(maxTokens, tokens + (now - previousNs) * bytesPerNs);
                    previousNs = now;
                    if (tokens >= n) { tokens -= n; return; }
                    sleepNs = Math.min(20_000_000L, Math.max(200_000L, (long) ((n - tokens) / bytesPerNs)));
                }
                java.util.concurrent.locks.LockSupport.parkNanos(sleepNs);
            }
        }
    }
    public Result run() throws Exception {
        serverIp = InetAddress.getByName(cfg.host);
        // One application-level probe before the transfer; includes TCP handshake overhead.
        long probeStart = ns();
        Socket probe = connect();
        try {
            sendLine(probe.getOutputStream(), "PING " + auth());
            if (!"PONG".equals(readLine(probe.getInputStream())))
                throw new IOException("PING 探测失败：服务端协议不兼容或口令错误");
            probeRttMs = (ns() - probeStart) / 1e6;
        } finally { unregister(probe); try { probe.close(); } catch (IOException ignored) {} }
        boolean up = cfg.direction.equals("UL") || cfg.direction.equals("BOTH");
        boolean down = cfg.direction.equals("DL") || cfg.direction.equals("BOTH");
        int count = cfg.streams * (up && down ? 2 : 1);
        pool = Executors.newFixedThreadPool(count);
        CountDownLatch latch = new CountDownLatch(count);
        RateLimiter uploadLimiter = new RateLimiter(cfg.rateMbps);
        RateLimiter downloadLimiter = new RateLimiter(cfg.rateMbps);
        long startNs = ns();
        deadlineNs = startNs + cfg.durationSeconds * 1_000_000_000L;
        for (int i = 0; i < cfg.streams; i++) {
            if (up) {
                final int worker = i;
                pool.submit(() -> {
                    try {
                        if (cfg.protocol.equals("TCP")) tcpWorker(true, uploadLimiter);
                        else udpUpload(worker, uploadLimiter);
                        successes.incrementAndGet();
                    } catch (Exception e) { recordError(e); }
                    finally { latch.countDown(); }
                });
            }
            if (down) {
                final int worker = i;
                pool.submit(() -> {
                    try {
                        if (cfg.protocol.equals("TCP")) tcpWorker(false, downloadLimiter);
                        else udpDownload(worker, downloadLimiter);
                        successes.incrementAndGet();
                    } catch (Exception e) { recordError(e); }
                    finally { latch.countDown(); }
                });
            }
        }
        long previousNs = startNs, previousUp = 0, previousDown = 0;
        try {
            while (active.get() && ns() < deadlineNs && latch.getCount() > 0) {
                Thread.sleep(250);
                long now = ns();
                long totalUp = ulBytes.get(), totalDown = dlBytes.get();
                double stepSeconds = Math.max(0.01, (now - previousNs) / 1e9);
                double upSpeed = (totalUp - previousUp) * 8 / stepSeconds / 1e6;
                double downSpeed = (totalDown - previousDown) * 8 / stepSeconds / 1e6;
                peakUl = Math.max(peakUl, upSpeed);
                peakDl = Math.max(peakDl, downSpeed);
                double elapsed = Math.max(.001, (now - startNs) / 1e9);
                long recv = udpPackets.get(), lost = udpLost.get();
                listener.onProgress(upSpeed, downSpeed, totalUp * 8 / elapsed / 1e6,
                        totalDown * 8 / elapsed / 1e6, peakUl, peakDl, totalUp, totalDown,
                        recv + lost == 0 ? 0 : lost * 100.0 / (recv + lost),
                        jitterCount.get() == 0 ? 0 : jitterWeighted.get() / (double) jitterCount.get() / 1000.0,
                        probeRttMs, (int) Math.max(0, Math.ceil((deadlineNs - now) / 1e9)));
                previousNs = now;
                previousUp = totalUp;
                previousDown = totalDown;
            }
            // Closing sockets is necessary to unblock workers stopped by the user or duration.
            boolean cancelled = !active.get();
            // Normal termination: give UDP receiver/statistics a short grace period before closing sockets.
            if (!cancelled) latch.await(800, TimeUnit.MILLISECONDS);
            active.set(false);
            synchronized (openSockets) { for (Socket s : openSockets) try { s.close(); } catch (IOException ignored) { } }
            synchronized (openDatagrams) { for (DatagramSocket d : openDatagrams) d.close(); }
            latch.await(2, TimeUnit.SECONDS);
            Result r = new Result();
            r.stopped = cancelled;
            r.ulBytes = ulBytes.get();
            r.dlBytes = dlBytes.get();
            r.elapsedMs = Math.max(1, Math.min(cfg.durationSeconds * 1000L, (ns() - startNs) / 1_000_000L));
            r.avgUl = r.ulBytes * 8.0 / (r.elapsedMs * 1000.0);
            r.avgDl = r.dlBytes * 8.0 / (r.elapsedMs * 1000.0);
            r.peakUl = peakUl;
            r.peakDl = peakDl;
            r.rttMs = probeRttMs;
            long received = udpPackets.get(), lost = udpLost.get();
            r.lossPercent = received + lost == 0 ? 0 : lost * 100.0 / (received + lost);
            r.jitterMs = jitterCount.get() == 0 ? 0 : jitterWeighted.get() / (double) jitterCount.get() / 1000.0;
            synchronized (errors) {
                if (!errors.isEmpty()) r.error = String.join(" | ", errors);
            }
            if (successes.get() == 0 && r.ulBytes == 0 && r.dlBytes == 0 && !r.stopped) {
                throw new IOException(r.error.isEmpty() ? "所有线程连接失败，请检查服务器和端口" : r.error);
            }
            return r;
        } finally {
            stop();
        }
    }
    private void tcpWorker(boolean up, RateLimiter limiter) throws IOException {
        Socket socket = connect();
        try {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();
            sendLine(output, (up ? "UP " : "DOWN ") + (cfg.durationSeconds * 1000) + " " + auth() + (up ? "" : " " + String.format(Locale.US, "%.3f", Math.max(0, cfg.rateMbps / cfg.streams))));
            requireOk(input);
            int chunkSize = cfg.rateMbps > 0 ? Math.max(1200, Math.min(64 * 1024, (int) (cfg.rateMbps * 1e6 / 8 / 20))) : 64 * 1024;
            byte[] buffer = new byte[chunkSize];
            if (up) {
                java.util.Arrays.fill(buffer, (byte) 0x5A);
                while (active.get() && ns() < deadlineNs) {
                    limiter.acquire(buffer.length, active, deadlineNs);
                    if (!active.get() || ns() >= deadlineNs) break;
                    output.write(buffer);
                    ulBytes.addAndGet(buffer.length);
                }
            } else {
                while (active.get() && ns() < deadlineNs) {
                    try {
                        int n = input.read(buffer);
                        if (n < 0) break;
                        dlBytes.addAndGet(n);
                        // Protocol intentionally does not throttle DL at the client; server rate limiting is requested separately.
                    } catch (SocketTimeoutException timeout) { /* allow cancellation and server latency */ }
                }
            }
        } finally {
            unregister(socket);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
    private String auth() { return cfg.token == null || cfg.token.isEmpty() ? "-" : cfg.token; }
    private static long sessionHash(String session) {
        CRC32 crc = new CRC32();
        crc.update(session.getBytes(StandardCharsets.US_ASCII));
        return crc.getValue();
    }
    private void udpUpload(int worker, RateLimiter limiter) throws IOException {
        DatagramSocket d = new DatagramSocket();
        register(d);
        String sid = UUID.randomUUID().toString().replace("-", "");
        try {
            byte[] hello = ("HELLO " + sid + " " + auth()).getBytes(StandardCharsets.US_ASCII);
            d.send(new DatagramPacket(hello, hello.length, serverIp, cfg.port));
            byte[] payload = new byte[1200];
            ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
            long seq = 0;
            while (active.get() && ns() < deadlineNs) {
                limiter.acquire(payload.length, active, deadlineNs);
                if (!active.get() || ns() >= deadlineNs) break;
                bb.put(0, (byte) 'N'); bb.put(1, (byte) 'P'); bb.put(2, (byte) 'T'); bb.put(3, (byte) '1');
                bb.putLong(4, seq++);
                bb.putLong(12, ns());
                bb.putInt(20, (int) sessionHash(sid));
                d.send(new DatagramPacket(payload, payload.length, serverIp, cfg.port));
                ulBytes.addAndGet(payload.length);
            }
        } finally {
            unregister(d);
            d.close();
        }
        // Fetch server-side UDP loss/Jitter when the transfer completes; this is best effort.
        if (active.get()) {
            try { Thread.sleep(150); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            try (Socket stats = connect()) {
                sendLine(stats.getOutputStream(), "STAT " + sid + " " + auth());
                String response = readLine(stats.getInputStream());
                String[] values = response.split(" ");
                if (values.length == 4 && values[0].equals("STATS")) {
                    long received = Long.parseLong(values[1]);
                    long lost = Long.parseLong(values[2]);
                    long microJitter = Math.round(Double.parseDouble(values[3]) * 1000);
                    udpPackets.addAndGet(received);
                    udpLost.addAndGet(lost);
                    jitterWeighted.addAndGet(microJitter * received);
                    jitterCount.addAndGet(received);
                }
            } catch (Exception ignored) { /* stats are optional */ }
        }
    }
    private void udpDownload(int worker, RateLimiter ignored) throws IOException {
        DatagramSocket d = new DatagramSocket();
        register(d);
        String sid = UUID.randomUUID().toString().replace("-", "");
        long hash = sessionHash(sid);
        try {
            d.setSoTimeout(350);
            byte[] hello = ("HELLO " + sid + " " + auth()).getBytes(StandardCharsets.US_ASCII);
            d.send(new DatagramPacket(hello, hello.length, serverIp, cfg.port));
            Socket command = connect();
            try {
                sendLine(command.getOutputStream(), "UDP_DOWN " + sid + " " +
                        (cfg.durationSeconds * 1000) + " " +
                        String.format(Locale.US, "%.3f", Math.max(0.1, cfg.rateMbps / cfg.streams)) + " 1200 " + auth());
                requireOk(command.getInputStream());
                byte[] data = new byte[2048];
                long max = -1, received = 0, previousSent = 0, previousArrival = 0;
                double jitter = 0;
                while (active.get() && ns() < deadlineNs + 200_000_000L) {
                    DatagramPacket incoming = new DatagramPacket(data, data.length);
                    try { d.receive(incoming); }
                    catch (SocketTimeoutException timeout) { continue; }
                    if (incoming.getLength() < 24) continue;
                    ByteBuffer bb = ByteBuffer.wrap(data, 0, incoming.getLength()).order(ByteOrder.BIG_ENDIAN);
                    if (bb.get() != 'N' || bb.get() != 'P' || bb.get() != 'T' || bb.get() != '1') continue;
                    long seq = bb.getLong(), sentNs = bb.getLong(), packetHash = Integer.toUnsignedLong(bb.getInt());
                    if (packetHash != hash) continue;
                    long arrival = ns();
                    if (seq > max) max = seq;
                    received++;
                    if (previousArrival != 0) {
                        long difference = Math.abs((arrival - previousArrival) - (sentNs - previousSent));
                        jitter += ((difference / 1e6) - jitter) / 16;
                    }
                    previousSent = sentNs;
                    previousArrival = arrival;
                    dlBytes.addAndGet(incoming.getLength());
                }
                long lost = Math.max(0, max + 1 - received);
                udpPackets.addAndGet(received);
                udpLost.addAndGet(lost);
                jitterWeighted.addAndGet(Math.round(jitter * 1000 * received));
                jitterCount.addAndGet(received);
            } finally {
                unregister(command);
                command.close();
            }
        } finally {
            unregister(d);
            d.close();
        }
    }
}
