#!/usr/bin/env python3
"""Bounded diagnostic runner for multi-phase RTP QA, not a production protocol change.
Keep the existing 20,000-frame bound per phase; add a lifetime 60,000-frame /
64MiB body budget over the longer success/search/backoff sequence. No lifetime-counter resets,
no extra RTP attempts, no server timeout changes.
Only packet counts/types and phase outcomes are recorded, never packet bodies.
"""
import argparse
import json
import time
from collections import Counter
from pathlib import Path
import rtp_runtime_smoke as rtp

original_pump = rtp.pump


class ObservedWire(rtp.RetainedWire):
    MAX_TOTAL_PACKETS = 60000
    MAX_TOTAL_BYTES = 64 * 1024 * 1024

    def __init__(self, port):
        super().__init__(port)
        self.initialize_metrics()

    def initialize_metrics(self):
        self.total_packets = 0
        self.total_bytes = 0
        self.packet_types = Counter()
        self.phases = []

    def begin_phase(self):
        # Only the inherited short-phase counter resets. Lifetime counters never do.
        self.packets = 0

    def receive(self, deadline):
        packet, body = super().receive(deadline)
        self.total_packets += 1
        self.total_bytes += len(body)
        self.packet_types[min(packet, 256)] += 1
        if self.total_packets > self.MAX_TOTAL_PACKETS:
            raise ValueError('RTP lifetime packet budget exceeded')
        if self.total_bytes > self.MAX_TOTAL_BYTES:
            raise ValueError('RTP lifetime body-byte budget exceeded')
        return packet, body

    def summary(self):
        return {'packets': self.total_packets, 'body_bytes': self.total_bytes,
                'packet_types': {hex(k): v for k, v in self.packet_types.items()},
                'phase_limit': 20000, 'packet_limit': self.MAX_TOTAL_PACKETS,
                'body_byte_limit': self.MAX_TOTAL_BYTES, 'phases': self.phases}


def observed_pump(wire, duration, expected=None):
    wire.begin_phase()
    started = time.monotonic()
    before = wire.total_packets
    phase = {'duration_limit': duration, 'expects_response': expected is not None}
    try:
        result = original_pump(wire, duration, expected)
        phase['outcome'] = 'matched' if expected else 'wait_complete'
        return result
    except rtp.ColdLoadRejected:
        phase['outcome'] = 'cold_refusal_observed'
        raise
    except Exception as error:
        phase['outcome'] = type(error).__name__
        raise
    finally:
        phase['packets'] = wire.total_packets - before
        phase['seconds'] = round(time.monotonic() - started, 3)
        wire.phases.append(phase)
        (rtp.runtime/'rtp-wire-metrics.json').write_text(json.dumps(wire.summary(), indent=2)+'\n')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--polymer', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--port', type=int, default=25591)
    p.add_argument('--http-port', type=int, default=18101)
    p.add_argument('--synthetic-fixture', action='store_true')
    a = p.parse_args()
    # The underlying suite still enforces nonroot, loopback, fresh private fixture,
    # empty synthetic DB, deadlines, one account and graceful cleanup.
    rtp.runtime = a.out
    rtp.RetainedWire = ObservedWire
    rtp.pump = observed_pump
    rtp.suite.probe = rtp.rtp_probe
    raise SystemExit(rtp.suite.run(a))
