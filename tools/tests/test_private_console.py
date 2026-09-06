import json
import os
from pathlib import Path
import signal
import socket
import stat
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import private_console as console


class CommandTests(unittest.TestCase):
    def test_russian_console_command(self):
        self.assertEqual(console.command_bytes('say Проверка'), 'say Проверка'.encode())

    def test_forbidden_controls_and_multiple_commands(self):
        for text in ('', '  ', ' stop', 'stop ', '/stop', 'list\nstop', 'list\rstop',
                     'say\x00x', 'say\tx', 'say\x7fx', 'say\u2028stop', 'say\u2029stop',
                     'say\u200bhidden', 'say\ud800'):
            with self.subTest(text=repr(text)), self.assertRaises((ValueError, UnicodeError)):
                console.command_bytes(text)

    def test_byte_bound_is_not_character_bound(self):
        self.assertEqual(len(console.command_bytes('я' * 1024)), 2048)
        for value in ('я' * 1025, 'a' * 2049):
            with self.assertRaises(ValueError):
                console.command_bytes(value)

    def test_peer_authority_is_os_identity(self):
        self.assertTrue(console.permitted_peer(0, 1001))
        self.assertTrue(console.permitted_peer(1001, 1001))
        self.assertFalse(console.permitted_peer(1002, 1001))


class FrameTests(unittest.TestCase):
    def test_fragmented_frame(self):
        left, right = socket.socketpair()
        with left, right:
            def writer():
                for fragment in (b'\x00', b'\x00\x00', b'\x04li', b'st'):
                    left.sendall(fragment)
                    time.sleep(.005)
            worker = threading.Thread(target=writer)
            worker.start()
            self.assertEqual(console.receive_frame(right), b'list')
            worker.join(timeout=1)

    def test_truncated_header_and_body(self):
        for payload in (b'\x00', struct.pack('!I', 4) + b'li'):
            left, right = socket.socketpair()
            with left, right:
                left.sendall(payload)
                left.shutdown(socket.SHUT_WR)
                with self.assertRaises(ValueError):
                    console.receive_frame(right)

    def test_invalid_lengths_rejected_before_body(self):
        for length in (0, 2049, 2**32-1):
            left, right = socket.socketpair()
            with left, right:
                left.sendall(struct.pack('!I', length))
                with self.assertRaises(ValueError):
                    console.receive_frame(right)

    def test_timeout_is_absolute(self):
        left, right = socket.socketpair()
        with left, right, patch.object(console, 'REQUEST_SECONDS', .03):
            start = time.monotonic()
            with self.assertRaises(TimeoutError):
                console.receive_frame(right)
            self.assertLess(time.monotonic() - start, .5)

    def test_live_peer_credentials(self):
        left, right = socket.socketpair()
        with left, right:
            self.assertEqual(console.peer_uid(right), os.geteuid())


class DirectoryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='wlc-')
        self.root = Path(self.temporary.name)
        self.root.chmod(0o700)
        self.path = self.root / 'console.sock'

    def tearDown(self):
        self.temporary.cleanup()

    def test_bad_mode_and_owner_fail_closed(self):
        self.root.chmod(0o750)
        with self.assertRaises(ValueError):
            console.PrivateListener(self.path)
        self.root.chmod(0o700)
        with self.assertRaises(ValueError):
            console.private_directory(self.root, os.geteuid()+1)

    def test_symlink_directory_is_rejected(self):
        link = self.root / 'link'
        real = self.root / 'real'
        real.mkdir(mode=0o700)
        link.symlink_to(real, target_is_directory=True)
        with self.assertRaises(ValueError):
            console.PrivateListener(link / 'console.sock')

    def test_existing_path_is_never_deleted(self):
        self.path.write_text('preserve')
        with self.assertRaises(FileExistsError):
            console.PrivateListener(self.path)
        self.assertEqual(self.path.read_text(), 'preserve')

    def test_lock_symlink_and_hardlink_are_rejected(self):
        target = self.root / 'target'
        target.write_text('preserve')
        target.chmod(0o600)
        lock = self.root / 'console.sock.lock'
        lock.symlink_to(target)
        with self.assertRaises(OSError):
            console.PrivateListener(self.path)
        lock.unlink()
        os.link(target, lock)
        with self.assertRaises(ValueError):
            console.PrivateListener(self.path)
        self.assertEqual(target.read_text(), 'preserve')

    def test_socket_mode_single_instance_and_cleanup(self):
        listener = console.PrivateListener(self.path)
        try:
            self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
            with self.assertRaises(BlockingIOError):
                console.PrivateListener(self.path)
            self.assertTrue(self.path.exists())
        finally:
            listener.close()
            listener.close()
        self.assertFalse(self.path.exists())

    def test_cleanup_does_not_delete_replacement(self):
        listener = console.PrivateListener(self.path)
        self.path.unlink()
        self.path.write_text('replacement')
        listener.close()
        self.assertEqual(self.path.read_text(), 'replacement')

    def test_client_refuses_non_socket(self):
        self.path.write_text('not a socket')
        self.path.chmod(0o600)
        with self.assertRaises(ValueError):
            console.send(self.path, 'list')


