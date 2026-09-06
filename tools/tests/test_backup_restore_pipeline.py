"""Real archive/SQLite round trips on synthetic data, never Minecraft acceptance."""
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import unittest

import restore_snapshot
import test_backup as backup_fixture


@unittest.skipUnless(backup_fixture.BASH and os.name == 'posix', 'requires Bash and POSIX tools')
class BackupRestorePipelineTest(unittest.TestCase):
    def setUp(self):
        # Composition avoids rediscovering/inheriting the 26 lifecycle test cases.
        self.fixture = backup_fixture.BackupTest()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.setUp()
        self.runtime = self.fixture.runtime
        self.db_path = self.runtime / 'warland' / 'warland.db'
        self.db_path.parent.mkdir()
        with closing(sqlite3.connect(self.db_path)) as db, db:
            db.execute('CREATE TABLE example(id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)')
            db.executemany('INSERT INTO example VALUES(?, ?)', [(1, 1500), (2, 2300)])
        self.destination = self.fixture.root / 'restored'

    def source_snapshot(self):
        result = {}
        for path in self.runtime.rglob('*'):
            name = str(path.relative_to(self.runtime))
            if path.is_symlink():
                result[name] = ('link', os.readlink(path))
            elif path.is_file():
                result[name] = ('file', path.read_bytes())
            else:
                result[name] = ('directory', None)
        return result

    def archive(self, **config):
        result = self.fixture.run_backup(**config)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('Verified archive:', result.stdout)
        archives = list(self.fixture.backups.glob('*.tar.gz'))
        self.assertEqual(1, len(archives))
        archive = archives[0]
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(),
                         Path(str(archive) + '.sha256').read_text().split()[0])
        return archive

    def assert_round_trip(self, initial_state, expected_starts):
        before = self.source_snapshot()
        archive = self.archive(initial_state=initial_state)
        self.fixture.assert_recovery(expected_starts)
        calls_before_restore = self.fixture.calls()
        report = restore_snapshot.restore(archive, self.destination)
        self.assertEqual('verified_isolated_restore', report['status'])
        self.assertEqual(1, report['sqlite_databases_checked'])
        self.assertFalse(report['services_started'])
        self.assertFalse(report['production_modified'])
        self.assertEqual(calls_before_restore, self.fixture.calls())
        self.assertEqual(before, self.source_snapshot())
        restored_db = self.destination / 'warland' / 'warland.db'
        self.assertEqual(self.db_path.read_bytes(), restored_db.read_bytes())
        with closing(sqlite3.connect(restored_db.as_uri() + '?mode=ro', uri=True)) as db:
            self.assertEqual([(1, 1500), (2, 2300)],
                             db.execute('SELECT id, amount FROM example ORDER BY id').fetchall())
            self.assertEqual([('ok',)], db.execute('PRAGMA quick_check').fetchall())
        for relative in ('world/synthetic.txt', 'server.properties', 'fabric-server-launch.jar'):
            self.assertEqual((self.runtime / relative).read_bytes(),
                             (self.destination / relative).read_bytes())
        self.assertFalse((self.destination / 'logs').exists())
        report_path = self.destination / 'restore-verification.json'
        self.assertEqual(report, json.loads(report_path.read_text()))
        for path in [self.destination, *self.destination.rglob('*')]:
            self.assertFalse(path.is_symlink())
            self.assertEqual(0o700 if path.is_dir() else 0o600, path.stat().st_mode & 0o777)
        self.assertEqual(before, self.source_snapshot())

    def test_active_backup_restores_sqlite_world_bytes_and_private_modes(self):
        self.assert_round_trip('active', 1)

    def test_inactive_backup_round_trip_never_starts_a_service(self):
        self.assert_round_trip('inactive', 0)

    def test_tampered_archive_is_rejected_before_restore(self):
        before = self.source_snapshot()
        archive = self.archive()
        with archive.open('ab') as out:
            out.write(b'synthetic tamper')
        with self.assertRaisesRegex(restore_snapshot.RestoreError, 'checksum mismatch'):
            restore_snapshot.restore(archive, self.destination)
        self.assertFalse(self.destination.exists())
        self.assertEqual(before, self.source_snapshot())
        self.assertTrue(archive.exists())

    def test_tar_success_does_not_make_corrupt_sqlite_restorable(self):
        self.db_path.write_bytes(b'synthetic corrupt SQLite')
        before = self.source_snapshot()
        archive = self.archive()
        with self.assertRaisesRegex(restore_snapshot.RestoreError, 'SQLite'):
            restore_snapshot.restore(archive, self.destination)
        self.assertFalse(self.destination.exists())
        self.assertEqual(before, self.source_snapshot())
        self.assertTrue(archive.exists())
        self.fixture.assert_recovery(1)

    def test_existing_restore_target_and_source_are_preserved(self):
        before = self.source_snapshot()
        archive = self.archive()
        self.destination.mkdir()
        marker = self.destination / 'existing.txt'
        marker.write_text('preexisting destination: never replace')
        with self.assertRaisesRegex(restore_snapshot.RestoreError, 'must not exist'):
            restore_snapshot.restore(archive, self.destination)
        self.assertEqual('preexisting destination: never replace', marker.read_text())
        self.assertEqual([marker], list(self.destination.iterdir()))
        self.assertEqual(before, self.source_snapshot())

    def test_runtime_symlink_produces_archive_but_restore_fails_closed(self):
        target = self.fixture.root / 'synthetic-external.txt'
        target.write_text('do not follow or overwrite')
        (self.runtime / 'external-link').symlink_to(target)
        before = self.source_snapshot()
        archive = self.archive()
        with self.assertRaisesRegex(restore_snapshot.RestoreError, 'Links'):
            restore_snapshot.restore(archive, self.destination)
        self.assertFalse(self.destination.exists())
        self.assertEqual(before, self.source_snapshot())
        self.assertEqual('do not follow or overwrite', target.read_text())
        self.assertTrue(archive.exists())


if __name__ == '__main__':
    unittest.main()
