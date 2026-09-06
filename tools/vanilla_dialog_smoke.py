#!/usr/bin/env python3
"""Loopback-only native-dialog protocol QA. Downloads/validates packs; does not render them."""
import argparse, functools, hashlib, http.server, io, json, os, re, secrets, shutil, socket, struct, subprocess, threading, time, uuid, zipfile
from pathlib import Path
import auth_encrypted_smoke as base
import package_resources

NAME = 'WlVanillaWireQA'
IDENTITY = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+NAME).encode()).digest(), version=3)
base.NAME, base.IDENTITY = NAME, IDENTITY

def action(nonce, password, registration, kind=None):
    values={'nonce':str(nonce),'password':password,'confirmation':password if registration else '', 'owner':''}
    def utf(s):
        b=s.encode('utf-8');return struct.pack('>H',len(b))+b
    nbt=b'\x0a'+b''.join(b'\x08'+utf(k)+utf(v) for k,v in values.items())+b'\x00'
    return base.text('warland:auth/'+(kind or ('register' if registration else 'login')))+b'\x01'+nbt

def probe(port, pin, password, mode, pack_url, pack_hash):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    import urllib.request
    w=base.Wire(port);phase='login';deadline=time.monotonic()+45;sent=False;nonce=None
    result={'mode':mode,'encrypted':False,'dialog':False,'play':False,'balance':False,'pack_downloaded':False,'no_mod_channels':True}
    try:
        w.send(0,base.varint(774)+base.text('127.0.0.1')+struct.pack('>H',port)+base.varint(2))
        w.send(0,base.text(NAME)+IDENTITY.bytes)
        while True:
            p,b=w.receive(deadline)
            if phase=='login':
                if p==1:
                    sid,o=base.read_blob(b,0);pub,o=base.read_blob(b,o);token,o=base.read_blob(b,o)
                    if b[o:]!=b'\x00' or hashlib.sha256(pub).hexdigest()!=pin:raise ValueError('Wrong native encrypted transport')
                    key=secrets.token_bytes(16);rsa=serialization.load_der_public_key(pub)
                    w.send(1,base.blob(rsa.encrypt(key,padding.PKCS1v15()))+base.blob(rsa.encrypt(token,padding.PKCS1v15())))
                    w.enable_encryption(key);result['encrypted']=True
                elif p==2:
                    if not result['encrypted'] or b[:16]!=IDENTITY.bytes:raise ValueError('Wrong identity')
                    phase='configuration';w.send(3)
                elif p==4:
                    query,_=base.read_vi(b);w.send(2,base.varint(query)+b'\x00')
                else:raise ValueError('Unexpected LOGIN packet '+str(p))
            elif phase=='configuration':
                if p==18:
                    if nonce is not None:
                        if mode=='wrong-password':result['denied']=True;return result
                        raise ValueError('Unexpected authentication denial')
                    matches=set(re.findall(rb'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}',b))
                    if len(matches)!=1 or b'warland:auth/login' not in b:raise ValueError('Expected native authentication dialog')
                    nonce=uuid.UUID(next(iter(matches)).decode());result['dialog']=True
                    if mode=='malformed-register':
                        w.send(8,action(uuid.uuid4(),password,True));w.send(8,action(nonce,password,True,'not-allowed'))
                        quiet=time.monotonic()+.8
                        while time.monotonic()<quiet:
                            try:q,_=w.receive(quiet)
                            except TimeoutError:break
                            if q in (2,3,17,18):raise ValueError('Invalid action advanced authentication')
                        result['invalid_actions_ignored']=True
                    w.send(8,action(nonce,password,mode in ('register','malformed-register'), 'cancel' if mode=='cancel' else None))
                elif p==17:
                    if mode in ('wrong-password','cancel'):raise ValueError('Rejected auth cleared barrier')
                    result['barrier_cleared']=True
                elif p==9:
                    if not result.get('barrier_cleared'):raise ValueError('Pack sent before authentication')
                    rid=b[:16];url,o=base.read_blob(b,16);digest,o=base.read_blob(b,o)
                    if url.decode()!=pack_url or b[o]!=1:raise ValueError('Unexpected pack destination or optional pack')
                    w.send(6,rid+base.varint(3))
                    with urllib.request.urlopen(pack_url,timeout=10) as r:data=r.read(1024*1024)
                    if hashlib.sha256(data).hexdigest()!=pack_hash or hashlib.sha1(data).hexdigest()!=digest.decode():raise ValueError('Pack checksum mismatch')
                    with zipfile.ZipFile(io.BytesIO(data)) as z:
                        if z.testzip() is not None:raise ValueError('Pack corrupt')
                    w.send(6,rid+base.varint(4));w.send(6,rid+base.varint(0));result['pack_downloaded']=True
                elif p==14:w.send(7,b'\x00')
                elif p==4:w.send(4,b)
                elif p==5:w.send(5,b)
                elif p==3:
                    if not result.get('barrier_cleared') or not result['pack_downloaded']:raise ValueError('Premature PLAY release')
                    w.send(3);phase='play'
                elif p==2:
                    if mode=='cancel':result['cancelled']=True;return result
                    raise ValueError('Configuration disconnect')
            else:
                if p==0x30:result['play']=True;w.send(0x2b)
                elif p==0x46:
                    teleport,_=base.read_vi(b);w.send(0,base.varint(teleport))
                elif p==0x0b:w.send(0x0a,struct.pack('>f',4.0))
                elif p==0x2b:w.send(0x1b,b)
                elif p==0x3b:w.send(0x2c,b)
                elif p in (0x20,0x74):raise ValueError('PLAY disconnect or reconfiguration')
                elif p==0x77:
                    if 'Добро пожаловать в WarLand!'.encode() in b and not sent:w.send(0x06,base.text('balance'));sent=True
                    if sent and 'Баланс'.encode() in b:result['balance']=True
                if result['play'] and result['balance']:return result
    finally:w.close()

