#!/usr/bin/env python3
"""Install a pinned WarLand server on Linux x86-64; never overwrite an existing server."""
import argparse, fcntl, hashlib, json, os, platform, re, shlex, shutil, subprocess, tarfile, tempfile, time
import urllib.error, urllib.parse, urllib.request
from pathlib import Path

MAX_DOWNLOAD = 128 * 1024 * 1024
VERSION = '0.1.0-alpha.3'
PATHS = {'fabric-server-launch.jar', f'mods/warland-{VERSION}.jar',
    'mods/fabric-api-0.141.6+1.21.11.jar', 'mods/polymer-bundled-0.15.2+1.21.11.jar', 'resource-pack.zip'}


def sha256(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()


def https(url):
    if not isinstance(url,str):raise ValueError('Invalid download URL')
    u=urllib.parse.urlsplit(url)
    if u.scheme!='https' or not u.hostname or u.username or u.password or u.fragment:
        raise ValueError('Downloads require credential-free HTTPS')
    return url


def child(root, name):
    p=Path(name)
    if p.is_absolute() or not p.parts or any(s in ('.','..') for s in p.parts) or '\\' in name:
        raise ValueError('Unsafe installation path')
    target=root
    for part in p.parts:
        target=target/part
        if target.is_symlink():raise ValueError('Installation paths must not be symlinks')
    return target


def validate(manifest):
    if manifest.get('schema')!=1 or manifest.get('version')!=VERSION:raise ValueError('Unsupported manifest')
    files=manifest.get('files',[])
    if not isinstance(files,list) or len(files)!=len(PATHS) or not all(isinstance(f,dict) for f in files) or {f.get('path') for f in files}!=PATHS:
        raise ValueError('Manifest must contain exactly the expected server artifacts')
    for f in files+[manifest.get('java',{})]:
        https(f.get('url'))
        if not isinstance(f.get('sha256'),str) or not re.fullmatch('[0-9a-f]{64}',f['sha256']):raise ValueError('Invalid SHA-256 pin')
    https(manifest.get('resource_pack_url'))
    if not isinstance(manifest.get('resource_pack_sha1'),str) or not re.fullmatch('[0-9a-f]{40}',manifest['resource_pack_sha1']):raise ValueError('Invalid resource-pack pin')
    pack=next(f for f in files if f['path']=='resource-pack.zip')
    if pack['url']!=manifest['resource_pack_url']:raise ValueError('Pack URL does not match the downloaded artifact')
    return manifest


def download(url, destination, expected):
    https(url)
    if destination.is_symlink():raise ValueError('Refusing a symlink destination')
    if destination.exists():
        if not destination.is_file() or sha256(destination)!=expected:
            raise ValueError('Existing managed artifact differs; preserved without overwrite')
        return False
    destination.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    for attempt in range(3):
        fd, name=tempfile.mkstemp(prefix='.download-',dir=destination.parent)
        try:
            with os.fdopen(fd,'wb') as out:
                req=urllib.request.Request(url,headers={'User-Agent':'WarLand-bootstrap/alpha.3'})
                with urllib.request.urlopen(req,timeout=40) as source:
                    https(source.geturl())
                    total=0;digest=hashlib.sha256()
                    while block:=source.read(1024*1024):
                        total+=len(block)
                        if total>MAX_DOWNLOAD:raise ValueError('Download exceeds size limit')
                        out.write(block);digest.update(block)
                out.flush();os.fsync(out.fileno())
            if digest.hexdigest()!=expected:raise ValueError('Download checksum mismatch; nothing installed')
            os.link(name,destination)
            return True
        except urllib.error.HTTPError as error:
            if attempt==2 or error.code not in (429,503):raise
            retry=error.headers.get('Retry-After','') if error.headers else ''
            delay=max(2*(attempt+1),int(retry)) if retry.isdigit() else 2*(attempt+1)
            if delay>60:raise
            time.sleep(delay)
        except (urllib.error.URLError,TimeoutError):
            if attempt==2:raise
            time.sleep(2*(attempt+1))
        finally:Path(name).unlink(missing_ok=True)


def java21(path):
    try:
        r=subprocess.run([str(path),'-version'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=10)
        return r.returncode==0 and re.search(r'version "21(?:\.|\")',r.stdout) is not None
    except (OSError,subprocess.SubprocessError):return False


def java_runtime(root, pin):
    current=shutil.which('java')
    if current and java21(current):return Path(current)
    installed=child(root,'.java/bin/java')
    if installed.exists():
        if not java21(installed):raise ValueError('Existing private runtime is not Java 21')
        return installed
    archive=child(root,'.downloads/java21.tar.gz');download(pin['url'],archive,pin['sha256'])
    stage=Path(tempfile.mkdtemp(prefix='.jre-stage-',dir=root))
    try:
        with tarfile.open(archive,'r:gz') as t:
            members=t.getmembers()
            if len(members)>5000 or sum(m.size for m in members)>300*1024*1024:raise ValueError('Runtime archive exceeds bounds')
            t.extractall(stage,filter='data')
        roots=list(stage.iterdir())
        if len(roots)!=1 or roots[0].is_symlink() or not (roots[0]/'bin/java').is_file():raise ValueError('Invalid runtime layout')
        target=child(root,'.java')
        if target.exists():raise ValueError('Runtime target already exists')
        roots[0].rename(target)
    finally:shutil.rmtree(stage)
    if not java21(installed):raise ValueError('Downloaded runtime failed Java 21 validation')
    return installed


def write_new(path, text, mode=0o600):
    if path.is_symlink():raise ValueError('Refusing configuration symlink')
    if path.exists():
        if not path.is_file():raise ValueError('Configuration target is not a file')
        return
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,mode)
    with os.fdopen(fd,'w',encoding='utf-8') as out:out.write(text);out.flush();os.fsync(out.fileno())


def install(manifest, directory, accept_eula=False):
    validate(manifest)
    if not accept_eula:raise ValueError('Read https://aka.ms/MinecraftEULA and explicitly pass --accept-eula')
    if platform.system()!='Linux' or platform.machine() not in ('x86_64','amd64'):
        raise ValueError('This server installer supports Linux x86-64 only; players use ordinary Minecraft')
    directory=Path(directory).absolute()
    if directory.is_symlink():raise ValueError('Server directory must not be a symlink')
    directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    root=directory.resolve();marker=child(root,'.warland-install.json')
    key=hashlib.sha256(json.dumps(manifest,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    if not marker.exists() and any(root.iterdir()):raise ValueError('Refusing an existing nonempty server directory')
    if marker.exists() and json.loads(marker.read_text()).get('manifest_sha256')!=key:
        raise ValueError('Different installation already exists; use the backup/update runbook')
    lock=child(root,'.warland-install.lock')
    with lock.open('a') as held:
        fcntl.flock(held,fcntl.LOCK_EX|fcntl.LOCK_NB)
        write_new(marker,json.dumps({'version':VERSION,'manifest_sha256':key})+'\n')
        for f in manifest['files']:download(f['url'],child(root,f['path']),f['sha256'])
        pack=child(root,'resource-pack.zip')
        if hashlib.sha1(pack.read_bytes()).hexdigest()!=manifest['resource_pack_sha1']:raise ValueError('Pack SHA-1 mismatch')
        java=java_runtime(root,manifest['java'])
        write_new(child(root,'eula.txt'),'eula=true\n')
        props={'server-port':'25565','online-mode':'false','white-list':'false','enforce-whitelist':'false',
            'enforce-secure-profile':'false','enable-rcon':'false','enable-query':'false','max-players':'10',
            'view-distance':'4','simulation-distance':'4','pause-when-empty-seconds':'-1',
            'motd':'WarLand alpha.3 | Minecraft 1.21.11 | Vanilla client',
            'resource-pack':manifest['resource_pack_url'],'resource-pack-sha1':manifest['resource_pack_sha1'],
            'require-resource-pack':'true','resource-pack-prompt':json.dumps({'text':'WarLand: download server resources to play.'})}
        write_new(child(root,'server.properties'),'\n'.join(k+'='+v for k,v in props.items())+'\n')
        start='#!/bin/sh\nset -eu\ncd -- "$(dirname -- "$0")"\nexec '+shlex.quote(str(java))+' -Xms256M -Xmx1400M -XX:ActiveProcessorCount=2 -XX:+UseG1GC -Dwarland.encryptedOffline=true -Dwarland.vanillaClient=true -jar fabric-server-launch.jar nogui\n'
        write_new(child(root,'start.sh'),start,0o700)
    return root


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest',type=Path,default=Path(__file__).with_name('server-lock.json'))
    parser.add_argument('--directory',type=Path,default=Path('WarLand-server'))
    parser.add_argument('--accept-eula',action='store_true');args=parser.parse_args();os.umask(0o077)
    try:
        root=install(json.loads(args.manifest.read_text()),args.directory,args.accept_eula)
        print('WarLand установлен. Запуск: '+str(root/'start.sh'))
        print('Fabric при первом запуске автоматически загрузит Minecraft и свои библиотеки. Существующие данные не перезаписывались.')
    except Exception as exc:raise SystemExit('Установка остановлена: '+str(exc))
