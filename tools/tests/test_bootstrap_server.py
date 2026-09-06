import copy, hashlib, io, json, tempfile, unittest, urllib.error
from pathlib import Path
from unittest.mock import patch
import bootstrap_server as target
DATA=b'synthetic artifact';DIGEST=hashlib.sha256(DATA).hexdigest()
class Response(io.BytesIO):
 def geturl(self):return 'https://example.com/artifact'
def manifest():
 return {'schema':1,'version':target.VERSION,'files':[{'path':p,'url':'https://example.com/'+p,'sha256':DIGEST} for p in sorted(target.PATHS)],'java':{'url':'https://example.com/java.tar.gz','sha256':DIGEST},'resource_pack_url':'https://example.com/resource-pack.zip','resource_pack_sha1':hashlib.sha1(DATA).hexdigest()}
class BootstrapTests(unittest.TestCase):
 def setUp(self):
  t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name)
 def fetch(self,path=None,pin=DIGEST):return target.download('https://example.com/artifact',path or self.root/'file.jar',pin)
 def test_verified_download(self):
  with patch.object(target.urllib.request,'urlopen',return_value=Response(DATA)):
   self.assertTrue(self.fetch());self.assertEqual((self.root/'file.jar').read_bytes(),DATA)
  self.assertEqual(list(self.root.glob('.download-*')),[])
 def test_bad_digest_never_installed(self):
  with patch.object(target.urllib.request,'urlopen',return_value=Response(DATA)):self.assertRaises(ValueError,self.fetch,pin='0'*64)
  self.assertEqual(list(self.root.iterdir()),[])
 def test_oversize_never_installed(self):
  with patch.object(target.urllib.request,'urlopen',return_value=Response(DATA)),patch.object(target,'MAX_DOWNLOAD',2):self.assertRaises(ValueError,self.fetch)
  self.assertEqual(list(self.root.iterdir()),[])
 def test_existing_good_file_skips_network(self):
  (self.root/'file.jar').write_bytes(DATA)
  with patch.object(target.urllib.request,'urlopen') as opened:self.assertFalse(self.fetch());opened.assert_not_called()
 def test_existing_bad_file_preserved(self):
  p=self.root/'file.jar';p.write_bytes(b'keep');self.assertRaises(ValueError,self.fetch);self.assertEqual(p.read_bytes(),b'keep')
 def test_destination_symlink_rejected(self):
  p=self.root/'link';p.symlink_to(self.root/'outside');self.assertRaises(ValueError,self.fetch,p)
 def test_https_required(self):
  for u in ('http://example.com/x','file:///etc/passwd','https://token:secret@example.com/x','https://example.com/x#fragment'):self.assertRaises(ValueError,target.https,u)
 def test_redirect_downgrade_rejected(self):
  r=Response(DATA);r.geturl=lambda:'http://example.com/x'
  with patch.object(target.urllib.request,'urlopen',return_value=r):self.assertRaises(ValueError,self.fetch)
 def test_transient_error_retries_bounded(self):
  with patch.object(target.urllib.request,'urlopen',side_effect=[urllib.error.URLError('temporary'),Response(DATA)]) as opened,patch.object(target.time,'sleep'):
   self.assertTrue(self.fetch());self.assertEqual(opened.call_count,2)
 def test_persistent_error_stops_after_three(self):
  with patch.object(target.urllib.request,'urlopen',side_effect=urllib.error.URLError('temporary')) as opened,patch.object(target.time,'sleep'):
   self.assertRaises(urllib.error.URLError,self.fetch);self.assertEqual(opened.call_count,3)
  self.assertEqual(list(self.root.iterdir()),[])
 def test_rate_limit_retry_after_respected(self):
  error=urllib.error.HTTPError('https://example.com',429,'wait',{'Retry-After':'8'},None)
  with patch.object(target.urllib.request,'urlopen',side_effect=[error,Response(DATA)]),patch.object(target.time,'sleep') as sleep:
   self.assertTrue(self.fetch());sleep.assert_called_once_with(8)
 def test_long_rate_limit_not_evaded(self):
  error=urllib.error.HTTPError('https://example.com',429,'wait',{'Retry-After':'600'},None)
  with patch.object(target.urllib.request,'urlopen',side_effect=error) as opened,patch.object(target.time,'sleep') as sleep:
   self.assertRaises(urllib.error.HTTPError,self.fetch);self.assertEqual(opened.call_count,1);sleep.assert_not_called()
 def test_manifest_requires_exact_artifacts(self):
  m=manifest();m['files'].pop();self.assertRaises(ValueError,target.validate,m)
 def test_duplicate_path_rejected(self):
  m=manifest();m['files'][0]=m['files'][1];self.assertRaises(ValueError,target.validate,m)
 def test_bad_hash_rejected(self):
  m=manifest();m['files'][0]['sha256']='bad';self.assertRaises(ValueError,target.validate,m)
 def test_pack_url_must_match(self):
  m=manifest();m['resource_pack_url']='https://other.example/x';self.assertRaises(ValueError,target.validate,m)
 def test_paths_cannot_escape(self):
  for p in ('../outside','/tmp/outside','mods/../../outside','mods\\outside'):self.assertRaises(ValueError,target.child,self.root,p)
 def test_symlink_directory_cannot_escape(self):
  (self.root/'mods').symlink_to(self.root/'other');self.assertRaises(ValueError,target.child,self.root,'mods/mod.jar')
 def test_eula_before_files_or_network(self):
  with patch.object(target,'download') as download:self.assertRaises(ValueError,target.install,manifest(),self.root/'new',False);download.assert_not_called()
  self.assertFalse((self.root/'new').exists())
 def test_existing_server_preserved(self):
  (self.root/'world.dat').write_bytes(b'keep')
  with patch.object(target,'download') as download:self.assertRaises(ValueError,target.install,manifest(),self.root,True);download.assert_not_called()
  self.assertEqual((self.root/'world.dat').read_bytes(),b'keep')
 def test_install_resume_preserves_configuration(self):
  def download(url,dest,expected):
   dest.parent.mkdir(parents=True,exist_ok=True)
   if not dest.exists():dest.write_bytes(DATA)
  with patch.object(target,'download',side_effect=download),patch.object(target,'java_runtime',return_value=Path('/usr/bin/java')):
   target.install(manifest(),self.root,True);props=self.root/'server.properties'
   self.assertIn('require-resource-pack=true',props.read_text());self.assertIn('warland.vanillaClient=true',(self.root/'start.sh').read_text());self.assertEqual((self.root/'start.sh').stat().st_mode&0o777,0o700)
   props.write_text('operator settings\n');target.install(manifest(),self.root,True);self.assertEqual(props.read_text(),'operator settings\n')
 def test_different_manifest_cannot_upgrade_existing(self):
  m=manifest();key=hashlib.sha256(json.dumps(m,sort_keys=True,separators=(',',':')).encode()).hexdigest();(self.root/'.warland-install.json').write_text(json.dumps({'manifest_sha256':key}))
  other=copy.deepcopy(m);other['files'][0]['sha256']='0'*64
  with patch.object(target,'download') as download:self.assertRaises(ValueError,target.install,other,self.root,True);download.assert_not_called()
 def test_write_new_preserves_existing(self):
  p=self.root/'config';p.write_text('keep');target.write_new(p,'replace');self.assertEqual(p.read_text(),'keep')
 def test_write_new_rejects_directory(self):self.assertRaises(ValueError,target.write_new,self.root,'no')
if __name__=='__main__':unittest.main()
