#!/usr/bin/env python3
"""Read-only release checklist. Attestations are operator evidence, never tests run by this tool."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import json
from pathlib import Path
import zipfile

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
        current_sha = sha256(artifact)
        if data.get('artifact_sha256') != current_sha: failures.append('Evidence is not tied to this exact artifact')
        with zipfile.ZipFile(artifact) as jar:
            info = jar.getinfo('fabric.mod.json')
            if info.file_size > 65536: raise ValueError('Oversized mod metadata')
            mod = json.loads(jar.read(info), object_pairs_hook=unique_object)
            if mod.get('id') != 'warland' or mod.get('depends', {}).get('minecraft') != '1.21.11':
                failures.append('Artifact is not the pinned WarLand Minecraft 1.21.11 build')
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
    except (OSError, ValueError, TypeError, AttributeError, KeyError, zipfile.BadZipFile) as error:
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
