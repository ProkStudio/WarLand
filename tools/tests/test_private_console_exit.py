import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

import private_console as console


class ExitTests(unittest.TestCase):
    def child_exit(self, child, terminate=False):
        with tempfile.TemporaryDirectory(prefix='wlc-exit-') as temporary:
            root = Path(temporary)
            root.chmod(0o700)
            path = root / 'console.sock'
            with (root/'bridge.log').open('w') as log:
                process = subprocess.Popen([sys.executable, str(Path(console.__file__).resolve()),
                    'serve', '--socket', str(path), '--cwd', str(root), '--grace', '1', '--',
                    sys.executable, '-u', '-c', child], stdout=log, stderr=log)
                try:
                    if terminate:
                        deadline = time.monotonic()+5
                        while not path.exists() and process.poll() is None and time.monotonic()<deadline:
                            time.sleep(.02)
                        self.assertTrue(path.exists(), (root/'bridge.log').read_text())
                        process.send_signal(signal.SIGTERM)
                    result = process.wait(timeout=9)
                    self.assertFalse(path.exists())
                    return result
                finally:
                    if process.poll() is None:
                        process.terminate()
                        process.wait(timeout=9)

    @unittest.skipIf(os.geteuid() == 0, 'Service deliberately refuses root; run suite as build user')
    def test_child_failure_is_not_hidden(self):
        self.assertEqual(self.child_exit('raise SystemExit(17)'), 17)

    @unittest.skipIf(os.geteuid() == 0, 'Service deliberately refuses root; run suite as build user')
    def test_forced_shutdown_is_not_reported_clean(self):
        self.assertEqual(self.child_exit('import time; time.sleep(60)', terminate=True), 124)

