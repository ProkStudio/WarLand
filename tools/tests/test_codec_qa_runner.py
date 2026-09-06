import hashlib
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

# Supports both local QA folder and repository tools/tests layout.
HERE = pathlib.Path(__file__).resolve().parent
SOURCE = HERE / 'run_codec_qa.py'
if not SOURCE.exists(): SOURCE = HERE.parent / 'qa' / 'run_codec_qa.py'
spec = importlib.util.spec_from_file_location('codec_qa_runner_under_test', SOURCE)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

class CodecQaRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.patch = mock.patch.object(runner, 'ROOT', self.root)
        self.patch.start()
    def tearDown(self):
        self.patch.stop(); self.temp.cleanup()
    def test_captures_only_own_command_output(self):
        runner.run([sys.executable, '-c', 'print("synthetic")'], self.root, 'success.log', 10)
        self.assertEqual((self.root/'success.log').read_text().strip(), 'synthetic')
        self.assertTrue((self.root/'success.log.pid').is_file())
    def test_preserves_failed_command_evidence(self):
        with self.assertRaisesRegex(RuntimeError, 'Command failed'):
            runner.run([sys.executable, '-c', 'print("preserved");raise SystemExit(7)'], self.root, 'failed.log', 10)
        self.assertIn('preserved', (self.root/'failed.log').read_text())
    def test_timeout_ends_own_process_group(self):
        with self.assertRaisesRegex(RuntimeError, 'timed out'):
            runner.run([sys.executable, '-c', 'import time;time.sleep(30)'], self.root, 'timeout.log', .1)
        pid = int((self.root/'timeout.log.pid').read_text())
        with self.assertRaises(ProcessLookupError): runner.os.kill(pid, 0)
        self.assertTrue((self.root/'timeout.log').exists())
    def test_never_overwrites_existing_evidence(self):
        (self.root/'exists.log').write_text('preserve')
        with self.assertRaises(FileExistsError):
            runner.run([sys.executable, '-c', 'raise SystemExit(99)'], self.root, 'exists.log', 10)
        self.assertEqual((self.root/'exists.log').read_text(), 'preserve')
        self.assertFalse((self.root/'exists.log.pid').exists())
    def test_root_execution_is_rejected_before_actions(self):
        with mock.patch.object(runner.os, 'getuid', return_value=0), mock.patch.object(runner, 'run') as execute:
            with self.assertRaisesRegex(RuntimeError, 'root'): runner.main()
            execute.assert_not_called()
    def test_existing_output_is_never_reused(self):
        (self.root/'sentinel').write_text('preserve')
        with mock.patch.object(runner.os, 'getuid', return_value=1001), mock.patch.object(runner, 'run') as execute:
            with self.assertRaisesRegex(RuntimeError, 'never overwrite'): runner.main()
            execute.assert_not_called()
        self.assertEqual((self.root/'sentinel').read_text(), 'preserve')
    def test_artifact_selection_rejects_ambiguity(self):
        libs = self.root/'build'/'libs'; libs.mkdir(parents=True)
        with self.assertRaisesRegex(RuntimeError, 'Ambiguous'): runner.artifact(self.root)
        (libs/'one.jar').write_bytes(b'fixture'); (libs/'one-sources.jar').write_bytes(b'fixture')
        self.assertEqual(runner.artifact(self.root).name, 'one.jar')
        (libs/'other.jar').write_bytes(b'fixture')
        with self.assertRaisesRegex(RuntimeError, 'Ambiguous'): runner.artifact(self.root)
    def test_digest_does_not_modify_file(self):
        path = self.root/'input'; path.write_bytes(b'synthetic input')
        self.assertEqual(runner.digest(path), hashlib.sha256(b'synthetic input').hexdigest())
        self.assertEqual(path.read_bytes(), b'synthetic input')

if __name__ == '__main__': unittest.main()
