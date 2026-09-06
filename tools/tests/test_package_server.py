import hashlib,json,tempfile,unittest,zipfile
from pathlib import Path
import package_server as target
from bootstrap_server import PATHS,validate
class BundleTests(unittest.TestCase):
 def setUp(self):
  t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name);self.out=self.root/'dist';self.jar=self.root/'candidate.jar'
  self.meta={'id':'warland','version':target.VERSION,'depends':{'polymer-core':'>=0.15.2+1.21.11'}};self.makejar()
  (self.root/'tools').mkdir();(self.root/'tools/bootstrap_server.py').write_text('# synthetic installer fixture\n')
  (self.root/'release').mkdir();self.pinfile=self.root/'release/server-dependencies.json'
  self.pins={'files':[{'path':p,'url':'https://example.com/'+p,'sha256':'0'*64} for p in sorted(PATHS) if not p.startswith('mods/warland-') and p!='resource-pack.zip'],'java':{'url':'https://example.com/java.tar.gz','sha256':'1'*64}}
  self.pinfile.write_text(json.dumps(self.pins))
  for n in ['ak74','magazine_545']:
   p=self.root/'src/main/resources/assets/warland/items'/f'{n}.json';p.parent.mkdir(parents=True,exist_ok=True);p.write_text('{"model":{"type":"minecraft:model","model":"minecraft:item/paper"}}')
 def makejar(self,classes=True):
  with zipfile.ZipFile(self.jar,'w') as z:
   z.writestr('fabric.mod.json',json.dumps(self.meta))
   if classes:z.writestr('ru/warland/auth/NativeAuthDialog.class',b'\xca\xfe\xba\xbe')
 def runbundle(self):return target.bundle(self.root,self.jar,self.out)
 def test_deterministic_release_assets(self):
  m=self.runbundle();validate(m);other=self.root/'other';target.bundle(self.root,self.jar,other)
  self.assertEqual({p.name:p.read_bytes() for p in self.out.iterdir()},{p.name:p.read_bytes() for p in other.iterdir()})
 def test_manifest_binds_release_artifacts(self):
  m=self.runbundle();self.assertEqual({f['path'] for f in m['files']},PATHS)
  for f in m['files']:
   if f['path'].startswith('mods/warland-') or f['path']=='resource-pack.zip':
    p=self.out/Path(f['path']).name;self.assertEqual(f['sha256'],hashlib.sha256(p.read_bytes()).hexdigest());self.assertTrue(f['url'].startswith('https://github.com/ProkStudio/WarLand/releases/download/v'+target.VERSION+'/'))
  self.assertEqual(m['resource_pack_sha1'],hashlib.sha1((self.out/'resource-pack.zip').read_bytes()).hexdigest())
 def test_bundle_contains_only_installer_manifest_instructions(self):
  self.runbundle()
  with zipfile.ZipFile(self.out/f'warland-server-{target.VERSION}.zip') as z:
   self.assertEqual(set(z.namelist()),{'bootstrap_server.py','server-lock.json','START_HERE_RU.txt'});self.assertIsNone(z.testzip());self.assertEqual(z.read('server-lock.json'),(self.out/'server-lock.json').read_bytes());self.assertIn('Fabric, клиентский мод и особый лаунчер не нужны',z.read('START_HERE_RU.txt').decode())
 def test_checksums_cover_all_other_assets(self):
  self.runbundle();lines=(self.out/'SHA256SUMS').read_text().splitlines();self.assertEqual(len(lines),5)
  for l in lines:
   digest,name=l.split('  ');self.assertEqual(digest,hashlib.sha256((self.out/name).read_bytes()).hexdigest())
 def test_old_mod_version_rejected(self):self.meta['version']='0.1.0-alpha.2';self.makejar();self.assertRaises(ValueError,self.runbundle)
 def test_wrong_mod_rejected(self):self.meta['id']='other';self.makejar();self.assertRaises(ValueError,self.runbundle)
 def test_source_jar_rejected(self):self.makejar(False);self.assertRaises(ValueError,self.runbundle)
 def test_missing_polymer_rejected(self):self.meta['depends']={};self.makejar();self.assertRaises(ValueError,self.runbundle)
 def test_existing_output_preserved(self):
  self.out.mkdir();(self.out/'keep').write_text('data');self.assertRaises(ValueError,self.runbundle);self.assertEqual((self.out/'keep').read_text(),'data')
 def test_existing_empty_output_preserved(self):self.out.mkdir();self.assertRaises(ValueError,self.runbundle);self.assertTrue(self.out.is_dir())
 def test_source_symlink_rejected(self):
  original=self.root/'original.jar';self.jar.rename(original);self.jar.symlink_to(original);self.assertRaises(ValueError,self.runbundle)
 def test_output_symlink_rejected(self):self.out.symlink_to(self.root/'other');self.assertRaises(ValueError,self.runbundle)
 def test_unsafe_dependency_rejected_and_stage_cleaned(self):
  self.pins['files'][0]['path']='../private';self.pinfile.write_text(json.dumps(self.pins));self.assertRaises(ValueError,self.runbundle);self.assertFalse(self.out.exists());self.assertFalse(list(self.root.glob('.warland-release-*')))
 def test_http_dependency_rejected(self):
  self.pins['files'][0]['url']='http://example.com/file';self.pinfile.write_text(json.dumps(self.pins));self.assertRaises(ValueError,self.runbundle)
if __name__=='__main__':unittest.main()
