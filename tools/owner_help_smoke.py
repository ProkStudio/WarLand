#!/usr/bin/env python3
"""Fresh loopback-only native chat-help acceptance; never a live owner/account.
Seeds ONLY the single synthetic account as owner between stopped QA boots.
This tests authenticated owner rendering, NOT owner-enrollment acceptance.
"""
import argparse
import hashlib
import json
import os
import re
import sqlite3
from pathlib import Path
import vanilla_dialog_smoke as suite
import rtp_runtime_smoke as wire_tools

original_probe = suite.probe
original_stop = suite.base.stop
original_wire = suite.base.Wire
runtime = None
stops = 0
seed_ready = False


def protected():
    with sqlite3.connect(f'file:{runtime}/warland/warland.db?mode=ro', uri=True, timeout=3) as db:
        data = {table: db.execute('SELECT * FROM ' + table + ' ORDER BY 1').fetchall()
                for table in ('auth_owner', 'accounts', 'ledger')}
        data['moderation'] = db.execute("SELECT key,json FROM state WHERE namespace='moderation' ORDER BY key").fetchall()
    return hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()


def stop_and_seed(process):
    global stops
    rc = original_stop(process)
    stops += 1
    if stops == 1 and rc == 0 and seed_ready:
        if os.geteuid() == 0 or runtime.parent != Path('/opt/warland-build') or not runtime.name.startswith('vanilla-dialog-qa-owner-help-'):
            raise ValueError('Synthetic owner seeding refused outside private QA')
        with sqlite3.connect(runtime/'warland/warland.db') as db:
            db.execute('PRAGMA foreign_keys=ON')
            ids = db.execute('SELECT uuid FROM auth_accounts').fetchall()
            if ids != [(str(suite.IDENTITY),)]:
                raise ValueError('Only the newly registered single synthetic account may be seeded')
            if db.execute('SELECT uuid FROM auth_owner WHERE id=1').fetchall() != [(None,)]:
                raise ValueError('Refusing to replace any existing owner')
            if db.execute('UPDATE auth_owner SET uuid=? WHERE id=1 AND uuid IS NULL', (str(suite.IDENTITY),)).rowcount != 1:
                raise ValueError('Synthetic owner row missing')
            if db.execute('PRAGMA foreign_key_check').fetchall():
                raise ValueError('Synthetic owner violates FK')
    return rc


def command(wire, text, expected=None):
    wire.send(0x06, suite.base.text(text))
    return wire_tools.pump(wire, 8 if expected else 3, expected)


def help_probe(port, pin, password, mode, pack_url, pack_hash):
    global seed_ready
    if mode not in ('malformed-register', 'register', 'login'):
        return original_probe(port, pin, password, mode, pack_url, pack_hash)
    suite.base.Wire = wire_tools.RetainedWire
    wire = None
    try:
        result = original_probe(port, pin, password, mode, pack_url, pack_hash)
        wire = wire_tools.RetainedWire.current
        wire_tools.pump(wire, 2)
        before = protected()
        if mode != 'login':
            messages = command(wire, 'wladmin help 1')
            if not any(b'command.unknown.command' in m or b'command.unknown.argument' in m for m in messages):
                raise ValueError('Explicit non-owner command rejection not observed')
            if any('Команды владельца'.encode() in m or 'Пример:'.encode() in m for m in messages):
                raise ValueError('Non-owner received owner help')
            result['non_owner_help_denied'] = True
        else:
            with sqlite3.connect(f'file:{runtime}/warland/warland.db?mode=ro', uri=True) as db:
                if db.execute('SELECT uuid FROM auth_owner WHERE id=1').fetchall() != [(str(suite.IDENTITY),)]:
                    raise ValueError('Seeded synthetic owner did not survive restart')
            messages = command(wire, 'wladmin', 'Навигация: /wladmin help')
            joined = b' '.join(messages).decode('utf-8', errors='replace')
            match = re.search(r'Команды владельца · 1/(\d+) ·', joined)
            if not match or int(match.group(1)) != 7:
                raise ValueError('Expected complete 27-entry/7-page installed catalog missing')
            all_messages = list(messages)
            for page in range(1, 8):
                messages = command(wire, f'wladmin help {page}', 'Навигация: /wladmin help')
                if not any(f'Команды владельца · {page}/7 ·'.encode() in m for m in messages):
                    raise ValueError('Owner help page header missing')
                all_messages.extend(messages)
            for expected in ('/staff role', '/staff unban', '/capital budget', '/citybuild purchases',
                             'ОПАСНЫЙ ЭКСПЕРИМЕНТ', '/arsenal issue', '/vehicle recover', '/season schedule', '/season cancel'):
                if not any(expected.encode() in m for m in all_messages):
                    raise ValueError('Required documented command or safety warning missing')
            command(wire, 'wladmin help 2147483647', 'Страница справки: 1–7')
            command(wire, 'wladmin diag', 'WarLand ready=true')
            result.update(owner_help_pages=7, owner_help_chat=True, legacy_diag_preserved=True,
                          invalid_page_denied=True, synthetic_owner_seed_not_enrollment=True)
        if protected() != before:
            raise ValueError('Read-only help changed owner, accounts, ledger or moderation')
        result['help_protected_state_unchanged'] = True
        if mode != 'login':
            seed_ready = True
        return result
    finally:
        wire = wire or wire_tools.RetainedWire.current
        if wire is not None:
            wire.dispose()
        wire_tools.RetainedWire.current = None
        suite.base.Wire = original_wire


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--polymer', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--port', type=int, default=25590)
    p.add_argument('--http-port', type=int, default=18100)
    p.add_argument('--synthetic-fixture', action='store_true')
    a = p.parse_args()
    if not a.synthetic_fixture or not a.out.name.startswith('vanilla-dialog-qa-owner-help-'):
        p.error('Explicit new synthetic owner-help fixture required')
    runtime = a.out
    suite.probe = help_probe
    suite.base.stop = stop_and_seed
    raise SystemExit(suite.run(a))
