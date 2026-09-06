#!/usr/bin/env python3
"""Fresh-fixture Fabric console acceptance; never production or real enrollment."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import sqlite3
import stat
import subprocess
import sys
import time

from auth_encrypted_smoke import database
from private_console import send


def wait_for(test, process, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline and process.poll() is None:
        if test():
            return
        time.sleep(.1)
    raise RuntimeError('Timed out or test process exited before expected condition')


def stop_bridge(process):
    if process is None:
        return None
    if process.poll() is None:
        process.send_signal(signal.SIGTERM)
        process.wait(timeout=75)
    return process.returncode


def run(args):
    os.umask(0o077)
    root = Path('/opt/warland-build')
    source, jar, out = (p.resolve() for p in (args.source, args.jar, args.out))
    if os.geteuid() == 0 or not args.synthetic_fixture:
        raise ValueError('Use the unprivileged build user and explicit synthetic-fixture acknowledgment')
    if source != root/'release-qa/final-runtime' or out.parent != root or not out.name.startswith('private-console-qa-'):
        raise ValueError('Only the known synthetic fixture and new console QA output are permitted')
    if out.exists() or args.out.is_symlink() or (source/'warland/private-auth').exists():
        raise ValueError('Refusing previous evidence or source containing private-auth')
    if hashlib.sha256(jar.read_bytes()).hexdigest() != args.sha256:
        raise ValueError('Artifact hash mismatch')
    initial = database(source)
    if any(initial[k] for k in ('profiles', 'auth_accounts', 'accounts', 'ledger')):
        raise ValueError('Fixture is not empty of player/economy records')
    if json.loads((source/'ops.json').read_text()) != []:
        raise ValueError('Fixture has operators')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', args.port))
    shutil.copytree(source, out, ignore=shutil.ignore_patterns('logs', '*.log', '*-result.json', 'usercache.json'))
    report = {'scope': 'Synthetic Fabric console/bootstrap lifecycle only; no real owner, Mojang/client/load acceptance',
              'source_sha': args.source_sha, 'artifact_sha256': args.sha256, 'cycles': [], 'success': False}
    report_path = out/'private-console-result.json'
    def save():
        report_path.write_text(json.dumps(report, indent=2)+'\n')
    process = None
    secret = b''
    try:
        for old in (out/'mods').glob('warland-*.jar'):
            old.unlink()
        shutil.copy2(jar, out/'mods'/jar.name)
        props = out/'server.properties'
        values = {'server-ip': '127.0.0.1', 'server-port': str(args.port), 'online-mode': 'true',
                  'enable-rcon': 'false', 'enable-query': 'false', 'max-players': '2',
                  'view-distance': '3', 'simulation-distance': '3'}
        lines = [line for line in props.read_text().splitlines() if line.partition('=')[0] not in values]
        props.write_text('\n'.join(lines+[k+'='+v for k,v in values.items()])+'\n')
        directory = out/'console'
        directory.mkdir(mode=0o700)
        console = directory/'control.sock'
        proof = out/'warland/private-auth/owner-bootstrap.txt'
        bridge = Path(__file__).with_name('private_console.py').resolve()
        for cycle in (1, 2):
            log = out/f'console-boot-{cycle}.log'
            item = {'cycle': cycle}
            report['cycles'].append(item)
            with log.open('x') as output:
                process = subprocess.Popen([sys.executable, str(bridge), 'serve', '--socket', str(console),
                    '--cwd', str(out), '--grace', '60', '--', 'nice', '-n', '10', 'java',
                    '-Xms128M', '-Xmx640M', '-XX:ActiveProcessorCount=1',
                    '-jar', 'fabric-server-launch.jar', 'nogui'], stdout=output, stderr=subprocess.STDOUT)
                wait_for(lambda: 'WarLand ready:' in log.read_text(errors='replace') and
                         'Done (' in log.read_text(errors='replace') and console.exists(), process, 110)
                item['socket_mode'] = oct(stat.S_IMODE(console.stat().st_mode))
                item['directory_mode'] = oct(stat.S_IMODE(directory.stat().st_mode))
                if item['socket_mode'] != '0o600' or item['directory_mode'] != '0o700':
                    raise ValueError('Incorrect socket permissions')
                if send(console, 'warland status').get('status') != 'queued':
                    raise ValueError('Status command rejected')
                wait_for(lambda: 'WarLand alpha | ready=true' in log.read_text(errors='replace'), process)
                item['real_console_status'] = True
                if send(console, 'warland-owner-bootstrap').get('status') != 'queued':
                    raise ValueError('Synthetic bootstrap command not queued')
                wait_for(lambda: proof.exists() and proof.stat().st_size == 43 and
                         'Bootstrap сохранён' in log.read_text(errors='replace'), process)
                secret = proof.read_bytes()
                if (not re.fullmatch(rb'[A-Za-z0-9_-]{43}', secret)
                        or stat.S_IMODE(proof.stat().st_mode) != 0o600
                        or stat.S_IMODE(proof.parent.stat().st_mode) != 0o700):
                    raise ValueError('Synthetic private proof contract failed')
                item['private_proof_created'] = True
                before = hashlib.sha256(secret).digest()
                if send(console, 'warland-owner-bootstrap').get('status') != 'queued':
                    raise ValueError('Repeated command did not reach queue')
                wait_for(lambda: 'существующий bootstrap не перезаписывается' in log.read_text(errors='replace'), process)
                if hashlib.sha256(proof.read_bytes()).digest() != before:
                    raise ValueError('Repeated bootstrap overwrote proof')
                item['existing_proof_preserved'] = True
                if secret in log.read_bytes():
                    raise ValueError('Synthetic secret was exposed in server log')
                proof.unlink()
                item['plaintext_removed'] = True
                item['exit'] = stop_bridge(process)
                process = None
            text = log.read_text(errors='replace')
            item['errors'] = [s for s in text.splitlines() if re.search(r'\bERROR\b|Exception|InjectionError', s)][:20]
            item['warnings'] = [s for s in text.splitlines() if '/WARN]' in s][:20]
            if secret in log.read_bytes():
                raise ValueError('Synthetic secret appeared during shutdown')
            secret = b''
            item['database'] = database(out)
            if item['database'] != initial or item['errors'] or item['exit'] != 0 or console.exists():
                raise ValueError('Console lifecycle, shutdown or persistent-state invariant failed')
            with sqlite3.connect(f'file:{out}/warland/warland.db?mode=ro', uri=True) as connection:
                if connection.execute('SELECT COUNT(*) FROM auth_owner WHERE uuid IS NOT NULL').fetchone()[0]:
                    raise ValueError('Unexpected owner binding')
            if json.loads((out/'ops.json').read_text()) != []:
                raise ValueError('Unexpected vanilla OP')
            item['no_owner_or_op'] = True
            save()
        report['success'] = True
    except Exception as error:
        # No exception messages or command input: avoid leaking a test proof.
        report['error_type'] = type(error).__name__
    finally:
        if process is not None:
            report['cleanup_exit'] = stop_bridge(process)
        proof = out/'warland/private-auth/owner-bootstrap.txt'
        if proof.is_file() and not proof.is_symlink():
            proof.unlink()
        save()
        (out/'result.exit').write_text('0\n' if report['success'] else '1\n')
        print(json.dumps(report, indent=2))
    return 0 if report['success'] else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--port', type=int, default=25569)
    parser.add_argument('--synthetic-fixture', action='store_true')
    raise SystemExit(run(parser.parse_args()))
