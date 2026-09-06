#!/usr/bin/env python3
"""Linux-only local console bridge; no TCP, RCON, passwords or remote access.

The OS account running the server (and root) is the console authority. A queued
acknowledgment means only that stdin delivery was queued, NOT command success.
"""
from __future__ import annotations

import argparse
from collections import deque
import errno
import fcntl
import json
import os
from pathlib import Path
import select
import signal
import socket
import stat
import struct
import subprocess
import sys
import time
import unicodedata

MAX_COMMAND = 2048  # Entire command + newline fits within Linux PIPE_BUF.
MAX_PENDING = 32
REQUEST_SECONDS = 2.0


def command_bytes(command: str) -> bytes:
    if not isinstance(command, str) or not command.strip():
        raise ValueError('Command must not be empty')
    if command != command.strip() or command.startswith('/'):
        raise ValueError('Use a console command without surrounding space or leading slash')
    if any(unicodedata.category(ch).startswith('C') or ch in '\u2028\u2029' for ch in command):
        raise ValueError('Control, formatting and line-separator characters are forbidden')
    data = command.encode('utf-8', errors='strict')
    if len(data) > MAX_COMMAND:
        raise ValueError('Command is too large')
    return data


def exact_read(connection: socket.socket, count: int, deadline: float) -> bytes:
    data = bytearray()
    while len(data) < count:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Console request timed out')
        connection.settimeout(remaining)
        chunk = connection.recv(count - len(data))
        if not chunk:
            raise ValueError('Truncated console request')
        data.extend(chunk)
    return bytes(data)


def receive_frame(connection: socket.socket, limit: int = MAX_COMMAND) -> bytes:
    deadline = time.monotonic() + REQUEST_SECONDS
    size = struct.unpack('!I', exact_read(connection, 4, deadline))[0]
    if not 0 < size <= limit:
        raise ValueError('Invalid console frame length')
    return exact_read(connection, size, deadline)


def send_frame(connection: socket.socket, payload: bytes) -> None:
    connection.settimeout(REQUEST_SECONDS)
    connection.sendall(struct.pack('!I', len(payload)) + payload)


def peer_uid(connection: socket.socket) -> int:
    if not hasattr(socket, 'SO_PEERCRED'):
        raise OSError('Linux SO_PEERCRED is required')
    return struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))[1]


def permitted_peer(uid: int, owner: int) -> bool:
    return uid in (0, owner)


def private_directory(path: Path, expected_owner: int | None = None) -> tuple[int, int]:
    """Pin a private, non-symlink directory; subsequent filesystem operations use fd."""
    path = Path(os.path.abspath(path))
    # Reject symlink ancestors too: the documented location is /run/warland.
    for ancestor in (path, *path.parents):
        info = ancestor.lstat()
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
            raise ValueError('Console directory ancestry must contain only real directories')
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        info = os.fstat(descriptor)
        if stat.S_IMODE(info.st_mode) != 0o700:
            raise ValueError('Console directory must have mode 0700')
        if expected_owner is not None and info.st_uid != expected_owner:
            raise ValueError('Console directory has the wrong owner')
        return descriptor, info.st_uid
    except BaseException:
        os.close(descriptor)
        raise


