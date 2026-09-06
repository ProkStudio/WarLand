"""No sockets/servers: prove the diagnostic runner stays bounded and redacted."""
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
import auth_encrypted_smoke as base
import rtp_observed_smoke as observed


class FakeSocket:
    def __init__(self, data): self.data = data
    def recv(self, n):
        data, self.data = self.data[:n], self.data[n:]
        return data


class ObservedRtpTest(unittest.TestCase):
    def wire(self, payload=b'\x01hello'):
        wire = observed.ObservedWire.__new__(observed.ObservedWire)
        wire.socket = FakeSocket(base.blob(payload))
        wire.pending = bytearray()
        wire.encryptor = wire.decryptor = None
        wire.packets = 0
        wire.position = None
        wire.teleports = []
        wire.initialize_metrics()
        return wire

    def test_original_per_phase_guard_is_still_20000(self):
        wire = self.wire(); wire.packets = 20000
        with self.assertRaisesRegex(ValueError, 'Packet budget exceeded'):
            wire.receive(time.monotonic()+1)

    def test_phase_reset_never_resets_lifetime_counters(self):
        wire = self.wire(); wire.packets = 20000
        wire.total_packets = 21000; wire.total_bytes = 3000
        wire.begin_phase()
        self.assertEqual(wire.receive(time.monotonic()+1), (1,b'hello'))
        self.assertEqual(wire.total_packets,21001)
        self.assertEqual(wire.total_bytes,3005)
        self.assertEqual(wire.packets,1)

    def test_absolute_lifetime_packet_limit_survives_phase_reset(self):
        wire = self.wire(); wire.total_packets = wire.MAX_TOTAL_PACKETS
        wire.begin_phase()
        with self.assertRaisesRegex(ValueError,'lifetime packet budget'):
            wire.receive(time.monotonic()+1)

    def test_absolute_lifetime_body_limit_survives_phase_reset(self):
        wire = self.wire(); wire.total_bytes = wire.MAX_TOTAL_BYTES
        wire.begin_phase()
        with self.assertRaisesRegex(ValueError,'body-byte budget'):
            wire.receive(time.monotonic()+1)

    def test_deadline_frame_buffer_and_teleport_guards_remain(self):
        with self.assertRaises(TimeoutError): self.wire().receive(time.monotonic()-1)
        wire=self.wire(); wire.socket=FakeSocket(base.varint(base.MAX_FRAME+1))
        with self.assertRaisesRegex(ValueError,'Frame exceeds bound'): wire.receive(time.monotonic()+1)
        with self.assertRaisesRegex(ValueError,'teleport layout'): self.wire(b'\x46\x00').receive(time.monotonic()+1)

    def test_metrics_never_contain_packet_bodies(self):
        wire=self.wire(b'\x77PASSWORD_CANARY_PRIVATE_TEXT')
        wire.receive(time.monotonic()+1)
        output=json.dumps(wire.summary())
        self.assertNotIn('PASSWORD',output);self.assertNotIn('PRIVATE_TEXT',output)
        self.assertEqual(wire.summary()['packet_types'],{'0x77':1})
        self.assertEqual(wire.summary()['packet_limit'],60000)
        self.assertEqual(wire.summary()['body_byte_limit'],67108864)

    def test_success_and_failure_phases_are_reported_not_silently_retried(self):
        wire=self.wire()
        with tempfile.TemporaryDirectory() as directory, patch.object(observed.rtp,'runtime',Path(directory)):
            with patch.object(observed,'original_pump',return_value=['ok']) as pump:
                self.assertEqual(observed.observed_pump(wire,31),['ok']);pump.assert_called_once()
            with patch.object(observed,'original_pump',side_effect=observed.rtp.ColdLoadRejected('safe test')) as pump:
                with self.assertRaises(observed.rtp.ColdLoadRejected): observed.observed_pump(wire,50,'target')
                pump.assert_called_once()
            metrics=json.loads((Path(directory)/'rtp-wire-metrics.json').read_text())
            self.assertEqual([x['outcome'] for x in metrics['phases']],['wait_complete','cold_refusal_observed'])
            self.assertNotIn('target',json.dumps(metrics))
            self.assertNotIn('safe test',json.dumps(metrics))

    def test_no_server_limits_or_extra_attempts_are_introduced(self):
        text=Path(observed.__file__).read_text()
        self.assertNotIn('server.properties',text)
        self.assertNotIn('range(',text)
        self.assertNotIn('sleep(',text)
        self.assertIn('rtp.suite.run(a)',text)
        self.assertIn('super().receive(deadline)',text)


if __name__ == '__main__': unittest.main()
