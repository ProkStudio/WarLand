#!/usr/bin/env python3
"""Verify and restore an offline backup into a NEW private directory; never start a service."""
from __future__ import annotations
import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import tarfile
import time

class RestoreError(ValueError):
    pass

def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

def normalized(name: str) -> str:
    p = PurePosixPath(name)
    if not name or '\\' in name or p.is_absolute() or '..' in p.parts:
        raise RestoreError('Unsafe archive path')
    value = str(p)
    if ':' in value or any(ord(c) < 32 for c in value):
        raise RestoreError('Unsafe archive filename')
    return value

def manifest(archive: tarfile.TarFile, max_files: int, max_bytes: int):
    entries = []
    seen = set()
    files = set()
    total = 0
    for count, item in enumerate(archive, 1):
        if count > max_files:
            raise RestoreError('Archive entry limit exceeded')
        name = normalized(item.name)
        if not (item.isfile() or item.isdir()) or item.issparse():
            raise RestoreError('Links, devices, pipes and sparse entries are forbidden')
        if name == '.':
            if not item.isdir():
                raise RestoreError('Invalid archive root')
            continue
        if name in seen:
            raise RestoreError('Duplicate archive path')
        seen.add(name)
        if item.size < 0 or (item.isdir() and item.size != 0):
            raise RestoreError('Invalid archive entry size')
        total += item.size
        if total > max_bytes:
            raise RestoreError('Expanded backup size limit exceeded')
        if item.isfile():
            files.add(name)
        entries.append((item, name))
    for _, name in entries:
        if any(str(parent) in files for parent in PurePosixPath(name).parents):
            raise RestoreError('File shadows an archive directory')
    required = {'server.properties', 'fabric-server-launch.jar', 'warland/warland.db'}
    if not required.issubset(files):
        raise RestoreError('Not a complete WarLand runtime backup')
    return entries, total

def verify_databases(root: Path, seconds: int = 30) -> int:
    databases = sorted(root.rglob('*.db'))
    for path in databases:
        deadline = time.monotonic() + seconds
        try:
            with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True, timeout=5)) as db:
                db.execute('PRAGMA trusted_schema=OFF')
                db.set_progress_handler(lambda: int(time.monotonic() > deadline), 10000)
                checks = db.execute('PRAGMA quick_check').fetchall()
                if checks != [('ok',)] or db.execute('PRAGMA foreign_key_check').fetchone() is not None:
                    raise RestoreError('Restored SQLite integrity check failed')
        except sqlite3.Error as error:
            raise RestoreError('Restored SQLite could not be verified') from error
    return len(databases)

def restore(archive_path: Path, destination: Path, expected: str | None = None,
            max_files: int = 200_000, max_bytes: int = 20 * 1024**3) -> dict:
    archive_path = archive_path.resolve(strict=True)
    if not archive_path.is_file() or max_files < 1 or max_bytes < 1:
        raise RestoreError('Invalid archive or limits')
    if expected is None:
        checksum = Path(str(archive_path) + '.sha256').read_text(encoding='ascii').strip().splitlines()
        if len(checksum) != 1:
            raise RestoreError('Checksum file must contain one SHA-256 record')
        expected = checksum[0].split()[0] if checksum[0].split() else ''
    if not re.fullmatch(r'[0-9a-fA-F]{64}', expected):
        raise RestoreError('Expected SHA-256 is invalid')
    actual = digest(archive_path)
    if actual != expected.lower():
        raise RestoreError('Backup checksum mismatch; nothing restored')
    destination = destination.absolute()
    if destination.exists() or destination.is_symlink():
        raise RestoreError('Destination must not exist; live directories cannot be overwritten')
    parent = destination.parent.resolve(strict=True)
    if not parent.is_dir():
        raise RestoreError('Destination parent must already exist')
    destination = parent / destination.name
    # Atomic directory creation reserves the name. Only this new private directory is ever cleaned up.
    destination.mkdir(mode=0o700)
    try:
        with tarfile.open(archive_path, mode='r:gz') as archive:
            entries, total = manifest(archive, max_files, max_bytes)
            free = shutil.disk_usage(parent).free
            if total + 64 * 1024**2 > free:
                raise RestoreError('Not enough free space for an isolated restore')
            for item, name in entries:
                target = destination / name
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                if item.isdir():
                    target.mkdir(mode=0o700, exist_ok=True)
                    continue
                source = archive.extractfile(item)
                if source is None:
                    raise RestoreError('Missing archive data')
                fd = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                with source, os.fdopen(fd, 'wb') as output:
                    shutil.copyfileobj(source, output, 1024 * 1024)
                if target.stat().st_size != item.size:
                    raise RestoreError('Truncated archive entry')
        db_count = verify_databases(destination)
        report = {'schema': 1, 'sha256': actual, 'entries': len(entries), 'expanded_bytes': total,
                  'sqlite_databases_checked': db_count, 'status': 'verified_isolated_restore',
                  'services_started': False, 'production_modified': False}
        # Refuse to clobber even an archive-supplied report.
        with (destination / 'restore-verification.json').open('x', encoding='utf-8') as out:
            json.dump(report, out, ensure_ascii=False, indent=2)
            out.write('\n')
        (destination / 'restore-verification.json').chmod(0o600)
        return report
    except BaseException:
        shutil.rmtree(destination)
        raise

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--sha256')
    parser.add_argument('--max-files', type=int, default=200_000)
    parser.add_argument('--max-gib', type=int, default=20)
    args = parser.parse_args()
    try:
        result = restore(args.archive, args.destination, args.sha256, args.max_files, args.max_gib * 1024**3)
    except (OSError, EOFError, ValueError, tarfile.TarError) as error:
        print(json.dumps({'status': 'rejected', 'reason': str(error)}, ensure_ascii=False))
        return 2
    print(json.dumps(result, ensure_ascii=False))
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
