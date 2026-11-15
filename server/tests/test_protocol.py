import importlib.util
import pathlib
import socket
import struct
import threading
import time
import unittest
import uuid
import zlib

PATH = pathlib.Path(__file__).resolve().parents[1] / 'netpump_server.py'
spec = importlib.util.spec_from_file_location('netpump_server', PATH)
module = importlib.util.module_from_spec(spec)
import sys
sys.modules[spec.name] = module
spec.loader.exec_module(module)

class TestProtocol(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = module.Server(('127.0.0.1', 0), 'unittest')
        cls.port = cls.srv.server_address[1]
        cls.thread = threading.Thread(target=cls.srv.serve_forever, daemon=True)
        cls.thread.start()
    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown(); cls.srv.server_close()
        cls.thread.join(2)

    def control(self, line):
        s = socket.create_connection(('127.0.0.1', self.port), 2)
        s.settimeout(2)
        s.sendall((line + '\n').encode())
        first = b''
        while not first.endswith(b'\n'):
            chunk = s.recv(1)
            if not chunk: break
            first += chunk
        return s, first
    def test_ping(self):
        s, reply = self.control('PING unittest')
        self.assertEqual(reply, b'PONG\n')
        s.close()
    def test_tcp_up(self):
        s, reply = self.control('UP 1000 unittest')
        self.assertEqual(reply, b'OK\n')
        s.sendall(b'x' * 8192)
        s.close()
    def test_tcp_down(self):
        s, reply = self.control('DOWN 1000 unittest 10')
        self.assertEqual(reply, b'OK\n')
        self.assertTrue(len(s.recv(8192)) > 0)
        s.close()
    def test_auth(self):
        s, reply = self.control('UP 1000 badpass')
        self.assertEqual(reply, b'ERR AUTH\n')
        s.close()
    def test_udp_up_stats(self):
        sid = uuid.uuid4().hex
        crc = zlib.crc32(sid.encode())
        d = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        d.sendto(f'HELLO {sid} unittest'.encode(), ('127.0.0.1', self.port))
        time.sleep(.05)
        for i in (0,1,3):
            payload = bytearray(1200)
            struct.pack_into('>4sQQI', payload, 0, b'NPT1', i, time.monotonic_ns(), crc)
            d.sendto(payload, ('127.0.0.1', self.port))
        time.sleep(.1)
        s, reply = self.control(f'STAT {sid} unittest')
        self.assertEqual(reply.split()[:3], [b'STATS', b'3', b'1'])
        s.close(); d.close()
    def test_udp_down(self):
        sid = uuid.uuid4().hex
        d = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        d.bind(('127.0.0.1', 0)); d.settimeout(2)
        d.sendto(f'HELLO {sid} unittest'.encode(), ('127.0.0.1', self.port))
        s, reply = self.control(f'UDP_DOWN {sid} 1000 1 1200 unittest')
        self.assertEqual(reply, b'OK\n')
        packet, addr = d.recvfrom(2048)
        self.assertEqual(packet[:4], b'NPT1')
        self.assertEqual(len(packet), 1200)
        self.assertEqual(struct.unpack_from('>I', packet, 20)[0], zlib.crc32(sid.encode()))
        d.close(); s.close()
if __name__ == '__main__': unittest.main()
