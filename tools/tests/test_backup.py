"""Hermetic backup lifecycle tests: no root, systemd, VPS or real player data.

Only the fixed host lock pathname is redirected in a disposable script copy.
All other production Bash is executed verbatim with a restricted helper PATH.
"""
from __future__ import annotations

import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'backup.sh'
BASH = shutil.which('bash')
STUB = r'''
import json, os, signal, sys
from pathlib import Path
root = Path(os.environ['BACKUP_TEST_ROOT'])
config = json.loads((root / 'config.json').read_text())
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with (root / 'calls.jsonl').open('a') as log:
    log.write(json.dumps([name, *args]) + '\n')
if name == 'id':
    print(config.get('uid', 0))
    sys.exit(0)
if name == 'date':
    print('20260906T110000Z')
    sys.exit(0)
if name == 'systemctl':
    if args[0] == 'show' and '--property=WorkingDirectory' in args:
        if config.get('directory_query_fails'):
            sys.exit(64)
        print(config.get('directory', str(root / 'runtime')))
        sys.exit(0)
    phase_file = root / 'phase'
    phase = phase_file.read_text() if phase_file.exists() else 'initial'
    if args[0] == 'stop':
        phase_file.write_text('stopped')
        sys.exit(74 if config.get('stop_fails') else 0)
    if args[0] == 'start':
        phase_file.write_text('restored')
        sys.exit(75 if config.get('start_fails') else 0)
    if config.get(phase + '_query_fails'):
        sys.exit(64)
    state = config.get(phase + '_state', {'initial': 'active', 'stopped': 'inactive', 'restored': 'active'}[phase])
    if args[0] == 'show' and '--property=ActiveState' in args:
        print(state)
        sys.exit(0)
    if args[0] == 'is-active':
        sys.exit(0 if state == 'active' else 3)
    sys.exit(98)  # Unexpected systemctl invocation: never delegate to host.
if name == 'tar':
    if '-czf' in args and config.get('interrupt'):
        os.kill(os.getppid(), getattr(signal, config['interrupt']))
        sys.exit(0)
    if '-czf' in args and config.get('tar_create_fails'):
        sys.exit(71)
    if '-tzf' in args and config.get('tar_verify_fails'):
        sys.exit(72)
if name == 'sha256sum' and config.get('checksum_fails'):
    sys.exit(73)
os.execv(config['real_helpers'][name], [name, *args])
'''


