#!/usr/bin/env python3
import argparse, hashlib, json, re
from pathlib import Path
p=argparse.ArgumentParser(); p.add_argument('--source-commit',required=True); p.add_argument('--server-image',required=True); p.add_argument('--site-image',required=True); p.add_argument('--backup-image',required=True); p.add_argument('--postgres-image',required=True); p.add_argument('--manifest',required=True); p.add_argument('--migration-id',required=True); p.add_argument('--output',default='release.json'); a=p.parse_args()
if not re.fullmatch(r'[0-9a-f]{40}',a.source_commit): p.error('source commit must be full SHA')
value={'schema':1,'sourceCommit':a.source_commit,'serverImage':a.server_image,'siteImage':a.site_image,'backupImage':a.backup_image,'postgresImage':a.postgres_image,'manifestSha256':hashlib.sha256(Path(a.manifest).read_bytes()).hexdigest(),'migrationId':a.migration_id}
Path(a.output).write_text(json.dumps(value,sort_keys=True,separators=(',',':'))+'\n')
