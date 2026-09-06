#!/usr/bin/env python3
"""Build a credential-free client overlay from exact release artifacts."""
import argparse,hashlib,json,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--fabric-api',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[1]; manifest=json.loads((root/'release/alpha-client.json').read_text())
api_hash=hashlib.sha256(a.fabric_api.read_bytes()).hexdigest()
if api_hash!=manifest['fabric_api_sha256']:raise SystemExit('Fabric API checksum mismatch')
pin=manifest['server_fingerprint'];assert len(pin)==64 and all(c in '0123456789abcdef' for c in pin)
with zipfile.ZipFile(a.jar) as z:
 metadata=json.loads(z.read('fabric.mod.json'))
 if metadata['version']!=manifest['version']:raise SystemExit('WarLand version mismatch')
files={f'mods/{a.jar.name}':a.jar.read_bytes(),f'mods/{a.fabric_api.name}':a.fabric_api.read_bytes(),'config/warland/server-fingerprint.txt':(pin+'\n').encode(),'START_HERE_RU.md':(root/'docs/ALPHA_JOIN.md').read_bytes()}
checks=''.join(hashlib.sha256(v).hexdigest()+'  '+k+'\n' for k,v in sorted(files.items()))
files['SHA256SUMS.txt']=checks.encode();files['release.json']=(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode()
a.out.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(a.out,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for name,data in sorted(files.items()):
  info=zipfile.ZipInfo(name,(2026,9,6,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o644<<16;z.writestr(info,data)
print(hashlib.sha256(a.out.read_bytes()).hexdigest(),a.out.name)
