#!/usr/bin/env python3
"""QA-only runner; execute nonroot under separately acquired shared build.lock."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import zipfile

ROOT = Path('/opt/warland-build/agent-002-20260906-1428-codec-qa-v1')
SOURCE = Path('/opt/warland-build/release-qa/final-runtime')
SHA = 'b4170b8bc73f66e763e481dc0d521d5cc2acbc1d'
BRANCH = 'agent/agent-002-20260906-1428/inventory-codec-v1'
GRADLE_SHA = '72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f'
PROBE = Path(__file__).with_name('MarketCodecRuntimeProbe.java')

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024**2), b''): h.update(block)
    return h.hexdigest()

def run(command, cwd, log, timeout=360):
    with (ROOT / log).open('xb') as out:
        process = subprocess.Popen(command, cwd=cwd, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
        (ROOT / (log + '.pid')).write_text(str(process.pid))
        try: result = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try: process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL); process.wait(timeout=15)
            raise RuntimeError('Own QA command timed out: ' + log)
    if result: raise RuntimeError('Command failed: ' + log)

def gradle(checkout):
    candidates = sorted(Path('/opt/warland-build').glob('*/.tools/gradle-9.2.1-bin.zip'))
    archive = next((p for p in candidates if digest(p) == GRADLE_SHA), None)
    if archive is None: raise RuntimeError('Verified Gradle archive unavailable')
    tools = checkout / '.tools'; tools.mkdir()
    shutil.copyfile(archive, tools / archive.name)

def artifact(checkout):
    files = [p for p in (checkout / 'build/libs').glob('*.jar') if not p.name.endswith('-sources.jar')]
    if len(files) != 1: raise RuntimeError('Ambiguous executable artifact')
    return files[0]

def boot(runtime, cycle):
    # Parent runner itself must be started in background. Only its own child is terminated on timeout.
    log = ROOT / ('runtime-%d.log' % cycle)
    with log.open('xb') as output:
        process = subprocess.Popen(['/usr/lib/jvm/java-21-openjdk-amd64/bin/java', '-Xms256m', '-Xmx640m',
            '-Dwarland.codecProbe=true', '-jar', 'fabric-server-launch.jar', 'nogui'],
            cwd=runtime, stdout=output, stderr=subprocess.STDOUT)
        (ROOT / ('runtime-%d.pid' % cycle)).write_text(str(process.pid))
        try: result = process.wait(timeout=240)
        except subprocess.TimeoutExpired:
            process.terminate()
            try: process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=15)
            raise RuntimeError('Own synthetic runtime timed out')
    text = log.read_text(errors='replace')
    if result != 0 or 'WARLAND_CODEC_PROBE_PASS checks=' not in text or 'WARLAND_CODEC_PROBE_FAIL' in text:
        raise RuntimeError('Runtime probe failed: cycle%d' % cycle)
    if cycle == 2 and 'WARLAND_CODEC_PROBE_RESTART_PASS' not in text:
        raise RuntimeError('Restart evidence absent')
    return {'exit': result, 'log_sha256': digest(log),
            'markers': [line[line.index('WARLAND_CODEC_PROBE_'):] for line in text.splitlines() if 'WARLAND_CODEC_PROBE_' in line]}

def main():
    if os.getuid() == 0: raise RuntimeError('Must not execute as root')
    if ROOT.exists() or ROOT.is_symlink(): raise RuntimeError('Output exists; never overwrite')
    if not (SOURCE / '.warland-qa-only').is_file(): raise RuntimeError('Synthetic fixture marker required')
    if not PROBE.is_file(): raise RuntimeError('Probe source missing')
    import socket
    with socket.socket() as sock: sock.bind(('127.0.0.1', 25579))
    if shutil.disk_usage(ROOT.parent).free < 2 * 1024**3: raise RuntimeError('Less than 2GiB free')
    ROOT.mkdir(mode=0o700)
    env = {'JAVA_HOME': '/usr/lib/jvm/java-21-openjdk-amd64', 'GRADLE_USER_HOME': '/opt/warland-build/.gradle'}
    os.environ.update(env)
    checkout = ROOT / 'source'
    run(['git', 'clone', '--single-branch', '--branch', BRANCH, 'https://github.com/ProkStudio/WarLand.git', str(checkout)], ROOT, 'clone.log')
    run(['git', 'checkout', '--detach', SHA], checkout, 'checkout.log')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout, text=True).strip()
    if head != SHA: raise RuntimeError('Source branch moved; re-coordinate exact SHA')
    gradle(checkout)
    run(['python3', 'tools/build.py', '--offline', '-Dorg.gradle.jvmargs=-Xmx1024m', 'clean', 'test', 'build'], checkout, 'build.log', 600)
    run(['env', 'PYTHONPATH=tools', 'python3', '-m', 'unittest', 'discover', '-s', 'tools/tests', '-v'], checkout, 'python.log')
    original = artifact(checkout)
    probe_build = ROOT / 'probe-source'
    shutil.copytree(checkout, probe_build, ignore=shutil.ignore_patterns('.git', '.gradle', '.tools', 'build'))
    java = probe_build / 'src/main/java/ru/warland/qa/MarketCodecRuntimeProbe.java'
    java.parent.mkdir(parents=True); shutil.copyfile(PROBE, java)
    # Only this new QA source copy changes its entrypoint/resources, never the tested code or production manifest.
    resources = probe_build / 'qa-resources'; resources.mkdir()
    (resources / 'fabric.mod.json').write_text(json.dumps({'schemaVersion':1,'id':'warland-codec-probe','version':'1',
        'name':'WarLand codec QA only','environment':'server','entrypoints':{'main':['ru.warland.qa.MarketCodecRuntimeProbe']},
        'depends':{'fabricloader':'>=0.19.5','minecraft':'1.21.11','java':'>=21','fabric-api':'*'}}))
    with (probe_build / 'build.gradle').open('a') as out:
        out.write("\nsourceSets.main.resources.setSrcDirs(['qa-resources'])\n")
    gradle(probe_build)
    run(['python3','tools/build.py','--offline','-Dorg.gradle.jvmargs=-Xmx1024m','clean','build','-x','test'], probe_build, 'probe-build.log', 600)
    probe_jar = artifact(probe_build)
    # The probed codec and planner must be the exact intermediary bytes of the ordinary published-source artifact.
    with zipfile.ZipFile(original) as left, zipfile.ZipFile(probe_jar) as right:
        names = [name for name in left.namelist() if name.startswith('ru/warland/economy/InventorySnapshot') or name == 'ru/warland/economy/MarketStackCodec.class']
        if len(names) != 4 or any(left.read(n) != right.read(n) for n in names): raise RuntimeError('Probe code differs from artifact')
    runtime = ROOT / 'runtime'; runtime.mkdir(mode=0o700)
    # Only cold fixture assets/caches are copied; no source worlds, DBs, accounts, configs or logs are read/modified.
    for folder in ['libraries', 'versions', '.fabric']:
        source = SOURCE / folder
        if source.is_symlink(): raise RuntimeError('Linked fixture folder')
        for parent, dirs, files in os.walk(source, followlinks=False):
            if any((Path(parent) / name).is_symlink() for name in dirs + files):
                raise RuntimeError('Linked entry in fixture cache')
        shutil.copytree(source, runtime / folder, symlinks=False)
    shutil.copyfile(SOURCE / 'fabric-server-launch.jar', runtime / 'fabric-server-launch.jar')
    (runtime / 'mods').mkdir()
    apis = []
    for path in (SOURCE / 'mods').glob('*.jar'):
        if path.is_symlink(): raise RuntimeError('Linked fixture mod')
        with zipfile.ZipFile(path) as archive:
            if json.loads(archive.read('fabric.mod.json')).get('id') == 'fabric-api': apis.append(path)
    if len(apis) != 1: raise RuntimeError('Expected one Fabric API bundle')
    shutil.copyfile(apis[0], runtime / 'mods/fabric-api.jar')
    shutil.copyfile(probe_jar, runtime / 'mods/codec-probe.jar')
    (runtime / '.warland-qa-only').write_text('agent-002 codec synthetic QA only\n')
    (runtime / 'eula.txt').write_text('eula=true\n')
    (runtime / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25579\nonline-mode=true\nwhite-list=true\nenforce-whitelist=true\nmax-players=1\nview-distance=2\nsimulation-distance=2\nlevel-name=codec-qa-world\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","lakes":false,"features":false}\ngenerate-structures=false\nspawn-protection=0\nenable-rcon=false\nenable-query=false\nsync-chunk-writes=true\nmotd=Private codec QA only\n')
    results = [boot(runtime, cycle) for cycle in (1, 2)]
    report = {'source':SHA,'artifact_sha256':digest(original),'probe_jar_sha256':digest(probe_jar),
        'probe_source_sha256':digest(PROBE),'identical_codec_classes':names,'cycles':results,
        'scope':'actual Fabric vanilla registry ItemStack/plan/envelope checks; NOT player inventory durability/trade/load acceptance'}
    (ROOT / 'result.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report), flush=True)

if __name__ == '__main__': main()
