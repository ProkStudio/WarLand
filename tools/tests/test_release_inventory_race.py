"""Deterministic disk-snapshot regressions; no live server or real player data."""
import json
import os
from pathlib import Path
import shutil
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import release_gate


class InventorySnapshotTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.mods = self.root / 'mods'
        self.mods.mkdir()
        self.jar = self.mods / 'warland.jar'
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr('fabric.mod.json', json.dumps({'id': 'warland'}))
        self.expected = release_gate.sha256(self.jar)
        self.note = self.mods / 'notes.txt'
        self.note.write_text('original')
        original = release_gate.fingerprint
        # Model equal/coarse directory timestamps only. File metadata stays real.
        self.fingerprints = patch.object(release_gate, 'fingerprint', side_effect=lambda info:
            ('constant-directory',) if stat.S_ISDIR(info.st_mode) else original(info))
        self.fingerprints.start()
        self.addCleanup(self.fingerprints.stop)

    def inspect_with(self, mutate):
        original = release_gate.inspect_mod
        def inspect(fd, name):
            result = original(fd, name)
            mutate()
            return result
        with patch.object(release_gate, 'inspect_mod', side_effect=inspect):
            release_gate.installed_artifact(self.root, self.expected)

    def rejects(self, mutate):
        with self.assertRaises((ValueError, OSError)):
            self.inspect_with(mutate)

    def test_stable_inventory_passes_with_equal_directory_fingerprint(self):
        self.inspect_with(lambda: None)

    def test_added_nonjar_is_detected(self):
        self.rejects(lambda: (self.mods / 'new.txt').write_text('added'))

    def test_added_duplicate_jar_is_detected(self):
        self.rejects(lambda: shutil.copyfile(self.jar, self.mods / 'duplicate.jar'))

    def test_removed_nonjar_is_detected(self):
        self.rejects(self.note.unlink)

    def test_renamed_nonjar_is_detected(self):
        self.rejects(lambda: self.note.rename(self.mods / 'renamed.txt'))

    def test_nonjar_modified_is_detected(self):
        self.rejects(lambda: self.note.write_text('changed and longer'))

    def test_jar_modified_after_inspection_is_detected(self):
        def mutate():
            with self.jar.open('ab') as stream:
                stream.write(b'after-inspection')
        self.rejects(mutate)

    def test_jar_replaced_after_inspection_is_detected(self):
        replacement = self.root / 'replacement.jar'
        shutil.copyfile(self.jar, replacement)
        self.rejects(lambda: os.replace(replacement, self.jar))

    def test_same_size_nonjar_replacement_is_detected(self):
        replacement = self.root / 'replacement.txt'
        replacement.write_bytes(self.note.read_bytes())
        self.rejects(lambda: os.replace(replacement, self.note))

    def test_symlink_added_is_detected(self):
        self.rejects(lambda: (self.mods / 'link.txt').symlink_to(self.note))

    def test_nonjar_mode_change_is_detected(self):
        self.rejects(lambda: self.note.chmod(0o400))

    def test_nonjar_link_count_change_is_detected(self):
        self.rejects(lambda: os.link(self.note, self.root / 'linked-note'))

    def test_second_snapshot_enforces_entry_limit(self):
        with patch.object(release_gate, 'MAX_MOD_ENTRIES', 2):
            self.rejects(lambda: (self.mods / 'extra.txt').write_text('extra'))

    def test_second_snapshot_enforces_jar_limit(self):
        with patch.object(release_gate, 'MAX_MOD_JARS', 1):
            self.rejects(lambda: shutil.copyfile(self.jar, self.mods / 'extra.jar'))

    def test_second_snapshot_enforces_byte_limit(self):
        with patch.object(release_gate, 'MAX_MODS_BYTES', self.jar.stat().st_size):
            self.rejects(lambda: shutil.copyfile(self.jar, self.mods / 'extra.jar'))

    def test_repeated_rejections_close_descriptors(self):
        before = len(list(Path('/proc/self/fd').iterdir()))
        for i in range(12):
            path = self.mods / ('added-%d.txt' % i)
            self.rejects(lambda: path.write_text('synthetic'))
        self.assertEqual(before, len(list(Path('/proc/self/fd').iterdir())))

    def test_repeated_successes_close_descriptors_and_preserve_files(self):
        before = len(list(Path('/proc/self/fd').iterdir()))
        contents = {p.name: p.read_bytes() for p in self.mods.iterdir()}
        for _ in range(12):
            self.inspect_with(lambda: None)
        self.assertEqual(before, len(list(Path('/proc/self/fd').iterdir())))
        self.assertEqual(contents, {p.name: p.read_bytes() for p in self.mods.iterdir()})


if __name__ == '__main__':
    unittest.main()
