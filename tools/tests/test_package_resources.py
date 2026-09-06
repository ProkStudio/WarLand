import hashlib, json, tempfile, unittest, zipfile
from pathlib import Path
from unittest.mock import patch
import package_resources as target

class PackTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name); self.assets=self.root/'assets'; self.assets.mkdir()
        for name in ('ak74','magazine_545'):
            path=self.assets/'warland/items'/f'{name}.json';path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(json.dumps({'model':{'type':'minecraft:model','model':'minecraft:item/paper'}}))
        self.out=self.root/'pack.zip'
    def test_deterministic_bytes(self):
        a=target.pack(self.assets,self.out); b=target.pack(self.assets,self.root/'second.zip')
        self.assertEqual(a['sha256'],b['sha256']);self.assertEqual(a['sha1'],hashlib.sha1(self.out.read_bytes()).hexdigest())
        with zipfile.ZipFile(self.out) as z:
            self.assertEqual(len(z.namelist()),3); self.assertIsNone(z.testzip())
            self.assertEqual(json.loads(z.read('pack.mcmeta'))['pack']['min_format'],[75,0])
            self.assertTrue(all(i.compress_type==zipfile.ZIP_STORED for i in z.infolist()))
    def test_existing_output_preserved(self):
        self.out.write_bytes(b'keep');self.assertRaises(ValueError,target.pack,self.assets,self.out);self.assertEqual(self.out.read_bytes(),b'keep')
    def test_missing_item_refused(self):
        (self.assets/'warland/items/ak74.json').unlink();self.assertRaises(ValueError,target.pack,self.assets,self.out)
    def test_bad_json_refused(self):
        (self.assets/'warland/items/ak74.json').write_text('{');self.assertRaises(ValueError,target.pack,self.assets,self.out);self.assertFalse(self.out.exists())
    def test_unexpected_secret_file_refused(self):
        (self.assets/'password.txt').write_text('synthetic');self.assertRaises(ValueError,target.pack,self.assets,self.out)
    def test_hidden_file_refused(self):
        (self.assets/'.hidden.json').write_text('{}');self.assertRaises(ValueError,target.pack,self.assets,self.out)
    def test_symlink_asset_refused(self):
        (self.assets/'linked.json').symlink_to(self.assets/'warland/items/ak74.json');self.assertRaises(ValueError,target.pack,self.assets,self.out)
    def test_symlink_root_refused(self):
        p=self.root/'link';p.symlink_to(self.assets,target_is_directory=True);self.assertRaises(ValueError,target.pack,p,self.out)
    def test_symlink_output_refused(self):
        self.out.symlink_to(self.root/'missing');self.assertRaises(ValueError,target.pack,self.assets,self.out)
    def test_output_inside_assets_refused(self):
        self.assertRaises(ValueError,target.pack,self.assets,self.assets/'pack.zip')
    def test_asset_budget_enforced(self):
        with patch.object(target,'MAX_ASSET_BYTES',1):self.assertRaises(ValueError,target.pack,self.assets,self.out)
    def test_total_budget_enforced(self):
        with patch.object(target,'MAX_PACK_BYTES',1):self.assertRaises(ValueError,target.pack,self.assets,self.out)
    def test_empty_root_refused(self):
        d=self.root/'empty';d.mkdir();self.assertRaises(ValueError,target.pack,d,self.out)
    def test_missing_root_refused(self):
        self.assertRaises(ValueError,target.pack,self.root/'none',self.out)
    def test_no_paths_or_files_outside_assets_in_archive(self):
        (self.root/'private.json').write_text('secret');target.pack(self.assets,self.out)
        with zipfile.ZipFile(self.out) as z:self.assertTrue(all(p=='pack.mcmeta' or p.startswith('assets/warland/') for p in z.namelist()))
    def test_mtime_changes_do_not_change_pack(self):
        target.pack(self.assets,self.out)
        import os
        for p in self.assets.rglob('*.json'):os.utime(p,(1234567890,1234567890))
        target.pack(self.assets,self.root/'second.zip');self.assertEqual(self.out.read_bytes(),(self.root/'second.zip').read_bytes())

if __name__=='__main__':unittest.main()