class QueueTests(unittest.TestCase):
    def test_bounded_queue(self):
        queue = console.PendingCommands()
        for _ in range(console.MAX_PENDING):
            queue.add(b'list')
        with self.assertRaises(ValueError):
            queue.add(b'list')

    def test_backpressure_and_partial_write_preserve_lines(self):
        queue = console.PendingCommands()
        queue.add(b'say test')
        with patch.object(console.os, 'write', side_effect=BlockingIOError):
            queue.flush_one(42)
        self.assertEqual(queue.queue[0], b'say test\n')
        with patch.object(console.os, 'write', return_value=3):
            queue.flush_one(42)
        queue.add(b'do not execute')
        queue.stop()
        self.assertEqual(list(queue.queue), [b' test\n', b'stop\n'])
        with patch.object(console.os, 'write', return_value=6):
            queue.flush_one(42)
        self.assertFalse(queue.partial)
        self.assertEqual(list(queue.queue), [b'stop\n'])

    def test_shutdown_drops_not_started_commands(self):
        queue = console.PendingCommands()
        queue.add(b'list')
        queue.stop()
        self.assertEqual(list(queue.queue), [b'stop\n'])

    def test_empty_write_is_failure(self):
        queue = console.PendingCommands()
        queue.add(b'list')
        with patch.object(console.os, 'write', return_value=0), self.assertRaises(BrokenPipeError):
            queue.flush_one(42)


class LifecycleTests(unittest.TestCase):
    def test_root_service_is_rejected(self):
        with patch.object(console.os, 'geteuid', return_value=0), self.assertRaises(ValueError):
            console.serve(Path('/unused/socket'), Path('/unused'), ['java'])

    @unittest.skipIf(os.geteuid() == 0, 'Service deliberately refuses root; run suite as build user')
    def test_real_subprocess_stdin_and_graceful_shutdown(self):
        with tempfile.TemporaryDirectory(prefix='wlc-live-') as temporary:
            root = Path(temporary)
            root.chmod(0o700)
            path = root / 'console.sock'
            marker = root / 'commands.txt'
            child = ("import sys\nfrom pathlib import Path\n"
                     "for line in sys.stdin:\n"
                     " with Path('commands.txt').open('a') as f: f.write(line)\n"
                     " if line.strip() == 'stop': break\n")
            with (root/'bridge.log').open('w') as log:
                process = subprocess.Popen([sys.executable, str(Path(console.__file__).resolve()),
                    'serve', '--socket', str(path), '--cwd', str(root), '--grace', '2', '--',
                    sys.executable, '-u', '-c', child], stdout=log, stderr=log)
                try:
                    deadline = time.monotonic()+8
                    while not path.exists() and process.poll() is None and time.monotonic()<deadline:
                        time.sleep(.02)
                    self.assertTrue(path.exists(), (root/'bridge.log').read_text())
                    result = console.send(path, 'say Проверка')
                    self.assertEqual(result, {'status': 'queued', 'command_executed': False})
                    # Invalid raw protocol input must not become a second stdin command.
                    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
                        sock.connect(str(path))
                        console.send_frame(sock, b'list\nstop')
                        self.assertEqual(json.loads(console.receive_frame(sock)), {'status': 'rejected'})
                    while (not marker.exists() or 'Проверка' not in marker.read_text()) and time.monotonic()<deadline:
                        time.sleep(.02)
                    self.assertEqual(marker.read_text(), 'say Проверка\n')
                    process.send_signal(signal.SIGTERM)
                    self.assertEqual(process.wait(timeout=8), 0, (root/'bridge.log').read_text())
                    self.assertEqual(marker.read_text(), 'say Проверка\nstop\n')
                    self.assertFalse(path.exists())
                finally:
                    if process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=8)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=3)


if __name__ == '__main__':
    unittest.main()
