from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from release_gate import check, GATES, sha256

class ReleaseGateTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.now=datetime(2026,9,6,5,tzinfo=timezone.utc)
        (self.root/'config/warland').mkdir(parents=True)
        (self.root/'server.properties').write_text('online-mode=true\nserver-ip=127.0.0.1\n')
        (self.root/'config/warland/core.json').write_text(json.dumps({'schemaVersion':1,'requireOnlineModeForPublic':True,'mobilizationHours':12}))
        self.jar=self.root/'warland.jar'
        with zipfile.ZipFile(self.jar,'w') as jar:jar.writestr('fabric.mod.json',json.dumps({'id':'warland','depends':{'minecraft':'1.21.11'}}))
        self.data={'schema':1,'artifact_sha256':sha256(self.jar),'recorded_at':self.now.isoformat(),
                   'gates':{key:{'passed':True,'evidence':'synthetic fixture only','verified_by':'unit test'} for key in GATES}}
        self.evidence=self.root/'evidence.json'
    def tearDown(self):self.temp.cleanup()
    def result(self):
        self.evidence.write_text(json.dumps(self.data))
        return check(self.root,self.jar,self.evidence,self.now)
    def test_complete_attestation_is_not_a_deployment(self):
        result=self.result();self.assertEqual('checklist_complete',result['status']);self.assertFalse(result['deploy_performed'])
    def test_every_missing_gate_blocks(self):
        for gate in GATES:
            saved=self.data['gates'].pop(gate)
            self.assertEqual('blocked',self.result()['status'])
            self.data['gates'][gate]=saved
    def test_truthy_strings_do_not_count_as_pass(self):
        self.data['gates']['load_30']['passed']='true';self.assertEqual('blocked',self.result()['status'])
    def test_another_artifact_invalidates_evidence(self):
        self.data['artifact_sha256']='0'*64;self.assertEqual('blocked',self.result()['status'])
    def test_public_offline_mode_blocked(self):
        (self.root/'server.properties').write_text('online-mode=false\n');self.assertEqual('blocked',self.result()['status'])
    def test_stale_future_and_naive_dates_blocked(self):
        for when in [self.now-timedelta(days=8),self.now+timedelta(seconds=1),self.now.replace(tzinfo=None)]:
            self.data['recorded_at']=when.isoformat();self.assertEqual('blocked',self.result()['status'])
    def test_missing_attribution_blocks(self):
        self.data['gates']['inventory_crash']['verified_by']=' ';self.assertEqual('blocked',self.result()['status'])
    def test_disabled_guard_blocks(self):
        (self.root/'config/warland/core.json').write_text(json.dumps({'schemaVersion':1,'requireOnlineModeForPublic':False,'mobilizationHours':12}))
        self.assertEqual('blocked',self.result()['status'])
    def test_duplicate_properties_fail_closed(self):
        (self.root/'server.properties').write_text('online-mode=true\nonline-mode=false\n');self.assertEqual('blocked',self.result()['status'])
    def test_noncanonical_escaped_properties_fail_closed(self):
        (self.root/'server.properties').write_text('online-mode=tr\\ue\n');self.assertEqual('blocked',self.result()['status'])
    def test_corrupt_jar_blocks(self):
        self.jar.write_text('broken');self.data['artifact_sha256']=sha256(self.jar);self.assertEqual('blocked',self.result()['status'])
    def test_duplicate_json_fields_fail_closed(self):
        self.evidence.write_text('{"schema":1,"schema":2}')
        self.assertEqual('blocked',check(self.root,self.jar,self.evidence,self.now)['status'])

if __name__ == '__main__':unittest.main()
