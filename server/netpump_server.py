#!/usr/bin/env python3
"""NetPump 1.0 test peer. Uses standard Python only. Explicit traffic generator."""
import argparse
import socket
import socketserver
import struct
import threading
import time
import zlib
from dataclasses import dataclass, field

MAGIC = b"NPT1"
BUF = b"\x5a" * 65536
MAX_SEC = 600
MAX_MBPS = 2000.0

@dataclass
class Session:
    ip_port: tuple
    received: int = 0
    max_seq: int = -1
    last_sent: int = 0
    last_arrival: int = 0
    jitter_ms: float = 0
    last_access: float = field(default_factory=time.monotonic)
    lock: threading.Lock = field(default_factory=threading.Lock)

    def on_packet(self, seq, sent_ns):
        now_ns = time.monotonic_ns()
        with self.lock:
            if self.last_arrival:
                delta = abs((now_ns - self.last_arrival) - (sent_ns - self.last_sent)) / 1e6
                self.jitter_ms += (delta - self.jitter_ms) / 16
            self.last_arrival, self.last_sent = now_ns, sent_ns
            self.received += 1
            self.max_seq = max(self.max_seq, seq)
            self.last_access = time.monotonic()

    def stats(self):
        with self.lock:
            lost = max(0, self.max_seq + 1 - self.received)
            return f"STATS {self.received} {lost} {self.jitter_ms:.3f}\n".encode()

class UDPThread(threading.Thread):
    def __init__(self, udp, sessions, lock, token):
        super().__init__(daemon=True)
        self.udp = udp
        self.sessions = sessions
        self.lock = lock
        self.token = token
        self.running = True

    def run(self):
        self.udp.settimeout(1)
        last_clean = time.monotonic()
        while self.running:
            try:
                data, addr = self.udp.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break
            if data.startswith(b"HELLO "):
                try:
                    _, sid, auth = data.decode("ascii").split(" ")
                    if len(sid) != 32 or any(c not in '0123456789abcdef' for c in sid):
                        continue
                    if auth != self.token: continue
                    key = zlib.crc32(sid.encode("ascii"))
                    with self.lock:
                        self.sessions[key] = Session(addr)
                except (UnicodeError, ValueError):
                    continue
            elif len(data) >= 24 and data[:4] == MAGIC:
                _, seq, stamp, key = struct.unpack_from(">4sQQI", data)
                with self.lock:
                    sess = self.sessions.get(key)
                if sess and sess.ip_port == addr:
                    sess.on_packet(seq, stamp)
            now = time.monotonic()
            if now - last_clean > 60:
                with self.lock:
                    for key in list(self.sessions):
                        if now - self.sessions[key].last_access > 900:
                            self.sessions.pop(key, None)
                last_clean = now

class Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 64

    def __init__(self, address, token):
        super().__init__(address, Handler)
        self.token = token or "-"
        self.sessions = {}
        self.lock = threading.Lock()
        self.udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.udp_socket.bind(self.server_address)
        self.listener = UDPThread(self.udp_socket, self.sessions, self.lock, self.token)
        self.listener.start()

    def server_close(self):
        self.listener.running = False
        self.udp_socket.close()
        super().server_close()

