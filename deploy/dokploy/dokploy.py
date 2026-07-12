#!/usr/bin/env python3
"""Fail-closed Dokploy delivery client. Never logs credentials or response bodies."""
from __future__ import annotations
import argparse, hashlib, json, os, re, sys, time, urllib.error, urllib.request
from pathlib import Path

DIGEST = re.compile(r"^[a-z0-9./_-]+(?::[a-z0-9._-]+)?@sha256:[0-9a-f]{64}$")
SHA = re.compile(r"^[0-9a-f]{40}$")
RELEASE_KEYS = {"schema", "sourceCommit", "serverImage", "siteImage", "backupImage", "postgresImage", "manifestSha256", "migrationId"}

def canonical(value: dict) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()

def load_release(path: str) -> dict:
    value = json.loads(Path(path).read_text())
    if set(value) != RELEASE_KEYS or value["schema"] != 1 or not SHA.fullmatch(value["sourceCommit"]):
        raise ValueError("invalid release schema or source commit")
    for key in ("serverImage", "siteImage", "backupImage", "postgresImage"):
        if not DIGEST.fullmatch(value[key]): raise ValueError(f"{key} is not digest-addressed")
    if not re.fullmatch(r"[0-9a-f]{64}", value["manifestSha256"]): raise ValueError("invalid manifest checksum")
    if not re.fullmatch(r"V[0-9]+__[A-Za-z0-9_.-]+", value["migrationId"]): raise ValueError("invalid migration identity")
    return value

