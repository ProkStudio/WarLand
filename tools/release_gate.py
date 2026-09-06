#!/usr/bin/env python3
"""Read-only release checklist. Attestations are operator evidence, never tests run by this tool."""
from __future__ import annotations
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone, timedelta
import hashlib
import json
import os
from pathlib import Path
import stat
import zipfile
import zlib

GATES = ('functional_stages_0_6', 'inventory_crash', 'war_permissions', 'vehicle_restart',
         'client_visual', 'load_10', 'load_20', 'load_30', 'restore_boot',
         'economy_balance', 'owner_cutover_approved')

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result: raise ValueError('Duplicate JSON field')
        result[key] = value
    return result

def read_json(path: Path):
    if path.stat().st_size > 4 * 1024**2: raise ValueError('JSON is too large')
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_object)

def properties(path: Path):
    result = {}
    for raw in path.read_text(encoding='utf-8').splitlines():
        line = raw.strip()
        if not line or line.startswith(('#', '!')): continue
        # Conservative subset: avoid interpreting Java escapes or continuation differently.
        if '\\' in line or '=' not in line: raise ValueError('Non-canonical server.properties')
        key, value = (part.strip() for part in line.split('=', 1))
        if not key or key in result: raise ValueError('Duplicate or empty server property')
        result[key] = value
    return result

def sha256(path: Path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024**2), b''):h.update(chunk)
    return h.hexdigest()

MAX_JAR_BYTES = 128 * 1024**2
MAX_MODS_BYTES = 512 * 1024**2
MAX_MOD_ENTRIES = 512
MAX_MOD_JARS = 128
MAX_METADATA_BYTES = 65536


def fingerprint(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


@contextmanager
def directory_fd(path: Path):
    """Walk each component without following symlinks (Linux operator tool)."""
    absolute = path.absolute()
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    fd = os.open(absolute.anchor, flags)
    try:
        for part in absolute.parts[1:]:
            child = os.open(part, flags, dir_fd=fd)
            os.close(fd)
            fd = child
        yield fd
    finally:
        os.close(fd)


@contextmanager
def mod_stream(parent_fd: int, name: str):
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC
    fd = os.open(name, flags, dir_fd=parent_fd)
    try:
        before = os.fstat(fd)
        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                or not 0 < before.st_size <= MAX_JAR_BYTES):
            raise ValueError('Unsafe or oversized mod file')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            yield stream, before
    finally:
        os.close(fd)


def inspect_mod(parent_fd: int, name: str):
    """Metadata and digest share one bounded, regular, no-follow descriptor."""
    with mod_stream(parent_fd, name) as (stream, before):
        with zipfile.ZipFile(stream) as jar:
            entries = [info for info in jar.infolist() if info.filename == 'fabric.mod.json']
            if len(entries) != 1 or entries[0].file_size > MAX_METADATA_BYTES:
                raise ValueError('Missing, ambiguous or oversized mod metadata')
            with jar.open(entries[0]) as metadata:
                raw = metadata.read(MAX_METADATA_BYTES + 1)
            if len(raw) > MAX_METADATA_BYTES:
                raise ValueError('Oversized mod metadata')
            mod = json.loads(raw, object_pairs_hook=unique_object)
            if not isinstance(mod, dict) or not isinstance(mod.get('id'), str) or not mod['id']:
                raise ValueError('Invalid Fabric mod metadata')
        stream.seek(0)
        digest = hashlib.sha256()
        total = 0
        for chunk in iter(lambda: stream.read(1024**2), b''):
            total += len(chunk)
            if total > MAX_JAR_BYTES:
                raise ValueError('Mod grew during inspection')
            digest.update(chunk)
        if (fingerprint(before) != fingerprint(os.fstat(stream.fileno()))
                or fingerprint(before) != fingerprint(os.stat(name, dir_fd=parent_fd, follow_symlinks=False))):
            raise ValueError('Mod changed during inspection')
        return mod, digest.hexdigest()


def mod_inventory(fd: int):
    """Bounded no-follow name/stat snapshot, including non-JAR entries."""
    manifest = {}
    names = []
    total_bytes = 0
    with os.scandir(fd) as entries:
        for count, entry in enumerate(entries, 1):
            if count > MAX_MOD_ENTRIES:
                raise ValueError('Too many mod directory entries')
            info = entry.stat(follow_symlinks=False)
            if not stat.S_ISREG(info.st_mode):
                raise ValueError('Non-flat or linked mod layout is unsupported')
            manifest[entry.name] = (fingerprint(info), info.st_mode, info.st_nlink)
            if entry.name.lower().endswith('.jar'):
                names.append(entry.name)
                total_bytes += info.st_size
                if len(names) > MAX_MOD_JARS or total_bytes > MAX_MODS_BYTES:
                    raise ValueError('Mod inventory exceeds inspection limits')
    return manifest, sorted(names)


