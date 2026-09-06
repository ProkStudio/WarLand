"""Synthetic disk fixtures, not Minecraft/client or release acceptance."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile
from unittest.mock import patch

import release_gate


class ArtifactBindingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runtime = self.root / 'runtime'
        (self.runtime / 'config/warland').mkdir(parents=True)
        (self.runtime / 'server.properties').write_text('online-mode=true\n')
        (self.runtime / 'config/warland/core.json').write_text(json.dumps({
            'schemaVersion': 1, 'requireOnlineModeForPublic': True, 'mobilizationHours': 12}))
        self.mods = self.runtime / 'mods'
        self.mods.mkdir()
        self.artifact = self.root / 'approved.jar'
        self.write_jar(self.artifact)
        self.installed = self.mods / 'renamed-preview.jar'
        shutil.copyfile(self.artifact, self.installed)
        self.now = datetime(2026, 9, 6, 10, tzinfo=timezone.utc)
        self.evidence = self.root / 'evidence.json'
        self.attest()

    def write_jar(self, path, mod_id='warland', marker='new', metadata=None):
        if metadata is None:
            metadata = json.dumps({'id': mod_id, 'depends': {'minecraft': '1.21.11'}})
        with zipfile.ZipFile(path, 'w') as jar:
            jar.writestr('fabric.mod.json', metadata)
            jar.writestr('test-marker', marker)

    def attest(self):
        self.evidence.write_text(json.dumps({
            'schema': 1, 'artifact_sha256': release_gate.sha256(self.artifact),
            'recorded_at': self.now.isoformat(),
            'gates': {key: {'passed': True, 'evidence': 'synthetic unit fixture',
                            'verified_by': 'unit test'} for key in release_gate.GATES}}))

    def result(self):
        return release_gate.check(self.runtime, self.artifact, self.evidence, self.now)

    def blocked(self):
        result = self.result()
        self.assertEqual('blocked', result['status'], result)
        self.assertFalse(result['deploy_performed'])
        return result

    def test_missing_mods_directory_blocks(self):
        self.installed.unlink(); self.mods.rmdir()
        self.blocked()

    def test_empty_mods_directory_blocks(self):
        self.installed.unlink()
        self.blocked()

    def test_old_installed_bytes_with_approved_external_jar_blocks(self):
        self.write_jar(self.installed, marker='old')
        self.blocked()

    def test_renamed_matching_installed_copy_passes(self):
        self.assertEqual('checklist_complete', self.result()['status'])

    def test_artifact_can_be_the_installed_file(self):
        self.artifact = self.installed
        self.assertEqual('checklist_complete', self.result()['status'])

    def test_other_fabric_mod_is_allowed(self):
        self.write_jar(self.mods / 'fabric-api.jar', mod_id='fabric-api')
        self.assertEqual('checklist_complete', self.result()['status'])

    def test_filename_alone_does_not_identify_warland(self):
        self.installed.unlink()
        self.write_jar(self.mods / 'warland.jar', mod_id='not-warland')
        self.blocked()

    def test_duplicate_identical_warland_blocks(self):
        shutil.copyfile(self.installed, self.mods / 'another-name.jar')
        self.blocked()

    def test_duplicate_old_warland_blocks(self):
        self.write_jar(self.mods / 'old.jar', marker='old')
        self.blocked()

    def test_corrupt_installed_jar_blocks(self):
        self.installed.write_bytes(b'not a zip')
        self.blocked()

    def test_corrupt_other_jar_blocks(self):
        (self.mods / 'unknown.jar').write_bytes(b'not a zip')
        self.blocked()

    def test_missing_metadata_blocks(self):
        with zipfile.ZipFile(self.installed, 'w') as jar:
            jar.writestr('something', 'not a Fabric mod')
        self.blocked()

    def test_duplicate_zip_metadata_blocks_even_when_hash_matches(self):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.artifact, 'a') as jar:
                jar.writestr('fabric.mod.json', '{"id":"warland","depends":{"minecraft":"1.21.11"}}')
        self.attest(); shutil.copyfile(self.artifact, self.installed)
        self.blocked()

    def test_invalid_metadata_shapes_and_duplicate_json_block(self):
        for metadata in ['[]', 'null', '"warland"', '{"id":1}',
                         '{"id":"x","id":"warland"}', '{broken',
                         '{"id":"warland","depends":[]}',
                         '{"id":"warland","depends":{"minecraft":"1.21.10"}}']:
            with self.subTest(metadata=metadata):
                self.write_jar(self.artifact, metadata=metadata)
                self.attest(); shutil.copyfile(self.artifact, self.installed)
                self.blocked()

    def test_oversized_metadata_blocks(self):
        self.write_jar(self.installed, metadata=' ' * 65537)
        self.blocked()

    def test_symlinked_mods_directory_blocks(self):
        other = self.root / 'elsewhere'
        self.mods.rename(other); self.mods.symlink_to(other, target_is_directory=True)
        self.blocked()

    def test_symlinked_installed_jar_blocks_without_exposing_target(self):
        self.installed.unlink(); self.installed.symlink_to(self.artifact)
        result = self.blocked()
        self.assertNotIn(str(self.artifact), json.dumps(result))

    def test_symlinked_artifact_blocks(self):
        link = self.root / 'linked.jar'; link.symlink_to(self.artifact)
        self.artifact = link
        self.blocked()

    def test_symlinked_artifact_parent_blocks(self):
        real = self.root / 'real'; real.mkdir()
        shutil.copyfile(self.artifact, real / 'artifact.jar')
        link = self.root / 'alias'; link.symlink_to(real, target_is_directory=True)
        self.artifact = link / 'artifact.jar'
        self.blocked()

    def test_hardlinked_installed_jar_blocks(self):
        self.installed.unlink(); os.link(self.artifact, self.installed)
        self.blocked()

    def test_nonregular_jar_blocks(self):
        self.installed.unlink(); self.installed.mkdir()
        self.blocked()

    def test_nested_mod_layout_is_not_silently_ignored(self):
        (self.mods / '1.21.11').mkdir()
        self.blocked()

    def test_nonjar_notes_and_disabled_files_are_ignored(self):
        (self.mods / 'README.txt').write_text('synthetic')
        (self.mods / 'old.jar.disabled').write_text('disabled')
        self.assertEqual('checklist_complete', self.result()['status'])

    def test_fifo_jar_fails_promptly_in_cli(self):
        self.installed.unlink(); os.mkfifo(self.installed)
        result = subprocess.run([sys.executable, str(Path(release_gate.__file__)),
                                 str(self.runtime), str(self.artifact), str(self.evidence)],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(2, result.returncode)
        self.assertEqual('blocked', json.loads(result.stdout)['status'])
        self.assertNotIn(str(self.installed), result.stdout)
        self.assertEqual('', result.stderr)

    def test_sensitive_filename_and_content_never_appear_in_failure(self):
        canary = 'FAKE-SECRET-DO-NOT-REPORT'
        self.write_jar(self.mods / (canary + '.jar'), metadata=canary)
        self.assertNotIn(canary, json.dumps(self.blocked()))

    def test_inspection_limits_fail_closed(self):
        for key in ['MAX_JAR_BYTES', 'MAX_MODS_BYTES', 'MAX_MOD_ENTRIES', 'MAX_MOD_JARS']:
            with self.subTest(limit=key), patch.object(release_gate, key, 0):
                self.blocked()

    def test_oversized_sparse_jar_blocks_without_full_read(self):
        with self.installed.open('wb') as stream:
            stream.truncate(release_gate.MAX_JAR_BYTES + 1)
        self.blocked()

    def test_corrupt_compressed_metadata_blocks(self):
        with zipfile.ZipFile(self.installed, 'w', compression=zipfile.ZIP_DEFLATED) as jar:
            jar.writestr('fabric.mod.json', '{"id":"warland"}')
        raw = bytearray(self.installed.read_bytes())
        offset = 30 + len('fabric.mod.json')
        raw[offset] ^= 255
        self.installed.write_bytes(raw)
        self.blocked()

    def test_inventory_changed_during_inspection_blocks(self):
        original = release_gate.inspect_mod
        def inspect(fd, name):
            result = original(fd, name)
            (self.mods / 'added-during-check.txt').write_text('synthetic')
            return result
        with patch.object(release_gate, 'inspect_mod', side_effect=inspect):
            with self.assertRaises(ValueError):
                release_gate.installed_artifact(self.runtime, release_gate.sha256(self.artifact))

    def test_mod_modified_during_metadata_read_blocks(self):
        original = zipfile.ZipFile
        def open_and_modify(stream, *args, **kwargs):
            jar = original(stream, *args, **kwargs)
            with self.installed.open('ab') as changed:
                changed.write(b'changed-after-open')
            return jar
        with release_gate.directory_fd(self.mods) as fd:
            with patch.object(release_gate.zipfile, 'ZipFile', side_effect=open_and_modify):
                with self.assertRaises(ValueError):
                    release_gate.inspect_mod(fd, self.installed.name)

    def test_file_replaced_during_metadata_read_blocks(self):
        original = zipfile.ZipFile
        def open_and_replace(stream, *args, **kwargs):
            jar = original(stream, *args, **kwargs)
            self.installed.unlink()
            shutil.copyfile(self.artifact, self.installed)
            return jar
        with release_gate.directory_fd(self.mods) as fd:
            with patch.object(release_gate.zipfile, 'ZipFile', side_effect=open_and_replace):
                with self.assertRaises(ValueError):
                    release_gate.inspect_mod(fd, self.installed.name)

    def test_repeated_failures_do_not_leak_descriptors(self):
        self.artifact = self.root / 'directory.jar'; self.artifact.mkdir()
        before = len(list(Path('/proc/self/fd').iterdir()))
        for _ in range(30):
            self.blocked()
        self.assertEqual(before, len(list(Path('/proc/self/fd').iterdir())))

    def test_repeated_successes_do_not_leak_descriptors(self):
        before = len(list(Path('/proc/self/fd').iterdir()))
        for _ in range(30):
            self.assertEqual('checklist_complete', self.result()['status'])
        self.assertEqual(before, len(list(Path('/proc/self/fd').iterdir())))

    def test_failed_check_does_not_mutate_files(self):
        self.write_jar(self.installed, marker='old')
        before = self.installed.read_bytes()
        self.blocked()
        self.assertEqual(before, self.installed.read_bytes())

    def test_success_does_not_mutate_files(self):
        def snapshot():
            return {str(p.relative_to(self.root)): p.read_bytes()
                    for p in self.root.rglob('*') if p.is_file()}
        before = snapshot()
        self.assertEqual('checklist_complete', self.result()['status'])
        self.assertEqual(before, snapshot())


if __name__ == '__main__':
    unittest.main()