@unittest.skipUnless(BASH and os.name == 'posix', 'requires Bash and POSIX tools')
class BackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='warland-backup-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runtime = self.root / 'runtime'
        self.runtime.mkdir()
        (self.runtime / 'fabric-server-launch.jar').write_bytes(b'synthetic launcher')
        (self.runtime / 'server.properties').write_text('server-ip=127.0.0.1\n')
        (self.runtime / 'world').mkdir()
        (self.runtime / 'world' / 'synthetic.txt').write_text('preserve this fixture\n')
        (self.runtime / 'logs').mkdir()
        (self.runtime / 'logs' / 'latest.log').write_text('excluded synthetic log\n')
        self.backups = self.root / 'backups'
        self.lock = self.root / 'backup.lock'
        source = SCRIPT.read_text()
        self.assertEqual(source.count('/var/lock/warland-backup.lock'), 1)
        self.script = self.root / 'backup.sh'
        self.script.write_text(source.replace('/var/lock/warland-backup.lock', str(self.lock)))
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.config = {'real_helpers': {}}
        for helper in ('realpath', 'mkdir', 'chmod', 'flock', 'mv', 'tar', 'gzip', 'sha256sum'):
            real = shutil.which(helper)
            if real is None:
                self.skipTest('missing helper: ' + helper)
            self.config['real_helpers'][helper] = real
            if helper not in ('tar', 'sha256sum'):
                (self.bin / helper).symlink_to(real)
        for helper in ('id', 'date', 'systemctl', 'tar', 'sha256sum'):
            path = self.bin / helper
            path.write_text('#!' + sys.executable + '\n' + STUB)
            path.chmod(0o700)
        # No inherited BASH_ENV, shell functions, shellopts or host helper PATH.
        self.env = {'PATH': str(self.bin), 'HOME': str(self.root), 'LC_ALL': 'C',
                    'BACKUP_TEST_ROOT': str(self.root)}

    def run_backup(self, service='warland.service', **config):
        self.config.update(config)
        (self.root / 'config.json').write_text(json.dumps(self.config))
        return subprocess.run([BASH, str(self.script), str(self.runtime), str(self.backups), service],
                              env=self.env, text=True, capture_output=True, timeout=15)

    def calls(self, helper=None):
        log = self.root / 'calls.jsonl'
        calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
        return [call for call in calls if helper is None or call[0] == helper]

    def assert_recovery(self, count):
        self.assertEqual(sum(call[1] == 'start' for call in self.calls('systemctl')), count)

    def assert_no_archive_work(self):
        self.assertEqual(self.calls('tar'), [])
        self.assertEqual(self.calls('sha256sum'), [])

    def assert_failed(self, result, code=None):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        if code is not None:
            self.assertEqual(result.returncode, code, result.stdout + result.stderr)
        self.assertNotIn('Verified archive:', result.stdout)

    def test_active_success_archive_checksum_permissions_and_recovery(self):
        result = self.run_backup()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Verified archive:', result.stdout)
        self.assert_recovery(1)
        archives = list(self.backups.glob('*.tar.gz'))
        self.assertEqual(len(archives), 1)
        archive = archives[0]
        expected = hashlib.sha256(archive.read_bytes()).hexdigest()
        self.assertEqual(Path(str(archive) + '.sha256').read_text().split()[0], expected)
        self.assertEqual(archive.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.backups.stat().st_mode & 0o777, 0o700)
        with tarfile.open(archive) as snapshot:
            self.assertEqual(snapshot.extractfile('./world/synthetic.txt').read(), b'preserve this fixture\n')
            self.assertNotIn('./logs/latest.log', snapshot.getnames())
        self.assertEqual((self.runtime / 'world/synthetic.txt').read_text(), 'preserve this fixture\n')
        self.assertEqual((self.runtime / 'logs/latest.log').read_text(), 'excluded synthetic log\n')

    def test_inactive_service_is_never_started(self):
        result = self.run_backup(initial_state='inactive')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_recovery(0)

    def test_restart_command_failure_is_not_success(self):
        result = self.run_backup(start_fails=True)
        self.assert_failed(result, 5)
        self.assert_recovery(1)
        self.assertEqual(len(list(self.backups.glob('*.tar.gz'))), 1)  # Keep useful backup.

    def test_reported_restart_success_requires_active_state(self):
        result = self.run_backup(restored_state='failed')
        self.assert_failed(result, 5)
        self.assert_recovery(1)

    def test_restart_state_query_failure_is_not_success(self):
        result = self.run_backup(restored_query_fails=True)
        self.assert_failed(result, 5)
        self.assert_recovery(1)

    def test_transitional_restart_state_is_not_success(self):
        result = self.run_backup(restored_state='activating')
        self.assert_failed(result, 5)
        self.assert_recovery(1)

    def test_initial_query_failure_stops_nothing(self):
        result = self.run_backup(initial_query_fails=True)
        self.assert_failed(result, 4)
        self.assert_no_archive_work()
        self.assert_recovery(0)
        self.assertFalse(any(call[1] == 'stop' for call in self.calls('systemctl')))

    def test_unknown_initial_states_stop_nothing(self):
        for state in ('failed', 'activating', 'deactivating', 'reloading', 'unknown', ''):
            with self.subTest(state=state):
                result = self.run_backup(initial_state=state)
                self.assert_failed(result, 4)
        self.assert_no_archive_work()
        self.assert_recovery(0)
        self.assertFalse(any(call[1] == 'stop' for call in self.calls('systemctl')))

    def test_stop_command_failure_restores_and_preserves_error(self):
        result = self.run_backup(stop_fails=True)
        self.assert_failed(result, 74)
        self.assert_no_archive_work()
        self.assert_recovery(1)

    def test_post_stop_query_failure_never_archives(self):
        result = self.run_backup(stopped_query_fails=True)
        self.assert_failed(result, 4)
        self.assert_no_archive_work()
        self.assert_recovery(1)

    def test_noninactive_post_stop_state_never_archives(self):
        for state in ('active', 'failed', 'deactivating', ''):
            with self.subTest(state=state):
                # Reset lifecycle between attempts within this one fixture.
                (self.root / 'phase').unlink(missing_ok=True)
                result = self.run_backup(stopped_state=state)
                self.assert_failed(result, 4)
        self.assert_no_archive_work()
        self.assert_recovery(4)

    def test_archive_creation_failure_restores_original_service(self):
        result = self.run_backup(tar_create_fails=True)
        self.assert_failed(result, 71)
        self.assert_recovery(1)

    def test_archive_verification_failure_preserves_partial_and_restores(self):
        result = self.run_backup(tar_verify_fails=True)
        self.assert_failed(result, 72)
        self.assert_recovery(1)
        self.assertEqual(len(list(self.backups.glob('*.partial'))), 1)

    def test_checksum_failure_restores_and_preserves_error(self):
        result = self.run_backup(checksum_fails=True)
        self.assert_failed(result, 73)
        self.assert_recovery(1)

    def test_backup_and_restart_failure_preserves_primary_error(self):
        result = self.run_backup(tar_create_fails=True, start_fails=True)
        self.assert_failed(result, 71)
        self.assertIn('WARNING:', result.stderr)
        self.assert_recovery(1)

    def test_failure_does_not_start_originally_inactive_service(self):
        result = self.run_backup(initial_state='inactive', tar_create_fails=True)
        self.assert_failed(result, 71)
        self.assert_recovery(0)

    def test_service_mismatch_is_rejected_before_stop(self):
        result = self.run_backup(directory=str(self.root))
        self.assert_failed(result, 2)
        self.assert_no_archive_work()
        self.assert_recovery(0)
        self.assertFalse(any(call[1] == 'stop' for call in self.calls('systemctl')))

    def test_unexpected_service_is_rejected_before_systemctl(self):
        result = self.run_backup(service='minecraft.service')
        self.assert_failed(result, 2)
        self.assertEqual(self.calls('systemctl'), [])

    def test_nonroot_is_rejected_before_systemctl(self):
        result = self.run_backup(uid=1000)
        self.assert_failed(result, 2)
        self.assertEqual(self.calls('systemctl'), [])

    def test_busy_real_lock_prevents_stop(self):
        with self.lock.open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_backup()
        self.assert_failed(result, 3)
        self.assert_no_archive_work()
        self.assert_recovery(0)
        self.assertFalse(any(call[1] == 'stop' for call in self.calls('systemctl')))

    def test_existing_archive_is_preserved_and_service_restored(self):
        self.backups.mkdir()
        previous = self.backups / 'warland-20260906T110000Z.tar.gz'
        previous.write_bytes(b'existing backup: never clobber')
        result = self.run_backup()
        self.assert_failed(result, 4)
        self.assertEqual(previous.read_bytes(), b'existing backup: never clobber')
        self.assert_no_archive_work()
        self.assert_recovery(1)

    def test_term_interrupt_preserves_signal_status_and_restores(self):
        result = self.run_backup(interrupt='SIGTERM')
        self.assert_failed(result, 143)
        self.assert_recovery(1)

    def test_int_interrupt_preserves_signal_status_and_restores(self):
        result = self.run_backup(interrupt='SIGINT')
        self.assert_failed(result, 130)
        self.assert_recovery(1)

    def test_directory_query_failure_stops_nothing(self):
        result = self.run_backup(directory_query_fails=True)
        self.assert_failed(result, 64)
        self.assert_no_archive_work()
        self.assert_recovery(0)
        self.assertFalse(any(call[1] == 'stop' for call in self.calls('systemctl')))

    def test_inactive_post_stop_query_failure_never_starts(self):
        result = self.run_backup(initial_state='inactive', stopped_query_fails=True)
        self.assert_failed(result, 4)
        self.assert_no_archive_work()
        self.assert_recovery(0)

    def test_staging_service_is_supported(self):
        result = self.run_backup(service='warland-staging.service')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_recovery(1)


if __name__ == '__main__':
    unittest.main()