class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self,*args):pass

def run(a):
    os.umask(0o077)
    root=Path('/opt/warland-build');source=root/'release-qa/final-runtime';out=a.out
    if os.geteuid()==0 or not a.synthetic_fixture or out.parent!=root or not out.name.startswith('vanilla-dialog-qa-') or out.exists():
        raise ValueError('Only a new synthetic unprivileged QA directory is permitted')
    if any(p.is_symlink() for p in source.rglob('*')) or (source/'warland/private-auth').exists():raise ValueError('Unsafe fixture')
    initial=base.database(source)
    if any(initial[k] for k in ('profiles','auth_accounts','accounts','ledger')):raise ValueError('Nonempty fixture')
    for port in (a.port,a.http_port):
        with socket.socket() as s:s.bind(('127.0.0.1',port))
    shutil.copytree(source,out,ignore=shutil.ignore_patterns('logs','*.log','*-result.json','usercache.json'))
    for old in (out/'mods').glob('warland-*.jar'):old.unlink()
    shutil.copy2(a.jar,out/'mods'/a.jar.name);shutil.copy2(a.polymer,out/'mods'/a.polymer.name)
    public=out/'qa-public';public.mkdir();metadata=package_resources.pack(Path(__file__).resolve().parents[1]/'src/main/resources/assets',public/'pack.zip')
    pack_url=f'http://127.0.0.1:{a.http_port}/pack.zip'
    httpd=http.server.ThreadingHTTPServer(('127.0.0.1',a.http_port),functools.partial(QuietHandler,directory=str(public)))
    thread=threading.Thread(target=httpd.serve_forever,daemon=True);thread.start()
    values={'server-ip':'127.0.0.1','server-port':str(a.port),'online-mode':'false','network-compression-threshold':'-1',
      'enable-rcon':'false','enable-query':'false','max-players':'2','view-distance':'3','simulation-distance':'3',
      'white-list':'false','enforce-whitelist':'false','enforce-secure-profile':'false','pause-when-empty-seconds':'-1',
      'resource-pack':pack_url,'resource-pack-sha1':metadata['sha1'],'require-resource-pack':'true'}
    props=out/'server.properties';lines=[s for s in props.read_text().splitlines() if s.partition('=')[0] not in values]
    props.write_text('\n'.join(lines+[k+'='+v for k,v in values.items()])+'\n')
    report={'success':False,'scope':'native unmodded wire protocol, pack download/hash/CRC; NOT graphical rendering or public/load acceptance',
      'jar_sha256':hashlib.sha256(a.jar.read_bytes()).hexdigest(),'pack':metadata,'cycles':[]}
    password=secrets.token_urlsafe(24);wrong=secrets.token_urlsafe(24);process=None;pin=None
    try:
        for cycle in (1,2):
            item={'cycle':cycle,'probes':[]};report['cycles'].append(item);log=out/f'boot-{cycle}.log'
            with log.open('x') as output:
                process=subprocess.Popen(['nice','-n','10','java','-Xms128M','-Xmx640M','-XX:ActiveProcessorCount=1',
                  '-Dwarland.encryptedOffline=true','-Dwarland.vanillaClient=true','-jar','fabric-server-launch.jar','nogui'],
                  cwd=out,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True,start_new_session=True)
                deadline=time.monotonic()+120
                while process.poll() is None and time.monotonic()<deadline:
                    content=log.read_text(errors='replace')
                    if 'WarLand ready:' in content and 'Done (' in content:break
                    time.sleep(.3)
                else:raise ValueError('Server did not become ready')
                current=re.search(r'encrypted-offline server fingerprint: ([0-9a-f]{64})',content).group(1)
                if pin is not None and current!=pin:raise ValueError('Server key changed')
                pin=current;item['stable_identity']=True
                if cycle==1:
                    item['probes'].append(probe(a.port,pin,password,'cancel',pack_url,metadata['sha256']));time.sleep(.5)
                    item['probes'].append(probe(a.port,pin,password,'malformed-register',pack_url,metadata['sha256']))
                else:
                    before=base.database(out);item['probes'].append(probe(a.port,pin,wrong,'wrong-password',pack_url,metadata['sha256']))
                    if before!=base.database(out):raise ValueError('Wrong password changed durable state')
                    time.sleep(2);item['probes'].append(probe(a.port,pin,password,'login',pack_url,metadata['sha256']))
                item['exit']=base.stop(process);process=None
            item['database']=base.database(out);d=item['database']
            if d['profiles']!=1 or d['auth_accounts']!=1 or d['starter_grants']!=1 or d['starter_total']!=1500 or d['quick_check']!='ok' or d['fk_errors']:
                raise ValueError('Durability invariant failed')
            if cycle==2 and item['database']!=report['cycles'][0]['database']:raise ValueError('Restart changed account or wallet')
            content=log.read_text(errors='replace')
            item['errors']=[s for s in content.splitlines() if re.search(r'\bERROR\b|Exception|InjectionError',s)][:10]
            if item['exit'] or item['errors']:raise ValueError('Server errors or nonzero shutdown')
            if password in content or wrong in content:raise ValueError('Secret canary found in log')
        report['success']=True
    except Exception as e:report['error_type']=type(e).__name__;report['error']=str(e)[:160]
    finally:
        if process is not None:report['cleanup_exit']=base.stop(process)
        httpd.shutdown();httpd.server_close();thread.join(timeout=2)
        (out/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
    return 0 if report['success'] else 1

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,required=True);p.add_argument('--polymer',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True);p.add_argument('--port',type=int,default=25580);p.add_argument('--http-port',type=int,default=18089)
    p.add_argument('--synthetic-fixture',action='store_true');raise SystemExit(run(p.parse_args()))