class PrivateListener:
    def __init__(self, path: Path):
        self.directory = -1
        self.lock = -1
        self.socket = None
        self.identity = None
        self.name = path.name
        if not self.name or self.name in ('.', '..') or len(os.fsencode(path)) > 100:
            raise ValueError('Invalid or oversized console socket path')
        try:
            self.directory, owner = private_directory(path.parent, os.geteuid())
            self.lock = os.open(self.name + '.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC,
                                0o600, dir_fd=self.directory)
            info = os.fstat(self.lock)
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != owner
                    or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1):
                raise ValueError('Unsafe console lock file')
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                os.stat(self.name, dir_fd=self.directory, follow_symlinks=False)
            except FileNotFoundError:
                pass
            else:
                raise FileExistsError('Console socket path already exists; nothing was removed')
            self.socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            old_umask = os.umask(0o177)
            try:
                self.socket.bind(f'/proc/self/fd/{self.directory}/{self.name}')
            finally:
                os.umask(old_umask)
            info = os.stat(self.name, dir_fd=self.directory, follow_symlinks=False)
            self.identity = (info.st_dev, info.st_ino)
            if not stat.S_ISSOCK(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
                raise ValueError('Console socket permissions were not applied')
            self.socket.listen(8)
            self.socket.setblocking(False)
        except BaseException:
            self.close()
            raise

    def close(self):
        if self.socket is not None:
            self.socket.close()
            self.socket = None
        if self.identity is not None and self.directory >= 0:
            try:
                info = os.stat(self.name, dir_fd=self.directory, follow_symlinks=False)
                if (info.st_dev, info.st_ino) == self.identity:
                    os.unlink(self.name, dir_fd=self.directory)
            except FileNotFoundError:
                pass
            self.identity = None
        if self.lock >= 0:
            os.close(self.lock)
            self.lock = -1
        if self.directory >= 0:
            os.close(self.directory)
            self.directory = -1


class PendingCommands:
    def __init__(self):
        self.queue = deque()
        self.partial = False

    def add(self, data: bytes):
        if len(self.queue) >= MAX_PENDING:
            raise ValueError('Console queue is full')
        self.queue.append(data + b'\n')

    def stop(self):
        # Finish an already-partially-written line; discard commands not started.
        keep = self.queue[0] if self.partial and self.queue else None
        self.queue.clear()
        if keep is not None:
            self.queue.append(keep)
        self.queue.append(b'stop\n')

    def flush_one(self, descriptor: int):
        if not self.queue:
            return
        try:
            count = os.write(descriptor, self.queue[0])
        except BlockingIOError:
            return
        if count <= 0:
            raise BrokenPipeError('Child stdin closed')
        if count == len(self.queue[0]):
            self.queue.popleft()
            self.partial = False
        else:
            self.queue[0] = self.queue[0][count:]
            self.partial = True


def serve(path: Path, cwd: Path, executable: list[str], grace: float = 60) -> int:
    if os.geteuid() == 0:
        raise ValueError('Run Minecraft as its dedicated non-root service account')
    if not executable or not 1 <= grace <= 120:
        raise ValueError('Specify a child executable and grace period of 1..120 seconds')
    listener = PrivateListener(path)
    pending = PendingCommands()
    process = None
    interrupted = False
    previous = {}

    def interrupt(_signal, _frame):
        nonlocal interrupted
        interrupted = True

    try:
        for sig in (signal.SIGTERM, signal.SIGINT):
            previous[sig] = signal.signal(sig, interrupt)
        # No shell, no environment rewriting, and no authlib overrides.
        process = subprocess.Popen(executable, cwd=cwd, stdin=subprocess.PIPE, start_new_session=True)
        descriptor = process.stdin.fileno()
        os.set_blocking(descriptor, False)
        stop_at = None
        while process.poll() is None:
            if interrupted and stop_at is None:
                pending.stop()
                stop_at = time.monotonic() + grace
            if stop_at is not None and time.monotonic() >= stop_at:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
                return 124  # Forced termination is NOT a clean Minecraft save.
            readable, writable, _ = select.select(
                [listener.socket] if stop_at is None else [],
                [descriptor] if pending.queue else [], [], .2)
            if writable:
                pending.flush_one(descriptor)
            if readable:
                connection, _ = listener.socket.accept()
                with connection:
                    response = {'status': 'rejected'}
                    try:
                        if not permitted_peer(peer_uid(connection), os.geteuid()):
                            raise PermissionError('Console peer is not the service account or root')
                        payload = receive_frame(connection)
                        canonical = command_bytes(payload.decode('utf-8', errors='strict'))
                        if process.poll() is not None or interrupted:
                            raise ValueError('Server is stopping')
                        pending.add(canonical)
                        response = {'status': 'queued', 'command_executed': False}
                    except (OSError, ValueError, UnicodeError):
                        # Do not echo commands or include them in logs/errors.
                        pass
                    try:
                        send_frame(connection, json.dumps(response).encode('ascii'))
                    except OSError:
                        pass  # Do not retry: a command might already have been queued.
        return process.returncode if process.returncode >= 0 else 128 - process.returncode
    finally:
        if process is not None:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
            process.stdin.close()
        listener.close()
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def send(path: Path, command: str) -> dict:
    payload = command_bytes(command)
    directory, owner = private_directory(path.parent)
    try:
        info = os.stat(path.name, dir_fd=directory, follow_symlinks=False)
        if (not stat.S_ISSOCK(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600
                or info.st_uid != owner):
            raise ValueError('Unsafe console socket')
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
            connection.settimeout(REQUEST_SECONDS)
            connection.connect(f'/proc/self/fd/{directory}/{path.name}')
            if peer_uid(connection) != owner:
                raise PermissionError('Unexpected console server identity')
            send_frame(connection, payload)
            result = json.loads(receive_frame(connection, 1024))
            if not isinstance(result, dict) or result.get('status') not in ('queued', 'rejected'):
                raise ValueError('Invalid console acknowledgment')
            return result
    finally:
        os.close(directory)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    service = sub.add_parser('serve')
    service.add_argument('--socket', type=Path, required=True)
    service.add_argument('--cwd', type=Path, required=True)
    service.add_argument('--grace', type=float, default=60)
    service.add_argument('executable', nargs=argparse.REMAINDER)
    client = sub.add_parser('send')
    client.add_argument('--socket', type=Path, required=True)
    client.add_argument('command')
    args = parser.parse_args(argv)
    try:
        if args.action == 'serve':
            executable = args.executable[1:] if args.executable[:1] == ['--'] else args.executable
            return serve(args.socket.absolute(), args.cwd.absolute(), executable, args.grace)
        result = send(args.socket.absolute(), args.command)
        print(json.dumps(result))
        return 0 if result['status'] == 'queued' else 2
    except (OSError, ValueError, UnicodeError):
        print('Private console failed. Inspect service health and permissions; do not blindly retry a mutation.', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
