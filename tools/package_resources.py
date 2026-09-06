#!/usr/bin/env python3
"""Deterministic, credential-free Minecraft 1.21.11 resource-pack builder."""
from pathlib import Path
import argparse, hashlib, json, os, tempfile, zipfile

MAX_ASSET_BYTES = 8 * 1024 * 1024
MAX_PACK_BYTES = 64 * 1024 * 1024
ALLOWED_SUFFIXES = {'.json', '.png', '.ogg', '.mcmeta'}


def pack(source: Path, output: Path) -> dict:
    source = Path(source)
    output = Path(output)
    if source.is_symlink() or not source.is_dir():
        raise ValueError('Asset root must be a real directory')
    root = source.resolve()
    if output.is_symlink() or output.exists():
        raise ValueError('Refusing to replace an existing pack')
    if output.resolve().is_relative_to(root):
        raise ValueError('Output must be outside the asset tree')
    entries = {}
    total = 0
    for path in sorted(source.rglob('*')):
        if path.is_symlink():
            raise ValueError('Asset symlinks are not permitted')
        if path.is_dir():
            continue
        relative = path.relative_to(source).as_posix()
        if not path.is_file() or not path.resolve().is_relative_to(root):
            raise ValueError('Unsafe asset path')
        if any(part.startswith('.') for part in path.relative_to(source).parts):
            raise ValueError('Hidden files are not assets')
        if path.suffix not in ALLOWED_SUFFIXES or path.stat().st_size > MAX_ASSET_BYTES:
            raise ValueError('Unsupported or oversized asset')
        data = path.read_bytes()
        total += len(data)
        if total > MAX_PACK_BYTES:
            raise ValueError('Asset budget exceeded')
        if path.suffix in {'.json', '.mcmeta'}:
            json.loads(data)
        entries['assets/' + relative] = data
    if not entries:
        raise ValueError('No assets to package')
    for required in ('assets/warland/items/ak74.json', 'assets/warland/items/magazine_545.json'):
        if required not in entries:
            raise ValueError('Missing required item definition')
    entries['pack.mcmeta'] = (json.dumps({'pack': {'description': 'WarLand — серверные ресурсы',
        'min_format': [75, 0], 'max_format': [75, 0]}}, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.warland-pack-', suffix='.zip', dir=output.parent)
    try:
        with os.fdopen(fd, 'w+b') as raw:
            with zipfile.ZipFile(raw, 'w', compression=zipfile.ZIP_STORED) as archive:
                for name, data in sorted(entries.items()):
                    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                    info.create_system = 3
                    info.external_attr = (0o100644 << 16)
                    archive.writestr(info, data)
            raw.flush(); os.fsync(raw.fileno())
        with zipfile.ZipFile(temporary) as archive:
            if archive.testzip() is not None:
                raise ValueError('Pack CRC verification failed')
        os.link(temporary, output)
        data = output.read_bytes()
        return {'file': output.name, 'size': len(data), 'entries': len(entries),
            'sha1': hashlib.sha1(data).hexdigest(), 'sha256': hashlib.sha256(data).hexdigest()}
    finally:
        Path(temporary).unlink(missing_ok=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, default=Path(__file__).resolve().parents[1] / 'src/main/resources/assets')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(pack(args.assets, args.out), indent=2))
