import hashlib, importlib.util, json, os, sys, tempfile, unittest
from pathlib import Path
from unittest import mock
spec=importlib.util.spec_from_file_location('dokploy',Path(__file__).parents[1]/'dokploy.py'); d=importlib.util.module_from_spec(spec); spec.loader.exec_module(d)
class DeliveryTest(unittest.TestCase):
  def release(self, **changes):
    value={'schema':1,'sourceCommit':'a'*40,'serverImage':'ghcr.io/x/server@sha256:'+'1'*64,'siteImage':'ghcr.io/x/site@sha256:'+'2'*64,'backupImage':'ghcr.io/x/backup@sha256:'+'3'*64,'postgresImage':'timescale/timescaledb@sha256:'+'4'*64,'manifestSha256':'5'*64,'migrationId':'V1__initial'}; value.update(changes)
    f=tempfile.NamedTemporaryFile(mode='w',delete=False); json.dump(value,f); f.close(); return f.name,value
  def test_release_is_canonical_and_digest_only(self):
    path,value=self.release(); loaded=d.load_release(path); self.assertEqual(d.release_id(loaded),'sha256:'+hashlib.sha256(d.canonical(value)).hexdigest())
  def test_moving_tag_rejected(self):
    path,_=self.release(serverImage='ghcr.io/x/server:latest'); self.assertRaises(ValueError,d.load_release,path)
  def test_review_name_is_deterministic(self): self.assertEqual(d.review_name('BuildHound/BuildHound',42),'review-buildhound-buildhound-42')
  def test_review_retry_updates_owned_compose(self):
    calls=[]
    class Fake:
      def __init__(self,*_): pass
      def request(self,method,path,body=None):
        calls.append((method,path,body))
        if path.startswith('/api/environment.one'): return {'compose':[{'name':'review-buildhound-buildhound-42','composeId':'c1','description':'{"repository":"BuildHound/BuildHound","pr":42,"sha":"'+'a'*40+'"}'}]}
        return {}
    argv=['dokploy.py','deploy-review','--base-repo','BuildHound/BuildHound','--head-repo','BuildHound/BuildHound','--sha','a'*40,'--state','open','--environment-id','e1','--dns-suffix','reviews.test','--pr','42','--label-present','--server-image','ghcr.io/x/server@sha256:'+'1'*64,'--site-image','ghcr.io/x/site@sha256:'+'2'*64]
    env={'DOKPLOY_URL':'https://dokploy.test','DOKPLOY_TOKEN':'token','BUILDHOUND_REVIEW_DB_PASSWORD':'db','BUILDHOUND_REVIEW_TOKEN':'review'}
    with mock.patch.object(d,'Client',Fake), mock.patch.object(sys,'argv',argv), mock.patch.dict(os.environ,env,clear=False): self.assertEqual(d.main(),0)
    self.assertIn('/api/compose.update',[x[1] for x in calls]); self.assertNotIn('/api/compose.create',[x[1] for x in calls])
  def test_release_updates_exact_resources_before_deploy(self):
    checksum=hashlib.sha256((Path(__file__).parents[1]/'stack.yaml').read_bytes()).hexdigest()
    path,_=self.release(manifestSha256=checksum)
    calls=[]
    class Fake:
      deployed=False
      def __init__(self,*_): pass
      def request(self,method,path,body=None):
        calls.append((method,path,body))
        if path in ('/api/compose.deploy','/api/application.deploy'): Fake.deployed=True; return {}
        if path.startswith('/api/deployment.') and Fake.deployed: return [{'deploymentId':path,'title':d.release_id(d.load_release(path_file)),'status':'done'}]
        if path.startswith('/api/deployment.'): return []
        if path.startswith('/api/application.one'): return {'dockerImage':'ghcr.io/x/site@sha256:'+'2'*64}
        return {}
    argv=['dokploy.py','deploy-release',path,'--compose-id','c1','--site-application-id','a1']
    path_file=path
    with mock.patch.object(d,'Client',Fake), mock.patch.object(sys,'argv',argv), mock.patch.dict(os.environ,{'DOKPLOY_URL':'https://dokploy.test','DOKPLOY_TOKEN':'token'},clear=False): self.assertEqual(d.main(),0)
    endpoints=[x[1] for x in calls]
    self.assertLess(endpoints.index('/api/compose.update'),endpoints.index('/api/compose.deploy'))
    self.assertIn('/api/application.deploy',endpoints)
if __name__=='__main__': unittest.main()