class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.request.settimeout(2.0)
        try:
            line = self.rfile.readline(256).decode("ascii", "strict").strip().split()
            if not line: return
            operation = line[0]
            if operation == "PING":
                if len(line) != 2 or line[1] != self.server.token:
                    return self.wfile.write(b"ERR AUTH\n")
                self.wfile.write(b"PONG\n")
            elif operation in ("UP", "DOWN"):
                if len(line) < 3 or line[2] != self.server.token:
                    return self.wfile.write(b"ERR AUTH\n")
                duration = min(MAX_SEC * 1000, max(1000, int(line[1]))) / 1000
                rate = min(MAX_MBPS, max(0, float(line[3]))) if operation == "DOWN" and len(line) > 3 else 0
                self.wfile.write(b"OK\n"); self.wfile.flush()
                deadline = time.monotonic() + duration
                if operation == "UP":
                    self.request.settimeout(.4)
                    while time.monotonic() < deadline:
                        try:
                            if not self.request.recv(131072): break
                        except socket.timeout:
                            continue
                else:
                    self.request.settimeout(2)
                    self._tcp_down(deadline, rate)
            elif operation == "STAT":
                if len(line) != 3 or line[2] != self.server.token:
                    return self.wfile.write(b"ERR AUTH\n")
                key = zlib.crc32(line[1].encode('ascii'))
                with self.server.lock:
                    session = self.server.sessions.get(key)
                if session is None:
                    return self.wfile.write(b"ERR NO_SESSION\n")
                self.wfile.write(session.stats())
            elif operation == "UDP_DOWN":
                if len(line) != 6 or line[5] != self.server.token:
                    return self.wfile.write(b"ERR AUTH\n")
                sid = line[1]
                duration = min(MAX_SEC * 1000, max(1000, int(line[2]))) / 1000
                mbps = min(MAX_MBPS, max(0.1, float(line[3])))
                length = min(1400, max(64, int(line[4])))
                key = zlib.crc32(sid.encode("ascii"))
                session = None
                for _ in range(10):
                    with self.server.lock:
                        session = self.server.sessions.get(key)
                    if session: break
                    time.sleep(.02)
                if not session or session.ip_port[0] != self.client_address[0]:
                    return self.wfile.write(b"ERR HELLO_REQUIRED\n")
                self.wfile.write(b"OK\n"); self.wfile.flush()
                self._udp_down(session.ip_port, key, duration, mbps, length)
            else:
                self.wfile.write(b"ERR UNSUPPORTED\n")
        except (ConnectionError, OSError, ValueError, UnicodeError):
            pass

    def _tcp_down(self, deadline, rate):
        block = BUF[:max(1200, min(len(BUF), int(rate * 1e6 / 8 / 20)))] if rate else BUF
        tokens = 0.0
        previous = time.monotonic()
        while time.monotonic() < deadline:
            if rate:
                now = time.monotonic()
                speed = rate * 1e6 / 8
                tokens = min(max(len(block), speed / 10), tokens + (now - previous) * speed)
                previous = now
                if tokens < len(block):
                    time.sleep(min(.01, (len(block) - tokens) / speed))
                    continue
                tokens -= len(block)
            try: self.request.sendall(block)
            except (ConnectionResetError, BrokenPipeError, OSError): break

    def _udp_down(self, addr, key, duration, mbps, size):
        p = bytearray(size)
        speed = mbps * 1e6 / 8
        tokens = size
        previous = time.monotonic()
        deadline = previous + duration
        seq = 0
        while time.monotonic() < deadline:
            now = time.monotonic()
            tokens = min(max(size, speed / 10), tokens + (now - previous) * speed)
            previous = now
            if tokens < size:
                time.sleep(min(.005, (size - tokens) / speed))
                continue
            tokens -= size
            struct.pack_into(">4sQQI", p, 0, MAGIC, seq, time.monotonic_ns(), key)
            try: self.server.udp_socket.sendto(p, addr)
            except OSError: break
            seq += 1

def main():
    a = argparse.ArgumentParser(description='NetPump mobile test peer (TCP + UDP on same port)')
    a.add_argument('--bind', default='127.0.0.1', help='Listen IP. Use 0.0.0.0 for LAN/WAN (firewall required).')
    a.add_argument('--port', type=int, default=5201)
    a.add_argument('--token', default='', help='Shared secret, strongly recommended when exposed to the Internet')
    args = a.parse_args()
    if not 1 <= args.port <= 65535: a.error('Invalid port')
    if args.token and (len(args.token) > 64 or not all(c in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-' for c in args.token)):
        a.error('Token must be ASCII letters, digits, _ or - (max 64 chars)')
    if args.bind == '0.0.0.0' and not args.token:
        print('WARNING: listening on all interfaces without a token. Restrict firewall access.', flush=True)
    with Server((args.bind, args.port), args.token) as svr:
        print(f'NetPump listening on {args.bind}:{args.port} TCP/UDP (Ctrl+C to stop)', flush=True)
        try: svr.serve_forever(poll_interval=.2)
        except KeyboardInterrupt: pass

if __name__ == '__main__': main()
