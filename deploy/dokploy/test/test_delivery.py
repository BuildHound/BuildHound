import hashlib, importlib.util, json, os, subprocess, sys, tempfile, unittest
from pathlib import Path
from unittest import mock
spec=importlib.util.spec_from_file_location('dokploy',Path(__file__).parents[1]/'dokploy.py'); d=importlib.util.module_from_spec(spec); spec.loader.exec_module(d)
class DeliveryTest(unittest.TestCase):
  def migration(self, migration_id, checksum):
    return {'id':migration_id,'sha256':checksum*64}
  def release(self, history=None, **changes):
    history=history or [self.migration('V1__initial','7')]
    value={'schema':2,'sourceCommit':'a'*40,'serverImage':'ghcr.io/x/server@sha256:'+'1'*64,'siteImage':'ghcr.io/x/site@sha256:'+'2'*64,'backupImage':'ghcr.io/x/backup@sha256:'+'3'*64,'postgresImage':'ghcr.io/x/db@sha256:'+'4'*64,'manifestSha256':'5'*64,'volumeGuardSha256':'6'*64,'migrationId':history[-1]['id'],'migrationHistory':history,'migrationHistorySha256':d.migration_history_sha256(history)}; value.update(changes)
    f=tempfile.NamedTemporaryFile(mode='w',delete=False); json.dump(value,f); f.close(); return f.name,value
  def test_release_is_canonical_and_digest_only(self):
    path,value=self.release(); loaded=d.load_release(path); self.assertEqual(d.release_id(loaded),'sha256:'+hashlib.sha256(d.canonical(value)).hexdigest())
  def test_moving_tag_rejected(self):
    path,_=self.release(serverImage='ghcr.io/x/server:latest'); self.assertRaises(ValueError,d.load_release,path)
  def test_rollback_requires_explicit_migration_compatibility(self):
    current_history=[self.migration('V14__older','a'),self.migration('V15__current','b')]
    current=('sha256:'+'9'*64,'V15__current',d.migration_history_sha256(current_history))
    _,candidate=self.release(history=current_history[:1])
    with self.assertRaises(ValueError): d.require_migration_compatibility(current,candidate,False)
    d.require_migration_compatibility(current,candidate,True)
  def test_same_version_migration_rewrite_is_always_rejected(self):
    current_history=[self.migration('V14__older','a'),self.migration('V15__current','b')]
    candidate_history=[self.migration('V14__older','a'),self.migration('V15__current','c')]
    current=('sha256:'+'9'*64,'V15__current',d.migration_history_sha256(current_history))
    _,candidate=self.release(history=candidate_history)
    with self.assertRaises(ValueError): d.require_migration_compatibility(current,candidate,True)
  def test_forward_migration_requires_unchanged_history_prefix(self):
    current_history=[self.migration('V14__older','a'),self.migration('V15__current','b')]
    current=('sha256:'+'9'*64,'V15__current',d.migration_history_sha256(current_history))
    _,candidate=self.release(history=current_history+[self.migration('V16__next','c')])
    d.require_migration_compatibility(current,candidate,False)
  def test_forward_migration_rejects_rewritten_older_file(self):
    current_history=[self.migration('V14__older','a'),self.migration('V15__current','b')]
    rewritten=[self.migration('V14__older','f'),self.migration('V15__current','b'),self.migration('V16__next','c')]
    current=('sha256:'+'9'*64,'V15__current',d.migration_history_sha256(current_history))
    _,candidate=self.release(history=rewritten)
    with self.assertRaises(ValueError): d.require_migration_compatibility(current,candidate,True)
  def test_release_rejects_migration_history_checksum_mismatch(self):
    path,_=self.release(migrationHistorySha256='0'*64)
    with self.assertRaises(ValueError): d.load_release(path)
  def test_current_release_parses_migration_bound_title(self):
    rid='sha256:'+'9'*64
    history_sha='8'*64
    self.assertEqual(d.current_release([{'status':'done','title':rid+'|V15__current|'+history_sha,'createdAt':'2026-07-13T12:00:00Z'}]),(rid,'V15__current',history_sha))
  def test_current_release_does_not_ignore_newer_unidentified_success(self):
    rid='sha256:'+'9'*64
    items=[
      {'status':'done','title':rid+'|V15__current|'+'8'*64,'createdAt':'2026-07-13T11:00:00Z'},
      {'status':'success','title':'manual deployment','createdAt':'2026-07-13T12:00:00Z'},
    ]
    self.assertIsNone(d.current_release(items))
  def test_current_release_rejects_ambiguous_latest_success(self):
    items=[
      {'status':'done','title':'first','createdAt':'2026-07-13T12:00:00Z'},
      {'status':'success','title':'second','createdAt':'2026-07-13T12:00:00+00:00'},
    ]
    with self.assertRaises(ValueError): d.current_release(items)
  def test_render_release_binds_numeric_ordered_migration_set(self):
    with tempfile.TemporaryDirectory() as root:
      root=Path(root); migrations=root/'migrations'; migrations.mkdir()
      (migrations/'V10__later.sql').write_text('select 10;\n')
      (migrations/'V2__earlier.sql').write_text('select 2;\n')
      manifest=root/'stack.yaml'; manifest.write_text('services: {}\n')
      guard=root/'guard.sh'; guard.write_text('#!/bin/sh\n')
      output=root/'release.json'
      subprocess.run([
        sys.executable,str(Path(__file__).parents[1]/'render-release.py'),
        '--source-commit','a'*40,
        '--server-image','ghcr.io/x/server@sha256:'+'1'*64,
        '--site-image','ghcr.io/x/site@sha256:'+'2'*64,
        '--backup-image','ghcr.io/x/backup@sha256:'+'3'*64,
        '--postgres-image','ghcr.io/x/db@sha256:'+'4'*64,
        '--manifest',str(manifest),'--volume-guard',str(guard),
        '--migrations-dir',str(migrations),'--output',str(output),
      ],check=True)
      release=d.load_release(str(output))
      self.assertEqual([item['id'] for item in release['migrationHistory']],['V2__earlier','V10__later'])
      self.assertEqual(release['migrationHistory'][0]['sha256'],hashlib.sha256(b'select 2;\n').hexdigest())
  def test_bootstrap_requires_exact_latest_manual_deployment(self):
    rid='sha256:'+'9'*64
    items=[
      {'status':'done','title':rid+'|V15__current','createdAt':'2026-07-13T11:00:00Z'},
      {'status':'success','title':'Manual deployment','createdAt':'2026-07-13T12:00:00Z'},
    ]
    d.require_manual_current(items)
    items[-1]['title']='manual deployment'
    with self.assertRaises(ValueError): d.require_manual_current(items)
    with self.assertRaises(ValueError): d.require_manual_current([])
  def test_bootstrap_deploy_rechecks_manual_current_before_mutation(self):
    checksum=hashlib.sha256((Path(__file__).parents[1]/'stack.yaml').read_bytes()).hexdigest()
    guard_checksum=hashlib.sha256((Path(__file__).parents[1]/'volume-guard.sh').read_bytes()).hexdigest()
    path,_=self.release(manifestSha256=checksum,volumeGuardSha256=guard_checksum)
    calls=[]
    class Fake:
      def __init__(self,*_): pass
      def request(self,method,request_path,body=None):
        calls.append((method,request_path,body))
        if request_path.startswith('/api/deployment.allByCompose'):
          return [{'deploymentId':'manual-1','title':'unexpected manual title','status':'done','createdAt':'2026-07-13T12:00:00Z'}]
        return []
    argv=['dokploy.py','deploy-release',path,'--compose-id','c1','--site-application-id','a1','--bootstrap-manual-current']
    with mock.patch.object(d,'Client',Fake), mock.patch.object(sys,'argv',argv), mock.patch.dict(os.environ,{'DOKPLOY_URL':'https://dokploy.test','DOKPLOY_TOKEN':'token'},clear=False):
      with self.assertRaises(ValueError): d.main()
    self.assertNotIn('POST',[method for method,_,_ in calls])
  def test_client_rejects_non_origin_urls_and_disables_redirects(self):
    with self.assertRaises(ValueError): d.Client('https://dokploy.test/path','token')
    client=d.Client('https://dokploy.test','token')
    redirect=next(handler for handler in client.opener.handlers if type(handler).__name__ == 'NoRedirect')
    self.assertIsNone(redirect.redirect_request(None,None,302,'Found',{},'https://attacker.test'))
  def test_review_name_is_deterministic(self):
    self.assertEqual(d.review_name(42),'mr42')
    with self.assertRaises(ValueError): d.review_name(0)
  def test_review_provider_id_is_repo_scoped(self):
    provider_id=d.review_provider_id('BuildHound/BuildHound',42)
    self.assertEqual(provider_id,d.review_provider_id('buildhound/buildhound',42))
    self.assertNotEqual(provider_id,d.review_provider_id('Another/Repository',42))
    self.assertRegex(provider_id,r'^bh-[0-9a-f]{24}-mr42$')
  def test_review_retry_updates_owned_compose(self):
    calls=[]
    class Fake:
      deployed=False
      def __init__(self,*_): pass
      def request(self,method,path,body=None):
        calls.append((method,path,body))
        if path.startswith('/api/environment.one'): return {'compose':[{'name':'mr42','composeId':'c1','description':'{"repository":"BuildHound/BuildHound","pr":42,"sha":"'+'a'*40+'"}'}]}
        if path == '/api/compose.deploy': Fake.deployed=True; return {}
        if path.startswith('/api/deployment.allByCompose') and Fake.deployed: return [{'deploymentId':'new-review','title':'a'*40,'status':'done'}]
        if path.startswith('/api/deployment.allByCompose'): return [{'deploymentId':'old-review','title':'old','status':'done'}]
        return {}
    argv=['dokploy.py','deploy-review','--base-repo','BuildHound/BuildHound','--head-repo','BuildHound/BuildHound','--sha','a'*40,'--state','open','--environment-id','e1','--dns-suffix','reviews.test','--pr','42','--label-present','--server-image','ghcr.io/x/server@sha256:'+'1'*64,'--site-image','ghcr.io/x/site@sha256:'+'2'*64]
    env={'DOKPLOY_URL':'https://dokploy.test','DOKPLOY_TOKEN':'token','BUILDHOUND_REVIEW_DB_PASSWORD':'db','BUILDHOUND_REVIEW_TOKEN':'review'}
    with mock.patch.object(d,'Client',Fake), mock.patch.object(sys,'argv',argv), mock.patch.dict(os.environ,env,clear=False): self.assertEqual(d.main(),0)
    self.assertIn('/api/compose.update',[x[1] for x in calls]); self.assertNotIn('/api/compose.create',[x[1] for x in calls])
  def test_review_create_separates_public_name_from_provider_id(self):
    calls=[]
    class Fake:
      deployed=False
      def __init__(self,*_): pass
      def request(self,method,path,body=None):
        calls.append((method,path,body))
        if path.startswith('/api/environment.one'): return {'compose':[]}
        if path == '/api/compose.create': return {'composeId':'c1'}
        if path == '/api/compose.deploy': Fake.deployed=True; return {}
        if path.startswith('/api/deployment.allByCompose') and Fake.deployed: return [{'deploymentId':'new-review','title':'a'*40,'status':'done'}]
        return {}
    argv=['dokploy.py','deploy-review','--base-repo','BuildHound/BuildHound','--head-repo','BuildHound/BuildHound','--sha','a'*40,'--state','open','--environment-id','e1','--dns-suffix','reviews.test','--pr','42','--label-present','--server-image','ghcr.io/x/server@sha256:'+'1'*64,'--site-image','ghcr.io/x/site@sha256:'+'2'*64]
    env={'DOKPLOY_URL':'https://dokploy.test','DOKPLOY_TOKEN':'token','BUILDHOUND_REVIEW_DB_PASSWORD':'db','BUILDHOUND_REVIEW_TOKEN':'review'}
    with mock.patch.object(d,'Client',Fake), mock.patch.object(sys,'argv',argv), mock.patch.dict(os.environ,env,clear=False): self.assertEqual(d.main(),0)
    create=next(body for method,path,body in calls if method == 'POST' and path == '/api/compose.create')
    provider_id=d.review_provider_id('BuildHound/BuildHound',42)
    self.assertEqual(create['name'],'mr42'); self.assertEqual(create['appName'],provider_id)
    self.assertIn(f'traefik.http.routers.{provider_id}-site',create['composeFile'])
    self.assertNotIn('traefik.http.routers.mr42-site',create['composeFile'])
  def test_release_updates_exact_resources_before_deploy(self):
    checksum=hashlib.sha256((Path(__file__).parents[1]/'stack.yaml').read_bytes()).hexdigest()
    guard_checksum=hashlib.sha256((Path(__file__).parents[1]/'volume-guard.sh').read_bytes()).hexdigest()
    path,_=self.release(manifestSha256=checksum,volumeGuardSha256=guard_checksum)
    calls=[]
    class Fake:
      deployed=False
      def __init__(self,*_): pass
      def request(self,method,path,body=None):
        calls.append((method,path,body))
        if path in ('/api/compose.deploy','/api/application.deploy'): Fake.deployed=True; return {}
        if path.startswith('/api/deployment.') and Fake.deployed: return [{'deploymentId':path,'title':d.release_title(d.load_release(path_file)),'status':'done'}]
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
