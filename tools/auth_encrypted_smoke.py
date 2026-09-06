#!/usr/bin/env python3
"""Synthetic loopback-only encrypted WarLand auth acceptance; never production/Mojang QA.
Requires Python 3.11+, cryptography, a stopped empty synthetic Fabric 1.21.11 fixture.
No credentials, identity overrides, or game settings are written outside --out.
"""
import argparse
import base64
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import sqlite3
import struct
import subprocess
import threading
import time
import urllib.parse
import uuid
import zipfile

MAX_FRAME = 2097152
NAME = 'WlSyntheticQA'
IDENTITY = uuid.UUID('8f361bb6-d00e-4caf-9437-f91b8cd0a708')


def varint(n):
    if not 0 <= n <= 0x7fffffff:
        raise ValueError('VarInt outside supported range')
    out = bytearray()
    while True:
        b = n & 127
        n >>= 7
        out.append(b | (128 if n else 0))
        if not n:
            return bytes(out)


def read_vi(data, offset=0):
    n = 0
    for i in range(5):
        x = data[offset + i]
        n |= (x & 127) << (7 * i)
        if x < 128:
            if n > 0x7fffffff:
                raise ValueError('VarInt overflow')
            return n, offset + i + 1
    raise ValueError('VarInt too long')


def blob(data):
    return varint(len(data)) + data


def text(value):
    return blob(value.encode('utf-8'))


def read_blob(data, offset):
    n, offset = read_vi(data, offset)
    if n > MAX_FRAME or offset + n > len(data):
        raise ValueError('Truncated or oversized field')
    return data[offset:offset + n], offset + n


