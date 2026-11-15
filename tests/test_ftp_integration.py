"""Standalone local FTP mock for testing FTP RETR/EPSV concurrency (no real server traffic)."""
import socket
import subprocess
import tempfile
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "app/src/main/java/com/netpump/engineering/FtpRunner.java"
HARNESS = Path(__file__).with_name("FtpHarness.java")


def serve(sock):
    try:
        sock.settimeout(10)
        reader = sock.makefile("rb")
        sock.sendall(b"220 Mock FTP ready\r\n")
        data_listener = None
        while True:
            raw = reader.readline()
            if not raw:
                break
            line = raw.decode("utf-8").strip()
            cmd = line.split(" ", 1)[0].upper()
            if cmd == "USER":
                sock.sendall(b"331 Password required\r\n")
            elif cmd == "PASS":
                sock.sendall(b"230 Welcome\r\n")
            elif cmd == "TYPE":
                sock.sendall(b"200 Binary\r\n")
            elif cmd == "SIZE":
                sock.sendall(b"213 21474836480\r\n")
            elif cmd == "EPSV":
                data_listener = socket.socket()
                data_listener.bind(("127.0.0.1", 0))
                data_listener.listen(1)
                data_listener.settimeout(8)
                port = data_listener.getsockname()[1]
                sock.sendall(f"229 Entering Extended Passive Mode (|||{port}|)\r\n".encode())
            elif cmd == "RETR":
                sock.sendall(b"150 Opening data\r\n")
                data, _ = data_listener.accept()
                data.settimeout(2)
                try:
                    chunk = b"F" * 65536
                    for _ in range(10000):
                        data.sendall(chunk)
                except (OSError, socket.timeout):
                    pass
                finally:
                    data.close()
                    data_listener.close()
                    data_listener = None
                try:
                    sock.sendall(b"226 Transfer completed\r\n")
                except OSError:
                    pass
            else:
                sock.sendall(b"502 Not implemented\r\n")
    except OSError:
        pass
    finally:
        sock.close()


def main():
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen()
    listener.settimeout(1)
    port = listener.getsockname()[1]
    stopped = threading.Event()

    def accept_loop():
        while not stopped.is_set():
            try:
                s, _ = listener.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            threading.Thread(target=serve, args=(s,), daemon=True).start()

    t = threading.Thread(target=accept_loop, daemon=True)
    t.start()
    try:
        with tempfile.TemporaryDirectory() as temp:
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", temp, str(RUNNER), str(HARNESS)], check=True)
            subprocess.run(["java", "-cp", temp, "FtpHarness", str(port)], check=True)
    finally:
        stopped.set()
        listener.close()


if __name__ == "__main__":
    main()
