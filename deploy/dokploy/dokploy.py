#!/usr/bin/env python3
"""Fail-closed Dokploy delivery client. Never logs credentials or response bodies."""
from __future__ import annotations
import argparse, hashlib, json, os, re, sys, time, urllib.error, urllib.parse, urllib.request
from datetime import datetime
from pathlib import Path

DIGEST = re.compile(r"^[a-z0-9./_-]+(?::[a-z0-9._-]+)?@sha256:[0-9a-f]{64}$")
SHA = re.compile(r"^[0-9a-f]{40}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
HOST_LABEL = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
RELEASE_ID = re.compile(r"^sha256:[0-9a-f]{64}$")
MIGRATION_ID = re.compile(r"^V([0-9]+)__[A-Za-z0-9_.-]+$")
RELEASE_TITLE = re.compile(r"^(sha256:[0-9a-f]{64})\|(V[0-9]+__[A-Za-z0-9_.-]+)\|([0-9a-f]{64})$")
LEGACY_RELEASE_TITLE = re.compile(r"^(sha256:[0-9a-f]{64})\|(V[0-9]+__[A-Za-z0-9_.-]+)$")
MANUAL_DEPLOYMENT_TITLE = "Manual deployment"
RELEASE_KEYS_V1 = {"schema", "sourceCommit", "serverImage", "siteImage", "backupImage", "postgresImage", "manifestSha256", "volumeGuardSha256", "migrationId"}
RELEASE_KEYS_V2 = RELEASE_KEYS_V1 | {"migrationHistory", "migrationHistorySha256"}

def canonical(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()

def migration_history_sha256(history: list[dict]) -> str:
    return hashlib.sha256(canonical(history)).hexdigest()

def load_release(path: str) -> dict:
    value = json.loads(Path(path).read_text())
    if not isinstance(value, dict):
        raise ValueError("invalid release schema or source commit")
    schema = value.get("schema")
    keys = RELEASE_KEYS_V1 if schema == 1 else RELEASE_KEYS_V2 if schema == 2 else set()
    if set(value) != keys or not isinstance(value.get("sourceCommit"), str) or not SHA.fullmatch(value["sourceCommit"]):
        raise ValueError("invalid release schema or source commit")
    for key in ("serverImage", "siteImage", "backupImage", "postgresImage"):
        if not DIGEST.fullmatch(value[key]): raise ValueError(f"{key} is not digest-addressed")
    if not re.fullmatch(r"[0-9a-f]{64}", value["manifestSha256"]): raise ValueError("invalid manifest checksum")
    if not re.fullmatch(r"[0-9a-f]{64}", value["volumeGuardSha256"]): raise ValueError("invalid volume guard checksum")
    if not MIGRATION_ID.fullmatch(value["migrationId"]): raise ValueError("invalid migration identity")
    if schema == 2:
        history = value["migrationHistory"]
        if not isinstance(history, list) or not history: raise ValueError("migration history must be a non-empty ordered list")
        previous_version = -1
        for migration in history:
            if not isinstance(migration, dict) or set(migration) != {"id", "sha256"}:
                raise ValueError("invalid migration history entry")
            migration_match = MIGRATION_ID.fullmatch(migration.get("id", ""))
            if migration_match is None or not re.fullmatch(r"[0-9a-f]{64}", migration.get("sha256", "")):
                raise ValueError("invalid migration history entry")
            version = int(migration_match.group(1))
            if version <= previous_version: raise ValueError("migration history is not strictly ordered")
            previous_version = version
        if history[-1]["id"] != value["migrationId"]: raise ValueError("latest migration identity differs from migration history")
        if value["migrationHistorySha256"] != migration_history_sha256(history):
            raise ValueError("migration history checksum differs from ordered migration set")
    return value

class Client:
    def __init__(self, base: str, token: str):
        parsed = urllib.parse.urlsplit(base)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.path not in ("", "/") or parsed.query or parsed.fragment or not token:
            raise ValueError("exact HTTPS base origin and token required")
        self.base, self.token = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, "", "", "")), token
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, request, fp, code, message, headers, new_url):
                return None
        self.opener = urllib.request.build_opener(NoRedirect)
    def request(self, method: str, path: str, body: dict | None = None):
        data = canonical(body) if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method,
            headers={"x-api-key": self.token, "Content-Type": "application/json", "Accept": "application/json"})
        try:
            with self.opener.open(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Dokploy API {method} {path} failed with HTTP {error.code}") from None

def release_id(value: dict) -> str: return "sha256:" + hashlib.sha256(canonical(value)).hexdigest()

def release_title(value: dict) -> str:
    suffix = f"|{value['migrationHistorySha256']}" if value["schema"] == 2 else ""
    return f"{release_id(value)}|{value['migrationId']}{suffix}"

def parse_release_title(title: object) -> tuple[str, str | None, str | None] | None:
    if not isinstance(title, str): return None
    if RELEASE_ID.fullmatch(title): return title, None, None
    match = RELEASE_TITLE.fullmatch(title)
    if match: return match.group(1), match.group(2), match.group(3)
    legacy = LEGACY_RELEASE_TITLE.fullmatch(title)
    return (legacy.group(1), legacy.group(2), None) if legacy else None

def latest_successful_deployment(items: list[dict]) -> dict | None:
    successful = [item for item in items if str(item.get("status", "")).lower() in ("done", "success")]
    if not successful: return None
    dated: list[tuple[datetime, dict]] = []
    for item in successful:
        created_at = item.get("createdAt")
        if not isinstance(created_at, str): raise ValueError("successful deployment is missing its creation timestamp")
        try:
            timestamp = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
        except ValueError:
            raise ValueError("successful deployment has an invalid creation timestamp") from None
        if timestamp.tzinfo is None: raise ValueError("successful deployment creation timestamp lacks a timezone")
        dated.append((timestamp, item))
    latest_timestamp = max(timestamp for timestamp, _ in dated)
    latest = [item for timestamp, item in dated if timestamp == latest_timestamp]
    if len(latest) != 1: raise ValueError("latest successful deployment is ambiguous")
    return latest[0]

def current_release(items: list[dict]) -> tuple[str, str | None, str | None] | None:
    latest = latest_successful_deployment(items)
    return parse_release_title(latest.get("title")) if latest else None

def require_manual_current(items: list[dict]) -> None:
    latest = latest_successful_deployment(items)
    if latest is None: raise ValueError("no current successful deployment found")
    if latest.get("title") != MANUAL_DEPLOYMENT_TITLE:
        raise ValueError("latest successful deployment is not the explicit manual deployment")

def require_migration_compatibility(current: tuple[str, str | None, str | None] | None, release: dict,
                                    attested: bool) -> None:
    if current is None or current[0] == release_id(release): return
    previous = current[1]
    candidate = release["migrationId"]
    if previous is None:
        if not attested: raise ValueError("current deployment lacks migration identity; compatibility attestation required")
        return
    previous_version = int(MIGRATION_ID.fullmatch(previous).group(1))
    candidate_version = int(MIGRATION_ID.fullmatch(candidate).group(1))
    if candidate_version == previous_version and candidate != previous:
        raise ValueError("migration history was rewritten at the current version")
    if candidate_version < previous_version:
        if not attested: raise ValueError("rollback migration compatibility attestation required")
        return
    previous_history_sha256 = current[2]
    if previous_history_sha256 is None:
        if not attested: raise ValueError("current deployment lacks full migration history; compatibility attestation required")
        return
    if release["schema"] != 2:
        raise ValueError("candidate release lacks full migration history")
    history = release["migrationHistory"]
    previous_index = next((index for index, migration in enumerate(history) if migration["id"] == previous), None)
    if previous_index is None or migration_history_sha256(history[:previous_index + 1]) != previous_history_sha256:
        raise ValueError("migration history before the candidate was rewritten")

def review_name(number: int) -> str:
    if number <= 0:
        raise ValueError("positive PR number required")
    return f"mr{number}"

def review_provider_id(repo: str, number: int) -> str:
    identifier = f"bh-{hashlib.sha256(repo.lower().encode()).hexdigest()[:24]}-{review_name(number)}"
    if len(identifier) > 63:
        raise ValueError("derived review provider ID is invalid")
    return identifier

def require_review(args):
    if args.pr <= 0 or not REPOSITORY.fullmatch(args.base_repo): raise ValueError("valid repository and positive PR number required")
    if not SHA.fullmatch(args.sha): raise ValueError("exact 40-character head SHA required")
    if args.head_repo != args.base_repo: raise ValueError("fork reviews are forbidden")
    if args.state != "open" or not args.label_present: raise ValueError("PR is not currently eligible")

def review_hosts(name: str, suffix: str) -> tuple[str, str]:
    labels = suffix.split(".")
    if suffix != suffix.lower() or len(suffix) > 253 or len(labels) < 2 or any(not HOST_LABEL.fullmatch(label) for label in labels):
        raise ValueError("invalid review DNS suffix")
    site_host, dashboard_host = f"{name}.{suffix}", f"{name}.dashboard.{suffix}"
    for host in (site_host, dashboard_host):
        if len(host) > 253 or any(not HOST_LABEL.fullmatch(label) for label in host.split(".")):
            raise ValueError("derived review host is invalid")
    return site_host, dashboard_host

def wait_for_deployment(client: Client, path: str, old_ids: set[str], title: str,
                        timeout_seconds: float = 600, poll_seconds: float = 5) -> str:
    old_ids = {item for item in old_ids if isinstance(item, str) and item}
    deadline = time.monotonic() + timeout_seconds
    while True:
        items = client.request("GET", path)
        matches = [item for item in items if item.get("title") == title and item.get("deploymentId") not in old_ids]
        if len(matches) > 1: raise ValueError("new deployment evidence is ambiguous")
        if matches:
            deployment = matches[0]
            status = str(deployment.get("status", "")).lower()
            if status in ("error", "failed", "cancelled"): raise RuntimeError("Dokploy deployment reached a failed terminal state")
            if status in ("done", "success"):
                deployment_id = deployment.get("deploymentId")
                if not isinstance(deployment_id, str) or not deployment_id: raise ValueError("successful deployment is missing its ID")
                return deployment_id
        remaining = deadline - time.monotonic()
        if remaining <= 0: raise RuntimeError("Dokploy deployment did not reach success within 10 minutes")
        time.sleep(min(poll_seconds, remaining))

def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("release-id"); validate.add_argument("release")
    deploy = sub.add_parser("deploy-release"); deploy.add_argument("release"); deploy.add_argument("--compose-id", required=True); deploy.add_argument("--site-application-id", required=True); deploy.add_argument("--proven-release-id")
    deploy.add_argument("--manifest", default=str(Path(__file__).with_name("stack.yaml")))
    deploy.add_argument("--volume-guard", default=str(Path(__file__).with_name("volume-guard.sh")))
    deploy.add_argument("--rollback-compatible", action="store_true")
    deploy.add_argument("--bootstrap-manual-current", action="store_true")
    review = sub.add_parser("deploy-review")
    for name, required in (("--base-repo",True),("--head-repo",True),("--sha",True),("--state",True),("--environment-id",True),("--dns-suffix",True)):
        review.add_argument(name, required=required)
    review.add_argument("--pr", required=True, type=int); review.add_argument("--label-present", action="store_true")
    review.add_argument("--server-image", required=True); review.add_argument("--site-image", required=True)
    delete = sub.add_parser("delete-review"); delete.add_argument("--base-repo", required=True); delete.add_argument("--pr", required=True, type=int); delete.add_argument("--environment-id", required=True); delete.add_argument("--expected-compose-id", required=True); delete.add_argument("--expected-sha", required=True)
    count = sub.add_parser("count-reviews"); count.add_argument("--base-repo", required=True); count.add_argument("--environment-id", required=True); count.add_argument("--exclude-pr", type=int)
    listing = sub.add_parser("list-reviews"); listing.add_argument("--base-repo", required=True); listing.add_argument("--environment-id", required=True)
    current = sub.add_parser("current-release-id"); current.add_argument("--compose-id", required=True)
    manual = sub.add_parser("require-manual-current"); manual.add_argument("--compose-id", required=True)
    args = parser.parse_args()
    if args.command == "count-reviews" and args.exclude_pr is not None and args.exclude_pr <= 0:
        raise ValueError("excluded PR must be positive")
    if args.command == "release-id":
        print(release_id(load_release(args.release))); return 0
    client = Client(os.environ["DOKPLOY_URL"], os.environ["DOKPLOY_TOKEN"])
    if args.command in ("current-release-id", "require-manual-current"):
        items=client.request("GET",f"/api/deployment.allByCompose?composeId={args.compose_id}")
        if args.command == "require-manual-current":
            require_manual_current(items); return 0
        current=current_release(items)
        if current is None: raise ValueError("no current successful release deployment found")
        print(current[0]); return 0
    if args.command == "deploy-release":
        release = load_release(args.release); rid = release_id(release)
        if args.proven_release_id and rid != args.proven_release_id: raise ValueError("production release differs from staging-proven release")
        manifest = Path(args.manifest)
        volume_guard = Path(args.volume_guard)
        if hashlib.sha256(manifest.read_bytes()).hexdigest() != release["manifestSha256"]: raise ValueError("release manifest checksum differs from trusted stack")
        if hashlib.sha256(volume_guard.read_bytes()).hexdigest() != release["volumeGuardSha256"]: raise ValueError("release volume guard checksum differs from trusted source")
        title = release_title(release)
        existing_compose=client.request("GET",f"/api/deployment.allByCompose?composeId={args.compose_id}")
        if args.bootstrap_manual_current: require_manual_current(existing_compose)
        current=current_release(existing_compose)
        has_unidentified_success=any(str(item.get("status", "")).lower() in ("done", "success") for item in existing_compose) and current is None
        if has_unidentified_success and not (args.rollback_compatible or args.bootstrap_manual_current):
            raise ValueError("successful current deployment lacks release/migration identity; compatibility attestation required")
        require_migration_compatibility(current, release, args.rollback_compatible)
        old_compose={x.get("deploymentId") for x in existing_compose}
        old_site={x.get("deploymentId") for x in client.request("GET",f"/api/deployment.all?applicationId={args.site_application_id}")}
        compose = manifest.read_text().replace("${BUILDHOUND_SERVER_IMAGE}", release["serverImage"]).replace("${BUILDHOUND_BACKUP_IMAGE}", release["backupImage"])
        compose = compose.replace("${BUILDHOUND_POSTGRES_IMAGE}", release["postgresImage"])
        compose = compose.replace("${BUILDHOUND_RELEASE_ID:-manual}", rid)
        client.request("POST", "/api/compose.update", {"composeId":args.compose_id,"composeFile":compose,"composeType":"stack","sourceType":"raw"})
        client.request("POST", "/api/application.update", {"applicationId":args.site_application_id,"dockerImage":release["siteImage"],"sourceType":"docker"})
        client.request("POST", "/api/compose.deploy", {"composeId":args.compose_id,"title":title})
        client.request("POST", "/api/application.deploy", {"applicationId":args.site_application_id,"title":title})
        compose_deployment=wait_for_deployment(client,f"/api/deployment.allByCompose?composeId={args.compose_id}",old_compose,title)
        site_deployment=wait_for_deployment(client,f"/api/deployment.all?applicationId={args.site_application_id}",old_site,title)
        app=client.request("GET",f"/api/application.one?applicationId={args.site_application_id}")
        if app.get("dockerImage") != release["siteImage"]: raise ValueError("deployed site image differs from release")
        print(json.dumps({"releaseId":rid,"migrationId":release["migrationId"],"migrationHistorySha256":release.get("migrationHistorySha256"),"composeDeploymentId":compose_deployment,"siteDeploymentId":site_deployment},separators=(",",":"))); return 0
    if args.command == "deploy-review":
        require_review(args); name=review_name(args.pr)
        provider_id=review_provider_id(args.base_repo,args.pr)
        site_host, dashboard_host = review_hosts(name, args.dns_suffix)
        if not DIGEST.fullmatch(args.server_image) or not DIGEST.fullmatch(args.site_image): raise ValueError("review images must be resolved digests")
        db_password=os.environ["BUILDHOUND_REVIEW_DB_PASSWORD"]; token=os.environ["BUILDHOUND_REVIEW_TOKEN"]
        compose=Path(__file__).with_name("review-stack.yaml").read_text()
        replacements={"${BUILDHOUND_SERVER_IMAGE}":args.server_image,"${BUILDHOUND_SITE_IMAGE}":args.site_image,"${BUILDHOUND_REVIEW_DB_PASSWORD}":db_password,"${BUILDHOUND_REVIEW_TOKEN}":token,"${BUILDHOUND_REPOSITORY}":args.base_repo,"${BUILDHOUND_PR_NUMBER}":str(args.pr),"${BUILDHOUND_HEAD_SHA}":args.sha,"${BUILDHOUND_REVIEW_PROVIDER_ID}":provider_id,"${BUILDHOUND_REVIEW_SITE_HOST}":site_host,"${BUILDHOUND_REVIEW_DASHBOARD_HOST}":dashboard_host}
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
            old_deployments={x.get("deploymentId") for x in client.request("GET",f"/api/deployment.allByCompose?composeId={compose_id}")}
            client.request("POST","/api/compose.update",{"composeId":compose_id,"description":description,"composeType":"stack","composeFile":compose})
        else:
            old_deployments=set()
            result=client.request("POST","/api/compose.create",{"environmentId":args.environment_id,"name":name,"appName":provider_id,"description":description,"composeType":"stack","composeFile":compose})
            compose_id=result["composeId"]
            client.request("POST","/api/compose.isolatedDeployment",{"composeId":compose_id})
        client.request("POST","/api/compose.deploy",{"composeId":compose_id,"title":args.sha})
        deployment_id=wait_for_deployment(client,f"/api/deployment.allByCompose?composeId={compose_id}",old_deployments,args.sha)
        print(json.dumps({"name":name,"composeId":compose_id,"deploymentId":deployment_id},separators=(",",":"))); return 0
    if args.command in ("count-reviews", "list-reviews"):
        environment=client.request("GET",f"/api/environment.one?environmentId={args.environment_id}")
        found=[]
        for item in environment.get("compose",[]):
            try: metadata=json.loads(item.get("description") or "{}")
            except json.JSONDecodeError: continue
            if metadata.get("repository")==args.base_repo and isinstance(metadata.get("pr"),int) and (args.command != "count-reviews" or args.exclude_pr is None or metadata["pr"] != args.exclude_pr): found.append({"pr":metadata["pr"],"sha":metadata.get("sha"),"createdAt":item.get("createdAt"),"composeId":item.get("composeId")})
        print(len(found) if args.command=="count-reviews" else json.dumps(found,separators=(",",":"))); return 0
    name=review_name(args.pr)
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
