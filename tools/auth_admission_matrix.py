#!/usr/bin/env python3
"""Additional synthetic encrypted admission attacks; invokes the base isolated harness.
No production endpoints, credentials, server flags, or packet guards are changed.
"""
import argparse
import json
from pathlib import Path
import time
import uuid
import auth_encrypted_smoke as smoke


class ExpectedDenial(Exception):
    pass


BASE_WIRE = smoke.Wire
BASE_PROBE = smoke.probe
CASES = ('forged-ready', 'stale-nonce', 'mismatched-confirmation')


class AttackWire(BASE_WIRE):
    case = None
    result = None

    def __init__(self, port):
        super().__init__(port)
        self.configuration = False
        self.injected = False

    def send(self, packet, body=b''):
        if packet == 3 and not self.configuration:
            self.configuration = True
        prefix = smoke.text('warland:auth_request')
        if self.configuration and packet == 2 and body.startswith(prefix):
            if self.injected:
                raise AssertionError('Repeated synthetic auth request')
            self.injected = True
            self.result['attack_sent'] = True
            if self.case == 'forged-ready':
                return super().send(3)
            payload = bytearray(body[len(prefix):])
            if self.case == 'stale-nonce':
                payload[1:17] = uuid.uuid4().bytes
            elif self.case == 'mismatched-confirmation':
                # v1 + nonce + registration byte; first counted UTF-16 secret starts at 18.
                size = int.from_bytes(payload[18:20], 'big')
                confirmation = 20 + size * 2
                if int.from_bytes(payload[confirmation:confirmation + 2], 'big') == 0:
                    raise AssertionError('Expected registration confirmation')
                payload[confirmation + 3] ^= 1
            return super().send(packet, prefix + payload)
        return super().send(packet, body)

    def receive(self, deadline):
        packet, body = super().receive(deadline)
        if self.configuration:
            if packet == 3:
                raise AssertionError('Forged client reached configuration completion')
            if packet == 1:
                channel, offset = smoke.read_blob(body, 0)
                if channel == b'warland:auth_challenge' and body[offset:][-1:] == b'\x02':
                    raise AssertionError('Invalid proof was accepted')
            if packet == 2:
                if not self.injected or b'WarLand:' not in body or self.encryptor is None:
                    raise AssertionError('Expected an encrypted WarLand-specific denial')
                self.result['encrypted_warland_denial'] = True
                raise ExpectedDenial()
        return packet, body


def run(args):
    results = []
    report_path = args.out / 'admission-matrix.json'
    original_probe = smoke.probe

    def augmented_probe(port, provider, password, mode):
        if mode == 'register':
            for case in CASES:
                item = {'case': case, 'attack_sent': False, 'encrypted_warland_denial': False,
                        'no_persistent_effects': False}
                results.append(item)
                before = smoke.database(args.out)
                AttackWire.case, AttackWire.result = case, item
                smoke.Wire = AttackWire
                try:
                    BASE_PROBE(port, provider, password, 'register')
                    raise AssertionError('Expected a denial, not a successful probe')
                except ExpectedDenial:
                    pass
                finally:
                    smoke.Wire = BASE_WIRE
                time.sleep(.3)
                if smoke.database(args.out) != before:
                    raise AssertionError('Rejected request changed profile/auth/economy records')
                item['no_persistent_effects'] = True
                report_path.write_text(json.dumps({'cases': results, 'success': False}, indent=2) + '\n')
            # Base harness expects exactly three identity-provider acceptances for its three probes.
            # Keep separate matrix evidence; this counter reset excludes only verified rejected attempts.
            if provider.accepted != len(CASES):
                raise AssertionError('Unexpected matrix identity acceptance count')
            provider.accepted = 0
        return BASE_PROBE(port, provider, password, mode)

    smoke.probe = augmented_probe
    try:
        code = smoke.run(args)
        report_path.write_text(json.dumps({'cases': results, 'success': code == 0 and len(results) == len(CASES),
            'scope': 'synthetic encrypted config-only attacks plus base registration/restart/login; not full packet/TTL/crash matrix'}, indent=2) + '\n')
        return code
    finally:
        smoke.probe = original_probe
        smoke.Wire = BASE_WIRE


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--port', type=int, default=25569)
    parser.add_argument('--synthetic-fixture', action='store_true')
    raise SystemExit(run(parser.parse_args()))
