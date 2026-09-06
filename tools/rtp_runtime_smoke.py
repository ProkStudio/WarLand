#!/usr/bin/env python3
"""Synthetic loopback RTP acceptance layered on the native vanilla authentication suite.
Uses a fresh private fixture; never a production account or graphical-client acceptance.
"""
import argparse
import sqlite3
import struct
import time
from pathlib import Path
import vanilla_dialog_smoke as suite

original_probe = suite.probe
original_wire = suite.base.Wire
runtime = None

class RetainedWire(original_wire):
    current = None
    def __init__(self, port):
        super().__init__(port)
        self.position = None
        self.teleports = []
        RetainedWire.current = self
    def receive(self, deadline):
        packet, body = super().receive(deadline)
        if packet == 0x46:
            _, offset = suite.base.read_vi(body)
            if len(body) - offset != 60:
                raise ValueError('Unexpected 1.21.11 teleport layout')
            xyz = struct.unpack_from('>ddd', body, offset)
            flags = struct.unpack_from('>i', body, offset + 56)[0]
            if flags & 7:
                if self.position is None:
                    raise ValueError('Relative initial teleport')
                xyz = tuple(v + self.position[i] if flags & (1 << i) else v for i, v in enumerate(xyz))
            self.position = xyz
            self.teleports.append(xyz)
        return packet, body
    def close(self):
        pass  # The wrapper owns this connection through the RTP checks.
    def dispose(self):
        original_wire.close(self)

def state():
    with sqlite3.connect(f'file:{runtime}/warland/warland.db?mode=ro', uri=True, timeout=3) as db:
        rows = db.execute("SELECT json FROM state WHERE namespace='rtp.cooldown.v1' AND key=?", (str(suite.IDENTITY),)).fetchall()
        return [int(row[0]) for row in rows]

class ColdLoadRejected(Exception):
    """Expected bounded cold-generation refusal, not a successful teleport."""

def pump(wire, duration, expected=None):
    deadline = time.monotonic() + duration
    last_ground = 0
    messages = []
    while time.monotonic() < deadline:
        if time.monotonic() - last_ground >= .25 and wire.position is not None:
            wire.send(0x20, b'\x01')  # mapped MOVE_PLAYER_STATUS_ONLY: onGround=true
            last_ground = time.monotonic()
        try:
            packet, body = wire.receive(min(deadline, time.monotonic() + .2))
        except TimeoutError:
            continue
        if packet == 0x46:
            teleport, _ = suite.base.read_vi(body)
            wire.send(0, suite.base.varint(teleport))
        elif packet == 0x0b:
            wire.send(0x0a, struct.pack('>f', 4.0))
        elif packet == 0x2b:
            wire.send(0x1b, body)
        elif packet == 0x3b:
            wire.send(0x2c, body)
        elif packet in (0x20, 0x74):
            raise ValueError('Unexpected PLAY disconnect/reconfiguration during RTP')
        elif packet == 0x77:
            # Raw system-message bytes only used for matching; no credential content is recorded.
            messages.append(body)
            if expected == 'Вы прибыли в безопасную точку Верхнего мира' and 'Загрузка местности заняла слишком долго; RTP отменён.'.encode() in body:
                raise ColdLoadRejected('Bounded cold-chunk timeout')
            if expected is not None and expected.encode() in body:
                return messages
    if expected is not None:
        # Include only known RTP message fragments, not raw player/server messages.
        known = ['RTP занят', 'RTP отменён', 'RTP недоступен', 'RTP доступен только', 'Не так быстро',
                 'Безопасная точка не найдена', 'Загрузка местности', 'Бесплатный RTP']
        seen = [s for s in known if any(s.encode() in msg for msg in messages)]
        raise TimeoutError('Expected RTP response absent; observed categories: ' + ', '.join(seen))
    return messages

def rtp_probe(port, pin, password, mode, pack_url, pack_hash):
    if mode not in ('malformed-register', 'register', 'login'):
        return original_probe(port, pin, password, mode, pack_url, pack_hash)
    suite.base.Wire = RetainedWire
    wire = None
    try:
        result = original_probe(port, pin, password, mode, pack_url, pack_hash)
        wire = RetainedWire.current
        pump(wire, 4)
        before = state()
        wire.send(0x06, suite.base.text('rtp'))
        if mode == 'login':
            if len(before) != 1 or before[0] <= int(time.time() * 1000):
                raise ValueError('Cooldown expired before restart check; acceptance not established')
            pump(wire, 8, 'RTP будет доступен через')
            if state() != before:
                raise ValueError('Denied repeat RTP rewrote cooldown')
            result['rtp_restart_cooldown_denied'] = True
        else:
            if before:
                raise ValueError('Fresh synthetic account unexpectedly has RTP state')
            rejected = 0
            origin = wire.position
            for attempt in range(3):
                try:
                    pump(wire, 50, 'Вы прибыли в безопасную точку Верхнего мира')
                    break
                except ColdLoadRejected:
                    rejected += 1
                    if state() or wire.position != origin:
                        raise ValueError('Rejected cold load changed cooldown or player position')
                    if attempt == 2:
                        raise ValueError('Three bounded cold-load refusals; positive RTP acceptance NOT established')
                    pump(wire, 31)  # Respect the existing global 30-second cold-load backoff.
                    wire.send(0x06, suite.base.text('rtp'))
            result['rtp_cold_load_refusals_without_state_change'] = rejected
            after = state()
            if len(after) != 1 or after[0] <= int(time.time() * 1000):
                raise ValueError('Successful RTP has no durable cooldown')
            if wire.position is None:
                raise ValueError('No RTP position packet received')
            # Record the observed landing. Radius-about-spawn is covered by pure policy tests;
            # this wire harness does not independently decode the world's spawn from level.dat.
            result['rtp_landing'] = [round(v, 3) for v in wire.position]
            result['rtp_durable_cooldown'] = True
            pump(wire, 2.2)  # Success has a documented two-second global admission backoff.
            wire.send(0x06, suite.base.text('rtp'))
            pump(wire, 8, 'RTP будет доступен через')
            if state() != after:
                raise ValueError('Cooldown rejection modified state')
            result['rtp_repeat_denied'] = True
        return result
    finally:
        wire = wire or RetainedWire.current
        if wire is not None:
            wire.dispose()
        RetainedWire.current = None
        suite.base.Wire = original_wire

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--polymer', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--port', type=int, default=25588)
    parser.add_argument('--http-port', type=int, default=18098)
    parser.add_argument('--synthetic-fixture', action='store_true')
    args = parser.parse_args()
    runtime = args.out
    suite.probe = rtp_probe
    raise SystemExit(suite.run(args))
