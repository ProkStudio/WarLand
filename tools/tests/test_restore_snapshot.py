import hashlib
from contextlib import closing
import io
import os
from pathlib import Path
import sqlite3
import tarfile
import tempfile
import unittest
from restore_snapshot import restore, RestoreError

class RestoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.db = self.root / 'fixture.db'
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute('CREATE TABLE example(id INTEGER PRIMARY KEY, amount INTEGER)')
            db.execute('INSERT INTO example VALUES(1,1500)')
        self.base = [('server.properties', b'server-ip=127.0.0.1\n'),
                     ('fabric-server-launch.jar', b'fixture only; never executed'),
                     ('warland/warland.db', self.db.read_bytes())]
    def tearDown(self):
        self.temp.cleanup()
    def archive(self, entries=None, extra=None):
        path = self.root / 'backup.tar.gz'
        with tarfile.open(path, 'w:gz') as tar:
            for name, data in self.base if entries is None else entries:
                item = tarfile.TarInfo(name); item.size = len(data)
                tar.addfile(item, io.BytesIO(data))
            if extra is not None:
                tar.addfile(extra)
        Path(str(path)+'.sha256').write_text(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+str(path)+'\n')
        return path
    def rejected(self, archive, **kwargs):
        target = self.root / 'restored'
        with self.assertRaises((RestoreError, EOFError, OSError, tarfile.TarError)):
            restore(archive, target, **kwargs)
        self.assertFalse(target.exists())
    def test_restores_rows_and_keeps_files_private(self):
        target = self.root/'restored'
        result = restore(self.archive(), target)
        self.assertEqual('verified_isolated_restore', result['status'])
        self.assertEqual(1, result['sqlite_databases_checked'])
        self.assertFalse(result['services_started'])
        self.assertEqual(0o700, target.stat().st_mode & 0o777)
        self.assertEqual(0o600, (target/'warland/warland.db').stat().st_mode & 0o777)
        with closing(sqlite3.connect(target/'warland/warland.db')) as db:
            self.assertEqual(1500, db.execute('SELECT amount FROM example').fetchone()[0])
    def test_existing_destination_is_never_changed(self):
        target=self.root/'live';target.mkdir();marker=target/'marker';marker.write_text('unchanged')
        with self.assertRaises(RestoreError):restore(self.archive(),target)
        self.assertEqual('unchanged',marker.read_text())
    def test_destination_symlink_is_rejected(self):
        target=self.root/'link';target.symlink_to(self.root/'absent')
        with self.assertRaises(RestoreError):restore(self.archive(),target)
        self.assertTrue(target.is_symlink())
    def test_wrong_checksum_rejected_before_directory_created(self):
        self.rejected(self.archive(),expected='0'*64)
    def test_missing_checksum_rejected(self):
        archive=self.archive();Path(str(archive)+'.sha256').unlink();self.rejected(archive)
    def test_invalid_checksum_rejected(self):
        self.rejected(self.archive(),expected='not-a-hash')
    def test_parent_traversal_rejected(self):
        self.rejected(self.archive(self.base+[('../escaped',b'x')]))
        self.assertFalse((self.root/'escaped').exists())
    def test_absolute_path_rejected(self):
        self.rejected(self.archive(self.base+[('/tmp/warland-escape',b'x')]))
    def test_backslash_path_rejected(self):
        self.rejected(self.archive(self.base+[('..\\escaped',b'x')]))
    def test_duplicate_entries_rejected(self):
        self.rejected(self.archive(self.base+[self.base[0]]))
    def test_links_and_devices_are_rejected(self):
        for kind in [tarfile.SYMTYPE,tarfile.LNKTYPE,tarfile.FIFOTYPE,tarfile.CHRTYPE]:
            with self.subTest(kind=kind):
                item=tarfile.TarInfo('link');item.type=kind;item.linkname='../escape'
                self.rejected(self.archive(extra=item))
    def test_shadowing_file_directory_rejected(self):
        self.rejected(self.archive(self.base+[('mods',b'file'),('mods/x.jar',b'x')]))
    def test_entry_limit_enforced(self):
        self.rejected(self.archive(),max_files=2)
    def test_expanded_size_limit_enforced(self):
        self.rejected(self.archive(),max_bytes=10)
    def test_non_runtime_archive_rejected(self):
        self.rejected(self.archive([('file.txt',b'incomplete')]))
    def test_corrupt_database_rejected(self):
        self.rejected(self.archive(self.base[:2]+[('warland/warland.db',b'not sqlite')]))
    def test_forged_verification_report_not_overwritten(self):
        self.rejected(self.archive(self.base+[('restore-verification.json',b'{}')]))
    def test_truncated_archive_rejected_even_with_matching_checksum(self):
        archive=self.archive();archive.write_bytes(archive.read_bytes()[:50])
        self.rejected(archive,expected=hashlib.sha256(archive.read_bytes()).hexdigest())

if __name__ == '__main__':unittest.main()