class Client:
    def __init__(self, base: str, token: str):
        if not base.startswith("https://") or not token: raise ValueError("HTTPS base URL and token required")
        self.base, self.token = base.rstrip("/"), token
    def request(self, method: str, path: str, body: dict | None = None):
        data = canonical(body) if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method,
            headers={"x-api-key": self.token, "Content-Type": "application/json", "Accept": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Dokploy API {method} {path} failed with HTTP {error.code}") from None

def release_id(value: dict) -> str: return "sha256:" + hashlib.sha256(canonical(value)).hexdigest()

def review_name(repo: str, number: int) -> str:
    slug = re.sub(r"[^a-z0-9-]", "-", repo.lower()).strip("-")
    return f"review-{slug}-{number}"

def require_review(args):
    if not SHA.fullmatch(args.sha): raise ValueError("exact 40-character head SHA required")
    if args.head_repo != args.base_repo: raise ValueError("fork reviews are forbidden")
    if args.state != "open" or not args.label_present: raise ValueError("PR is not currently eligible")

def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("release-id"); validate.add_argument("release")
    deploy = sub.add_parser("deploy-release"); deploy.add_argument("release"); deploy.add_argument("--compose-id", required=True); deploy.add_argument("--site-application-id", required=True); deploy.add_argument("--proven-release-id")
    review = sub.add_parser("deploy-review")
    for name, required in (("--base-repo",True),("--head-repo",True),("--sha",True),("--state",True),("--environment-id",True),("--dns-suffix",True)):
        review.add_argument(name, required=required)
    review.add_argument("--pr", required=True, type=int); review.add_argument("--label-present", action="store_true")
    review.add_argument("--server-image", required=True); review.add_argument("--site-image", required=True)
    delete = sub.add_parser("delete-review"); delete.add_argument("--base-repo", required=True); delete.add_argument("--pr", required=True, type=int); delete.add_argument("--environment-id", required=True); delete.add_argument("--expected-compose-id", required=True); delete.add_argument("--expected-sha", required=True)
    count = sub.add_parser("count-reviews"); count.add_argument("--base-repo", required=True); count.add_argument("--environment-id", required=True)
    listing = sub.add_parser("list-reviews"); listing.add_argument("--base-repo", required=True); listing.add_argument("--environment-id", required=True)
    current = sub.add_parser("current-release-id"); current.add_argument("--compose-id", required=True)
    args = parser.parse_args()
    if args.command == "release-id":
        print(release_id(load_release(args.release))); return 0
    client = Client(os.environ["DOKPLOY_URL"], os.environ["DOKPLOY_TOKEN"])
    if args.command == "current-release-id":
        items=client.request("GET",f"/api/deployment.allByCompose?composeId={args.compose_id}")
        matches=[x for x in items if x.get("status") in ("done","success") and re.fullmatch(r"sha256:[0-9a-f]{64}",x.get("title", ""))]
        if not matches: raise ValueError("no current successful release deployment found")
        print(max(matches,key=lambda x:x.get("createdAt", ""))["title"]); return 0
    if args.command == "deploy-release":
        release = load_release(args.release); rid = release_id(release)
        if args.proven_release_id and rid != args.proven_release_id: raise ValueError("production release differs from staging-proven release")
        manifest = Path(__file__).with_name("stack.yaml")
        if hashlib.sha256(manifest.read_bytes()).hexdigest() != release["manifestSha256"]: raise ValueError("release manifest checksum differs from trusted stack")
        compose = manifest.read_text().replace("${BUILDHOUND_SERVER_IMAGE}", release["serverImage"]).replace("${BUILDHOUND_BACKUP_IMAGE}", release["backupImage"])
        compose = compose.replace("${BUILDHOUND_RELEASE_ID:-manual}", rid)
        compose = re.sub(r"timescale/timescaledb:[^\s]+@sha256:[0-9a-f]{64}", release["postgresImage"], compose)
        client.request("POST", "/api/compose.update", {"composeId":args.compose_id,"composeFile":compose,"composeType":"stack","sourceType":"raw"})
        client.request("POST", "/api/application.update", {"applicationId":args.site_application_id,"dockerImage":release["siteImage"],"sourceType":"docker"})
        old_compose={x.get("deploymentId") for x in client.request("GET",f"/api/deployment.allByCompose?composeId={args.compose_id}")}
        old_site={x.get("deploymentId") for x in client.request("GET",f"/api/deployment.all?applicationId={args.site_application_id}")}
        client.request("POST", "/api/compose.deploy", {"composeId":args.compose_id,"title":rid})
        client.request("POST", "/api/application.deploy", {"applicationId":args.site_application_id,"title":rid})
        def wait(path, old):
            for _ in range(120):
                items=client.request("GET",path)
                matches=[x for x in items if x.get("deploymentId") not in old and x.get("title")==rid]
                failed=[x for x in matches if x.get("status") in ("error","failed","cancelled")]
                if failed: raise RuntimeError("Dokploy deployment reached a failed terminal state")
                done=[x for x in matches if x.get("status") in ("done","success") and x.get("deploymentId")]
                if len(done)==1: return done[0]["deploymentId"]
                if len(done)>1: raise ValueError("new deployment evidence is ambiguous")
                time.sleep(5)
            raise RuntimeError("Dokploy deployment did not reach success within 10 minutes")
        compose_deployment=wait(f"/api/deployment.allByCompose?composeId={args.compose_id}",old_compose)
        site_deployment=wait(f"/api/deployment.all?applicationId={args.site_application_id}",old_site)
        app=client.request("GET",f"/api/application.one?applicationId={args.site_application_id}")
        if app.get("dockerImage") != release["siteImage"]: raise ValueError("deployed site image differs from release")
        print(json.dumps({"releaseId":rid,"composeDeploymentId":compose_deployment,"siteDeploymentId":site_deployment},separators=(",",":"))); return 0
    if args.command == "deploy-review":
        require_review(args); name=review_name(args.base_repo,args.pr)
        if not DIGEST.fullmatch(args.server_image) or not DIGEST.fullmatch(args.site_image): raise ValueError("review images must be resolved digests")
        db_password=os.environ["BUILDHOUND_REVIEW_DB_PASSWORD"]; token=os.environ["BUILDHOUND_REVIEW_TOKEN"]
        compose=Path(__file__).with_name("review-stack.yaml").read_text()
        replacements={"${BUILDHOUND_SERVER_IMAGE}":args.server_image,"${BUILDHOUND_SITE_IMAGE}":args.site_image,"${BUILDHOUND_REVIEW_DB_PASSWORD}":db_password,"${BUILDHOUND_REVIEW_TOKEN}":token,"${BUILDHOUND_REPOSITORY}":args.base_repo,"${BUILDHOUND_PR_NUMBER}":str(args.pr),"${BUILDHOUND_HEAD_SHA}":args.sha,"${BUILDHOUND_REVIEW_NAME}":name,"${BUILDHOUND_REVIEW_SITE_HOST}":f"{name}.{args.dns_suffix}","${BUILDHOUND_REVIEW_DASHBOARD_HOST}":f"dashboard-{name}.{args.dns_suffix}"}
        for old,new in replacements.items(): compose=compose.replace(old,new)
        description=json.dumps({"repository":args.base_repo,"pr":args.pr,"sha":args.sha},separators=(",",":"))
        environment=client.request("GET",f"/api/environment.one?environmentId={args.environment_id}")
        owned=[]
        for item in environment.get("compose",[]):
            try: metadata=json.loads(item.get("description") or "{}")
            except json.JSONDecodeError: continue
            if item.get("name")==name and metadata.get("repository")==args.base_repo and metadata.get("pr")==args.pr: owned.append(item)
        if len(owned)>1: raise ValueError("review ownership is ambiguous")
        if owned:
            compose_id=owned[0]["composeId"]
            client.request("POST","/api/compose.update",{"composeId":compose_id,"description":description,"composeType":"stack","composeFile":compose})
        else:
            result=client.request("POST","/api/compose.create",{"environmentId":args.environment_id,"name":name,"appName":name,"description":description,"composeType":"stack","composeFile":compose})
            compose_id=result["composeId"]
            client.request("POST","/api/compose.isolatedDeployment",{"composeId":compose_id})
        client.request("POST","/api/compose.deploy",{"composeId":compose_id,"title":args.sha})
        print(json.dumps({"name":name,"composeId":compose_id})); return 0
    if args.command in ("count-reviews", "list-reviews"):
        environment=client.request("GET",f"/api/environment.one?environmentId={args.environment_id}")
        found=[]
        for item in environment.get("compose",[]):
            try: metadata=json.loads(item.get("description") or "{}")
            except json.JSONDecodeError: continue
            if metadata.get("repository")==args.base_repo and isinstance(metadata.get("pr"),int): found.append({"pr":metadata["pr"],"sha":metadata.get("sha"),"createdAt":item.get("createdAt"),"composeId":item.get("composeId")})
        print(len(found) if args.command=="count-reviews" else json.dumps(found,separators=(",",":"))); return 0
    name=review_name(args.base_repo,args.pr)
    environment=client.request("GET",f"/api/environment.one?environmentId={args.environment_id}")
    items=environment.get("compose",[])
    matches=[]
    for item in items:
        try: metadata=json.loads(item.get("description") or "{}")
        except json.JSONDecodeError: continue
        if item.get("name")==name and item.get("composeId")==args.expected_compose_id and metadata.get("repository")==args.base_repo and metadata.get("pr")==args.pr and metadata.get("sha")==args.expected_sha: matches.append(item)
    if len(matches)!=1: raise ValueError("review ownership is missing or ambiguous")
    item=matches[0]
    client.request("POST","/api/compose.delete",{"composeId":item["composeId"],"deleteVolumes":True})
    print(json.dumps({"deleted":name})); return 0

if __name__ == "__main__":
    try: raise SystemExit(main())
    except (KeyError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr); raise SystemExit(2)
