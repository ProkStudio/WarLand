"""Actual observed_pump wiring with mocked wire traffic, never a runtime PASS."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import rtp_observed_smoke as observed


class ObservedConnectionsTest(unittest.TestCase):
    def wire(self):
        wire = observed.ObservedWire.__new__(observed.ObservedWire)
        wire.packets = 0
        wire.initialize_metrics()
        return wire

    def test_restart_report_does_not_destroy_first_connections_phases(self):
        first, second = self.wire(), self.wire()
        with tempfile.TemporaryDirectory() as root, patch.object(observed.rtp, 'runtime', Path(root)):
            with patch.object(observed, 'original_pump', return_value=[]):
                observed.observed_pump(first, 4)
                observed.observed_pump(first, 8, 'PRIVATE_EXPECTATION_CANARY')
                observed.observed_pump(second, 4)
            reports = [json.loads(p.read_text()) for p in Path(root).glob('rtp-wire-connection-*.json')]
            self.assertEqual(sorted(len(r['phases']) for r in reports), [1, 2])
            self.assertNotIn('PRIVATE_EXPECTATION_CANARY', json.dumps(reports))
            self.assertEqual([p['outcome'] for p in json.loads(first.metrics_store.path.read_text())['phases']],
                             ['wait_complete', 'matched'])

    def test_refusal_remains_a_failure_and_its_report_survives_next_connection(self):
        first, second = self.wire(), self.wire()
        with tempfile.TemporaryDirectory() as root, patch.object(observed.rtp, 'runtime', Path(root)):
            with patch.object(observed, 'original_pump', side_effect=observed.rtp.ColdLoadRejected('PRIVATE_ERROR_CANARY')):
                with self.assertRaises(observed.rtp.ColdLoadRejected): observed.observed_pump(first, 50, 'success')
            with patch.object(observed, 'original_pump', return_value=[]): observed.observed_pump(second, 4)
            report = json.loads(first.metrics_store.path.read_text())
            self.assertEqual(report['phases'][0]['outcome'], 'cold_refusal_observed')
            self.assertNotIn('PRIVATE_ERROR_CANARY', json.dumps(report))
            self.assertEqual(report['packet_limit'], 60000)
            self.assertEqual(report['phase_limit'], 20000)


if __name__ == '__main__': unittest.main()