def installed_artifact(runtime: Path, expected_sha: str):
    """Only a standard flat mods directory is supported; no loader execution."""
    with directory_fd(runtime / 'mods') as fd:
        before = fingerprint(os.fstat(fd))
        manifest, names = mod_inventory(fd)
        warland_hashes = []
        for name in names:
            mod, digest = inspect_mod(fd, name)
            if mod['id'] == 'warland':
                warland_hashes.append(digest)
        after_manifest, after_names = mod_inventory(fd)
        if (manifest != after_manifest or names != after_names
                or before != fingerprint(os.fstat(fd))):
            raise ValueError('Mod directory changed during inspection')
        if len(warland_hashes) != 1:
            raise ValueError('Exactly one installed WarLand mod is required')
        if warland_hashes[0] != expected_sha:
            raise ValueError('Installed WarLand differs from the approved artifact')


def check(runtime: Path, artifact: Path, evidence: Path, now: datetime | None = None):
    failures = []
    now = now or datetime.now(timezone.utc)
    try:
        config = read_json(runtime/'config/warland/core.json')
        props = properties(runtime/'server.properties')
        if props.get('online-mode') != 'true': failures.append('Public release requires online-mode=true and an approved UUID migration')
        if config.get('requireOnlineModeForPublic') is not True: failures.append('Public authentication guard must remain enabled')
        if type(config.get('mobilizationHours')) is not int or config['mobilizationHours'] != 12: failures.append('Mobilization must remain 12 hours')
        if config.get('schemaVersion') != 1: failures.append('Unsupported core config schema')
        data = read_json(evidence)
        if data.get('schema') != 1: failures.append('Unsupported evidence schema')
        with directory_fd(artifact.parent) as fd:
            mod, current_sha = inspect_mod(fd, artifact.name)
        if data.get('artifact_sha256') != current_sha: failures.append('Evidence is not tied to this exact artifact')
        if mod.get('id') != 'warland' or mod.get('depends', {}).get('minecraft') != '1.21.11':
            failures.append('Artifact is not the pinned WarLand Minecraft 1.21.11 build')
        try:
            installed_artifact(runtime, current_sha)
        except (OSError, ValueError, TypeError, AttributeError, KeyError, RuntimeError,
                NotImplementedError, EOFError, zipfile.BadZipFile, zlib.error):
            # Do not echo file names, metadata, targets or exception text.
            failures.append('Installed mod inventory is unsafe, ambiguous or does not match the approved artifact')
        approved = datetime.fromisoformat(str(data.get('recorded_at', '')).replace('Z', '+00:00'))
        if approved.tzinfo is None or approved > now or now-approved > timedelta(days=7):
            failures.append('Evidence timestamp is missing, future-dated or older than seven days')
        gates = data.get('gates', {})
        if not isinstance(gates, dict): raise ValueError('Gate records must be an object')
        for gate in GATES:
            record = gates.get(gate)
            if not isinstance(record, dict) or record.get('passed') is not True:
                failures.append('Missing acceptance: '+gate)
            elif not all(isinstance(record.get(k), str) and record[k].strip() for k in ('evidence', 'verified_by')):
                failures.append('Unattributed acceptance: '+gate)
    except (OSError, ValueError, TypeError, AttributeError, KeyError, RuntimeError,
            NotImplementedError, EOFError, zipfile.BadZipFile, zlib.error) as error:
        failures.append('Unreadable or invalid release inputs: '+type(error).__name__)
    return {'schema': 1, 'status': 'blocked' if failures else 'checklist_complete',
            'failures': failures, 'deploy_performed': False,
            'notice': 'Evidence records are operator attestations, not independently proven by this checker.'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runtime', type=Path)
    parser.add_argument('artifact', type=Path)
    parser.add_argument('evidence', type=Path)
    args = parser.parse_args()
    result = check(args.runtime, args.artifact, args.evidence)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 2 if result['status'] == 'blocked' else 0

if __name__ == '__main__': raise SystemExit(main())