def secret(value):
    data = value.encode('utf-16-be')
    if len(data) > 256:
        raise ValueError('Secret exceeds wire bound')
    return struct.pack('>H', len(data) // 2) + data


class Wire:
    def __init__(self, port):
        self.socket = socket.create_connection(('127.0.0.1', port), timeout=3)
        self.socket.settimeout(.3)
        self.pending = bytearray()
        self.encryptor = self.decryptor = None
        self.packets = 0

    def send(self, packet, body=b''):
        payload = varint(packet) + body
        framed = blob(payload)
        self.socket.sendall(self.encryptor.update(framed) if self.encryptor else framed)

    def receive(self, deadline):
        while time.monotonic() < deadline:
            try:
                length, offset = read_vi(self.pending)
            except IndexError:
                length = None
            if length is not None:
                if not 0 < length <= MAX_FRAME:
                    raise ValueError('Frame exceeds bound')
                if len(self.pending) >= length + offset:
                    body = bytes(self.pending[offset:offset + length])
                    del self.pending[:offset + length]
                    self.packets += 1
                    if self.packets > 20000:
                        raise ValueError('Packet budget exceeded')
                    packet, start = read_vi(body)
                    return packet, body[start:]
            try:
                chunk = self.socket.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                raise EOFError('Connection closed before acceptance')
            self.pending.extend(self.decryptor.update(chunk) if self.decryptor else chunk)
            if len(self.pending) > MAX_FRAME + 65541:
                raise ValueError('Receive buffer exceeds bound')
        raise TimeoutError('Absolute client deadline exceeded')

    def enable_encryption(self, key):
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        if self.pending:
            raise ValueError('Unexpected bytes before encryption transition')
        cipher = Cipher(algorithms.AES(key), modes.CFB8(key))
        self.encryptor, self.decryptor = cipher.encryptor(), cipher.decryptor()

    def close(self):
        self.socket.close()


class IdentityProvider:
    """Consumes only client-registered hash/name pairs. No arbitrary user authentication."""
    def __init__(self):
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.hazmat.primitives import serialization
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        self.public = base64.b64encode(public).decode('ascii')
        self.pending, self.lock, self.accepted = {}, threading.Lock(), 0
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass  # Never put request parameters or identities in logs.

            def do_GET(self):
                url = urllib.parse.urlsplit(self.path)
                value, status = None, 404
                if url.path == '/publickeys':
                    value = {k: [{'publicKey': owner.public}] for k in ('profilePropertyKeys', 'playerCertificateKeys')}
                    status = 200
                elif url.path == '/session/minecraft/hasJoined':
                    q = urllib.parse.parse_qs(url.query, strict_parsing=True)
                    pair = (q.get('username', [''])[0], q.get('serverId', [''])[0])
                    with owner.lock:
                        until = owner.pending.pop(pair, 0)
                        if pair[0] == NAME and until > time.monotonic():
                            owner.accepted += 1
                            value = {'id': IDENTITY.hex, 'name': NAME, 'properties': []}
                            status = 200
                        else:
                            status = 204
                data = json.dumps(value).encode() if value is not None else b''
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = 'http://127.0.0.1:' + str(self.server.server_port)

    def allow(self, server_id, key, public):
        digest = hashlib.sha1(server_id + key + public).digest()
        signed = format(int.from_bytes(digest, 'big', signed=True), 'x')
        with self.lock:
            self.pending[(NAME, signed)] = time.monotonic() + 15

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.pending.clear()


def probe(port, provider, password, mode):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    w = Wire(port)
    result = {'case': mode, 'encrypted': False, 'auth_statuses': [], 'configuration_finishes': 0,
              'play': False, 'profile_ready_message': False, 'teleport_ack': 0, 'chunk_ack': 0,
              'keepalive_ack': 0, 'balance_reply': False}
    phase, command_sent = 'login', False
    deadline = time.monotonic() + 40
    try:
        w.send(0, varint(774) + text('127.0.0.1') + struct.pack('>H', port) + varint(2))
        w.send(0, text(NAME) + IDENTITY.bytes)
        while True:
            packet, body = w.receive(deadline)
            if phase == 'login':
                if packet == 1:
                    server_id, off = read_blob(body, 0)
                    public, off = read_blob(body, off)
                    token, off = read_blob(body, off)
                    if body[off:] != b'\x01' or result['encrypted']:
                        raise ValueError('Expected online-mode encrypted login')
                    key = secrets.token_bytes(16)
                    provider.allow(server_id, key, public)
                    rsa = serialization.load_der_public_key(public)
                    w.send(1, blob(rsa.encrypt(key, padding.PKCS1v15())) + blob(rsa.encrypt(token, padding.PKCS1v15())))
                    w.enable_encryption(key)
                    result['encrypted'] = True
                elif packet == 2:
                    if not result['encrypted'] or body[:16] != IDENTITY.bytes:
                        raise ValueError('Authenticated synthetic identity mismatch')
                    phase = 'configuration'
                    w.send(3)
                    w.send(2, text('minecraft:register') + b'warland:auth_challenge\x00fabric:registry/sync')
                elif packet == 4:
                    query, _ = read_vi(body)
                    w.send(2, varint(query) + b'\x00')
                else:
                    raise ValueError('Unexpected login packet: ' + str(packet))
            elif phase == 'configuration':
                if packet == 1:
                    channel, off = read_blob(body, 0)
                    payload = body[off:]
                    if channel == b'warland:auth_challenge':
                        if len(payload) != 17:
                            raise ValueError('Malformed challenge')
                        nonce, status = payload[:16], payload[16]
                        result['auth_statuses'].append(status)
                        if status == 0:
                            register = mode == 'register'
                            request = b'\x01' + nonce + bytes([int(register)]) + secret(password) + secret(password if register else '') + secret('')
                            w.send(2, text('warland:auth_request') + request)
                        elif status == 1 and mode == 'wrong-password':
                            # Keep reading long enough to detect an erroneous release/PLAY.
                            quiet = time.monotonic() + 1
                            while time.monotonic() < quiet:
                                try:
                                    other, _ = w.receive(quiet)
                                except TimeoutError:
                                    break
                                if other in (2, 3):
                                    raise ValueError('Denial unexpectedly disconnected/released')
                            return result
                        elif status != 2 or mode == 'wrong-password':
                            raise ValueError('Unexpected authentication result')
                    elif channel == b'fabric:registry/sync':
                        if result['auth_statuses'] != [0, 2]:
                            raise ValueError('Registry sync before authentication')
                        # Headless protocol probe does not render/remap registries like a real client.
                        w.send(2, text('fabric:registry/sync/complete'))
                elif packet == 14:
                    w.send(7, b'\x00')  # No known packs; request complete registry data.
                elif packet == 4:
                    w.send(4, body)
                elif packet == 5:
                    w.send(5, body)
                elif packet == 3:
                    if result['auth_statuses'] != [0, 2]:
                        raise ValueError('Premature configuration completion')
                    result['configuration_finishes'] += 1
                    w.send(3)
                    phase = 'play'
                elif packet == 2:
                    raise ValueError('Configuration disconnected')
            else:
                if packet == 0x30:
                    result['play'] = True
                    w.send(0x2b)  # PlayerLoaded control packet.
                elif packet == 0x46:
                    teleport, _ = read_vi(body)
                    w.send(0, varint(teleport))
                    result['teleport_ack'] += 1
                elif packet == 0x0b:
                    w.send(0x0a, struct.pack('>f', 4.0))
                    result['chunk_ack'] += 1
                elif packet == 0x2b:
                    w.send(0x1b, body)
                    result['keepalive_ack'] += 1
                elif packet == 0x3b:
                    w.send(0x2c, body)
                elif packet in (0x20, 0x74):
                    raise ValueError('Unexpected PLAY disconnect/reconfiguration')
                elif packet == 0x77:
                    if 'Добро пожаловать в WarLand!'.encode() in body:
                        result['profile_ready_message'] = True
                    if command_sent and 'Баланс'.encode() in body:
                        result['balance_reply'] = True
                if result['profile_ready_message'] and not command_sent:
                    w.send(0x06, text('balance'))
                    command_sent = True
                if (result['play'] and result['profile_ready_message'] and result['teleport_ack']
                        and result['chunk_ack'] and result['keepalive_ack'] and result['balance_reply']):
                    return result
    finally:
        w.close()


def database(runtime):
    with sqlite3.connect(f'file:{runtime}/warland/warland.db?mode=ro', uri=True, timeout=3) as c:
        tables = {r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        counts = {k: c.execute('SELECT COUNT(*) FROM ' + k).fetchone()[0] if k in tables else 0
                  for k in ('profiles', 'auth_accounts', 'accounts', 'ledger')}
        counts['starter_grants'] = c.execute("SELECT COUNT(*) FROM ledger WHERE operation=?", ('starter:' + str(IDENTITY),)).fetchone()[0]
        counts['starter_total'] = c.execute("SELECT COALESCE(SUM(delta),0) FROM ledger WHERE operation=?", ('starter:' + str(IDENTITY),)).fetchone()[0]
        counts['balances'] = c.execute('SELECT balance FROM accounts ORDER BY owner').fetchall()
        counts['quick_check'] = c.execute('PRAGMA quick_check').fetchone()[0]
        counts['fk_errors'] = len(c.execute('PRAGMA foreign_key_check').fetchall())
        return counts


def stop(process):
    if process is None:
        return None
    if process.poll() is None:
        try:
            process.stdin.write('stop\n')
            process.stdin.flush()
            process.wait(timeout=55)
        except (BrokenPipeError, subprocess.TimeoutExpired):
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
    process.stdin.close()
    return process.returncode


def run(args):
    os.umask(0o077)
    source, jar, out = (p.resolve() for p in (args.source, args.jar, args.out))
    root = Path('/opt/warland-build')
    if os.geteuid() == 0 or not args.synthetic_fixture:
        raise ValueError('Run as unprivileged build user with explicit synthetic-fixture acknowledgment')
    if source != root / 'release-qa/final-runtime' or out.parent != root or not out.name.startswith('auth-encrypted-qa-'):
        raise ValueError('Only the known synthetic fixture and fresh QA output are supported')
    if out.exists() or args.out.is_symlink() or (source / 'warland/private-auth').exists():
        raise ValueError('Refusing previous evidence or source with private auth material')
    if hashlib.sha256(jar.read_bytes()).hexdigest() != args.sha256:
        raise ValueError('Candidate artifact hash mismatch')
    initial = database(source)
    if any(initial[k] for k in ('profiles', 'auth_accounts', 'accounts', 'ledger')):
        raise ValueError('Source fixture contains player/economy records')
    with socket.socket() as check:
        check.bind(('127.0.0.1', args.port))
    shutil.copytree(source, out, ignore=shutil.ignore_patterns('logs', '*.log', '*-result.json', 'usercache.json'))
    report = {'scope': 'synthetic identity only; real online-mode and AES-CFB8 transport; NOT Mojang/GUI/production acceptance',
              'artifact_sha256': args.sha256, 'cycles': [], 'success': False}
    report_path = out / 'encrypted-auth-result.json'
    def save():
        report_path.write_text(json.dumps(report, indent=2) + '\n')
    provider, process = None, None
    password = 'Synthetic-' + secrets.token_urlsafe(24)
    wrong = 'NotThePassword-' + secrets.token_urlsafe(24)
    try:
        for old in (out / 'mods').glob('warland-*.jar'):
            old.unlink()
        shutil.copy2(jar, out / 'mods' / jar.name)
        props = out / 'server.properties'
        values = {'server-ip': '127.0.0.1', 'server-port': str(args.port), 'online-mode': 'true',
                  'network-compression-threshold': '-1', 'enable-rcon': 'false', 'enable-query': 'false',
                  'max-players': '2', 'view-distance': '3', 'simulation-distance': '3',
                  'white-list': 'false', 'enforce-whitelist': 'false', 'enforce-secure-profile': 'false'}
        lines = [s for s in props.read_text().splitlines() if s.partition('=')[0] not in values]
        props.write_text('\n'.join(lines + [k + '=' + v for k, v in values.items()]) + '\n')
        versions = []
        for path in (out / 'versions').rglob('*.jar'):
            with zipfile.ZipFile(path) as z:
                if 'version.json' in z.namelist():
                    versions.append(json.loads(z.read('version.json')))
        if not any(v.get('id') == '1.21.11' and v.get('protocol_version') == 774 for v in versions):
            raise ValueError('Pinned Minecraft protocol metadata mismatch')
        provider = IdentityProvider()
        save()
        for cycle in (1, 2):
            item = {'cycle': cycle, 'probes': []}
            report['cycles'].append(item)
            log = out / f'encrypted-boot-{cycle}.log'
            command = ['nice', '-n', '10', 'java', '-Xms128M', '-Xmx640M', '-XX:ActiveProcessorCount=1']
            command += ['-Dminecraft.api.' + kind + '.host=' + provider.url for kind in ('session', 'services', 'profiles')]
            command += ['-jar', 'fabric-server-launch.jar', 'nogui']
            with log.open('x') as output:
                process = subprocess.Popen(command, cwd=out, stdin=subprocess.PIPE, stdout=output,
                                           stderr=subprocess.STDOUT, text=True, start_new_session=True)
                deadline = time.monotonic() + 110
                while process.poll() is None and time.monotonic() < deadline:
                    content = log.read_text(errors='replace')
                    if 'WarLand ready:' in content and 'Done (' in content:
                        break
                    time.sleep(.25)
                else:
                    raise RuntimeError('Dedicated QA server did not become ready')
                before = database(out)
                if cycle == 2:
                    item['probes'].append(probe(args.port, provider, wrong, 'wrong-password'))
                    if database(out) != before:
                        raise ValueError('Wrong password changed persistent records')
                    time.sleep(2)
                item['probes'].append(probe(args.port, provider, password, 'register' if cycle == 1 else 'login'))
                item['exit'] = stop(process)
                process = None
            item['database'] = database(out)
            d = item['database']
            if (d['profiles'] != 1 or d['auth_accounts'] != 1 or d['starter_grants'] != 1
                    or d['starter_total'] <= 0 or d['quick_check'] != 'ok' or d['fk_errors']):
                raise ValueError('Durability/starter-grant invariant failed')
            if cycle == 2 and item['database'] != report['cycles'][0]['database']:
                raise ValueError('Reconnect duplicated persistent account/economy state')
            content = log.read_text(errors='replace')
            item['errors'] = [s for s in content.splitlines() if re.search(r'\bERROR\b|Exception|InjectionError', s)][:20]
            item['warnings'] = [s for s in content.splitlines() if '/WARN]' in s][:20]
            if item['exit'] != 0 or item['errors']:
                raise ValueError('Nonzero server exit or errors in log')
            if any(value in content for value in (password, wrong)):
                raise ValueError('Synthetic password canary appeared in server log')
            if json.loads((out / 'ops.json').read_text()) != []:
                raise ValueError('Unexpected OP grant')
            save()
        report['synthetic_provider_acceptances'] = provider.accepted
        if provider.accepted != 3:
            raise ValueError('Unexpected provider acceptance count')
        report['success'] = True
    except Exception as exc:
        report['error'] = type(exc).__name__ + ': ' + str(exc).replace(password, '[REDACTED]').replace(wrong, '[REDACTED]')
    finally:
        if process is not None:
            report['cleanup_exit'] = stop(process)
        if provider is not None:
            provider.close()
        # Passwords are disposable in-memory canaries, never saved as plaintext.
        save()
        (out / 'result.exit').write_text('0\n' if report['success'] else '1\n')
        print(json.dumps(report, indent=2))
    return 0 if report['success'] else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--port', type=int, default=25569)
    parser.add_argument('--synthetic-fixture', action='store_true')
    raise SystemExit(run(parser.parse_args()))
