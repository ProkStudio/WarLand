"""Real tempfile I/O only; does not claim Minecraft runtime acceptance."""
import json
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from rtp_metrics import ConnectionMetrics


class ConnectionMetricsTest(unittest.TestCase):
    def test_two_connections_preserve_both_reports(self):
        with tempfile.TemporaryDirectory() as root:
            a, b = ConnectionMetrics(), ConnectionMetrics()
            first = a.write(root, {'packets': 26000, 'phases': ['first']})
            second = b.write(root, {'packets': 42, 'phases': ['restart']})
            self.assertNotEqual(first, second)
            self.assertEqual(json.loads(first.read_text())['packets'], 26000)
            self.assertEqual(json.loads(second.read_text())['packets'], 42)
            self.assertEqual(json.loads((Path(root)/'rtp-wire-metrics.json').read_text())['packets'], 42)

    def test_same_connection_updates_same_file_without_extra_files(self):
        with tempfile.TemporaryDirectory() as root:
            store = ConnectionMetrics()
            first = store.write(root, {'packets': 2})
            self.assertEqual(store.write(root, {'packets': 10}), first)
            self.assertEqual(json.loads(first.read_text()), {'packets': 10})
            self.assertEqual(len(list(Path(root).glob('rtp-wire-connection-*.json'))), 1)
            self.assertEqual(list(Path(root).glob('.rtp-metrics-*')), [])

    def test_same_runtime_across_runner_instances_never_collides(self):
        with tempfile.TemporaryDirectory() as root:
            paths = {ConnectionMetrics().write(root, {'packets': n}) for n in range(16)}
            self.assertEqual(len(paths), 16)
            self.assertEqual({json.loads(p.read_text())['packets'] for p in paths}, set(range(16)))

    @unittest.skipIf(os.name == 'nt', 'POSIX modes')
    def test_private_file_modes_even_with_permissive_umask(self):
        old = os.umask(0)
        try:
            with tempfile.TemporaryDirectory() as root:
                path = ConnectionMetrics().write(root, {'packets': 1})
                for p in (path, Path(root)/'rtp-wire-metrics.json'):
                    self.assertEqual(stat.S_IMODE(p.stat().st_mode), 0o600)
        finally:
            os.umask(old)

    def test_failed_replace_preserves_previous_report_and_cleans_temporary(self):
        with tempfile.TemporaryDirectory() as root:
            store = ConnectionMetrics(); path = store.write(root, {'packets': 1})
            with patch('rtp_metrics.os.replace', side_effect=OSError('injected disk error')):
                with self.assertRaises(OSError): store.write(root, {'packets': 2})
            self.assertEqual(json.loads(path.read_text()), {'packets': 1})
            self.assertEqual(list(Path(root).glob('.rtp-metrics-*')), [])

    def test_failed_latest_write_keeps_new_per_connection_evidence(self):
        with tempfile.TemporaryDirectory() as root:
            store = ConnectionMetrics(); path = store.write(root, {'packets': 1})
            replace = os.replace
            def fail_latest(source, destination):
                if Path(destination).name == 'rtp-wire-metrics.json':
                    raise OSError('injected latest write failure')
                return replace(source, destination)
            with patch('rtp_metrics.os.replace', side_effect=fail_latest):
                with self.assertRaises(OSError): store.write(root, {'packets': 2})
            self.assertEqual(json.loads(path.read_text()), {'packets': 2})
            self.assertEqual(list(Path(root).glob('.rtp-metrics-*')), [])

    def test_directory_must_exist_and_stay_bound_to_connection(self):
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            store = ConnectionMetrics()
            with self.assertRaises(ValueError): store.write(Path(first)/'absent', {})
            path = store.write(first, {})
            with self.assertRaises(ValueError): store.write(second, {})
            self.assertEqual(path, store.path)
            self.assertEqual(list(Path(second).iterdir()), [])

    @unittest.skipIf(os.name == 'nt', 'POSIX symlinks')
    def test_symlink_directory_refused_and_latest_link_not_followed(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root); runtime = root/'runtime'; runtime.mkdir()
            alias = root/'alias'; alias.symlink_to(runtime, target_is_directory=True)
            with self.assertRaises(ValueError): ConnectionMetrics().write(alias, {})
            unrelated = root/'unrelated'; unrelated.write_text('keep')
            (runtime/'rtp-wire-metrics.json').symlink_to(unrelated)
            ConnectionMetrics().write(runtime, {'packets': 1})
            self.assertEqual(unrelated.read_text(), 'keep')
            self.assertFalse((runtime/'rtp-wire-metrics.json').is_symlink())

    def test_invalid_json_value_refused_without_overwriting_evidence(self):
        with tempfile.TemporaryDirectory() as root:
            store = ConnectionMetrics(); path = store.write(root, {'packets': 1})
            with self.assertRaises(ValueError): store.write(root, {'seconds': float('nan')})
            self.assertEqual(json.loads(path.read_text()), {'packets': 1})


if __name__ == '__main__': unittest.main()
