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
REVIEW_INGRESS_NETWORK = "buildhound-review-ingress"
UNRESOLVED_PLACEHOLDER = re.compile(r"\$\{[^}\r\n]+}")
RELEASE_ID = re.compile(r"^sha256:[0-9a-f]{64}$")
MIGRATION_ID = re.compile(r"^V([0-9]+)__[A-Za-z0-9_.-]+$")
RELEASE_TITLE = re.compile(r"^(sha256:[0-9a-f]{64})\|(V[0-9]+__[A-Za-z0-9_.-]+)\|([0-9a-f]{64})$")
LEGACY_RELEASE_TITLE = re.compile(r"^(sha256:[0-9a-f]{64})\|(V[0-9]+__[A-Za-z0-9_.-]+)$")
MANUAL_DEPLOYMENT_TITLE = "Manual deployment"
RELEASE_KEYS_V1 = {"schema", "sourceCommit", "serverImage", "siteImage", "backupImage", "postgresImage", "manifestSha256", "volumeGuardSha256", "migrationId"}
RELEASE_KEYS_V2 = RELEASE_KEYS_V1 | {"migrationHistory", "migrationHistorySha256"}

class DeploymentFailedTerminal(RuntimeError):
    pass

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

def review_composes(environment: object) -> list[dict]:
    if not isinstance(environment, dict) or not isinstance(environment.get("compose"), list):
        raise ValueError("Dokploy returned an invalid review environment")
    if any(not isinstance(item, dict) for item in environment["compose"]):
        raise ValueError("Dokploy returned an invalid review Compose record")
    return environment["compose"]

def review_metadata(item: dict) -> dict:
    description = item.get("description") or "{}"
    if not isinstance(description, str):
        raise ValueError("review ownership metadata is invalid")
    try:
        metadata = json.loads(description)
    except (json.JSONDecodeError, TypeError):
        raise ValueError("review ownership metadata is invalid") from None
    if (
        not isinstance(metadata, dict)
        or set(metadata) != {"repository", "pr", "sha"}
        or not isinstance(metadata.get("repository"), str)
        or not REPOSITORY.fullmatch(metadata["repository"])
        or not isinstance(metadata.get("pr"), int)
        or isinstance(metadata["pr"], bool)
        or metadata["pr"] <= 0
        or not isinstance(metadata.get("sha"), str)
        or not SHA.fullmatch(metadata["sha"])
    ):
        raise ValueError("review ownership metadata is invalid")
    return metadata

def review_update(compose_id: str, description: str, compose: str) -> dict:
    return {
        "composeId": compose_id,
        "description": description,
        "composeType": "stack",
        "composeFile": compose,
        "sourceType": "raw",
        "command": "",
        "env": "",
        "autoDeploy": False,
        "enableSubmodules": False,
        "composePath": "./docker-compose.yml",
        "suffix": "",
        "randomize": False,
        "isolatedDeployment": False,
        "isolatedDeploymentsVolume": False,
        "triggerType": "push",
        "watchPaths": [],
        "repository": None,
        "owner": None,
        "branch": None,
        "githubId": None,
        "gitlabProjectId": None,
        "gitlabRepository": None,
        "gitlabOwner": None,
        "gitlabBranch": None,
        "gitlabPathNamespace": None,
        "gitlabId": None,
        "bitbucketRepository": None,
        "bitbucketRepositorySlug": None,
        "bitbucketOwner": None,
        "bitbucketBranch": None,
        "bitbucketId": None,
        "giteaRepository": None,
        "giteaOwner": None,
        "giteaBranch": None,
        "giteaId": None,
        "customGitUrl": None,
        "customGitBranch": None,
        "customGitSSHKeyId": None,
    }

def require_exact_review_compose(value: object, *, compose_id: str, environment_id: str,
                                 name: str, app_name_prefix: str, description: str, compose: str) -> str:
    if not isinstance(value, dict):
        raise ValueError("Dokploy returned an invalid review Compose")
    expected = review_update(compose_id, description, compose) | {
        "environmentId": environment_id,
        "name": name,
        "serverId": None,
    }
    if any(value.get(key) != expected_value for key, expected_value in expected.items()):
        raise ValueError("Dokploy persisted unexpected review Compose state")
    app_name = value.get("appName")
    if not isinstance(app_name, str) or not re.fullmatch(re.escape(app_name_prefix) + r"-[A-Za-z0-9]{6}", app_name):
        raise ValueError("Dokploy generated an unexpected review application name")
    if any(value.get(key) != [] for key in ("domains", "mounts", "backups")):
        raise ValueError("review Compose has hidden Dokploy resources")
    if any(value.get(key) is not None for key in ("github", "gitlab", "bitbucket", "gitea", "server")):
        raise ValueError("review Compose has a hidden provider or server target")
    environment = value.get("environment")
    if not isinstance(environment, dict) or environment.get("environmentId") != environment_id:
        raise ValueError("review Compose environment binding is invalid")
    project = environment.get("project")
    if not isinstance(project, dict) or environment.get("env") not in (None, "") or project.get("env") not in (None, ""):
        raise ValueError("review environment inherits untrusted deployment variables")
    return app_name

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
            if status in ("error", "failed", "cancelled"): raise DeploymentFailedTerminal("Dokploy deployment reached a failed terminal state")
            if status in ("done", "success"):
                deployment_id = deployment.get("deploymentId")
                if not isinstance(deployment_id, str) or not deployment_id: raise ValueError("successful deployment is missing its ID")
                return deployment_id
        remaining = deadline - time.monotonic()
        if remaining <= 0: raise RuntimeError("Dokploy deployment did not reach success within 10 minutes")
        time.sleep(min(poll_seconds, remaining))

def wait_for_review_routes_gone(name: str, suffix: str, timeout_seconds: float = 300,
                                poll_seconds: float = 5) -> None:
    site_host, dashboard_host = review_hosts(name, suffix)
    urls = (f"https://{site_host}/", f"https://{dashboard_host}/health")
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, fp, code, message, headers, new_url):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    deadline = time.monotonic() + timeout_seconds
    while True:
        statuses = []
        for url in urls:
            try:
                with opener.open(urllib.request.Request(url, method="GET"), timeout=20) as response:
                    statuses.append(response.status)
            except urllib.error.HTTPError as error:
                statuses.append(error.code)
                error.close()
            except urllib.error.URLError:
                statuses.append(None)
        if statuses == [404, 404]: return
        remaining = deadline - time.monotonic()
        if remaining <= 0: raise RuntimeError("review routes remained reachable after Swarm removal")
        time.sleep(min(poll_seconds, remaining))

def revoke_review_compose(client: Client, compose_id: str, name: str,
                          dns_suffix: str, expected_sha: str | None) -> None:
    # Remove waiting jobs, then require the installed version to remain non-running while an
    # already-dequeued worker has time to publish its running state.
    client.request("POST", "/api/compose.cleanQueues", {"composeId": compose_id})
    matching = []
    if expected_sha is not None:
        deployments = client.request("GET", f"/api/deployment.allByCompose?composeId={compose_id}")
        if not isinstance(deployments, list) or any(not isinstance(item, dict) for item in deployments):
            raise RuntimeError("Dokploy returned invalid deployment evidence during cleanup")
        matching = [item for item in deployments if item.get("title") == expected_sha]
        terminal = {"done", "success", "error", "failed", "cancelled"}
        if any(str(item.get("status", "")).lower() not in terminal for item in matching):
            raise RuntimeError("review deployment is still active; preserving reconciliation anchor")
    # A removed waiting job has no deployment row. In that case, repeatedly drain the queue
    # and observe a non-running Compose long enough for a dequeued worker to publish `running`.
    checks = 6 if expected_sha is not None and not matching else 2
    for check in range(checks):
        if check > 0 and not matching:
            client.request("POST", "/api/compose.cleanQueues", {"composeId": compose_id})
        compose = client.request("GET", f"/api/compose.one?composeId={compose_id}")
        if not isinstance(compose, dict) or compose.get("composeId") != compose_id or compose.get("composeStatus") not in ("idle", "done", "error"):
            raise RuntimeError("review deployment is still active or has unknown state")
        if check < checks - 1: time.sleep(5 if checks > 2 else 1)
    # compose.stop propagates Swarm removal errors; compose.delete alone does not on Dokploy v0.29.12.
    client.request("POST", "/api/compose.stop", {"composeId": compose_id})
    # Keep the database ownership anchor until Traefik has converged to route absence.
    wait_for_review_routes_gone(name, dns_suffix)

def delete_review_record(client: Client, compose_id: str, environment_id: str) -> None:
    client.request("POST", "/api/compose.delete", {"composeId": compose_id, "deleteVolumes": True})
    environment = client.request("GET", f"/api/environment.one?environmentId={environment_id}")
    if any(item.get("composeId") == compose_id for item in review_composes(environment)):
        raise RuntimeError("Dokploy still reports the deleted review Compose")

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
    for name, required in (("--base-repo",True),("--head-repo",True),("--sha",True),("--state",True),("--environment-id",True),("--dns-suffix",True),("--ingress-network",True)):
        review.add_argument(name, required=required)
    review.add_argument("--pr", required=True, type=int); review.add_argument("--label-present", action="store_true")
    review.add_argument("--server-image", required=True); review.add_argument("--site-image", required=True)
    for command in ("revoke-review", "delete-review"):
        delete = sub.add_parser(command); delete.add_argument("--base-repo", required=True); delete.add_argument("--pr", required=True, type=int); delete.add_argument("--environment-id", required=True); delete.add_argument("--dns-suffix", required=True); delete.add_argument("--expected-compose-id", required=True); delete.add_argument("--expected-sha", required=True)
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
        if args.ingress_network != REVIEW_INGRESS_NETWORK: raise ValueError("unexpected review ingress network")
        if not DIGEST.fullmatch(args.server_image) or not DIGEST.fullmatch(args.site_image): raise ValueError("review images must be resolved digests")
        db_password=os.environ["BUILDHOUND_REVIEW_DB_PASSWORD"]; token=os.environ["BUILDHOUND_REVIEW_TOKEN"]
        compose=Path(__file__).with_name("review-stack.yaml").read_text()
        replacements={"${BUILDHOUND_SERVER_IMAGE}":args.server_image,"${BUILDHOUND_SITE_IMAGE}":args.site_image,"${BUILDHOUND_REVIEW_DB_PASSWORD}":db_password,"${BUILDHOUND_REVIEW_TOKEN}":token,"${BUILDHOUND_REPOSITORY}":args.base_repo,"${BUILDHOUND_PR_NUMBER}":str(args.pr),"${BUILDHOUND_HEAD_SHA}":args.sha,"${BUILDHOUND_REVIEW_PROVIDER_ID}":provider_id,"${BUILDHOUND_REVIEW_SITE_HOST}":site_host,"${BUILDHOUND_REVIEW_DASHBOARD_HOST}":dashboard_host,"${DOKPLOY_REVIEW_INGRESS_NETWORK}":args.ingress_network}
        for old,new in replacements.items(): compose=compose.replace(old,new)
        if UNRESOLVED_PLACEHOLDER.search(compose): raise ValueError("trusted review manifest contains an unresolved placeholder")
        description=json.dumps({"repository":args.base_repo,"pr":args.pr,"sha":args.sha},separators=(",",":"))
        mutation_possible=False
        deploy_may_be_active=False
        deployment_failed_terminal=False
        try:
            environment=client.request("GET",f"/api/environment.one?environmentId={args.environment_id}")
            owned=[]
            for item in review_composes(environment):
                metadata=review_metadata(item)
                if item.get("name")==name and metadata.get("repository")==args.base_repo and metadata.get("pr")==args.pr: owned.append(item)
            if len(owned)>1: raise ValueError("review ownership is ambiguous")
            if owned:
                if owned[0].get("serverId") is not None: raise ValueError("owned review has an unexpected server target")
                compose_id=owned[0]["composeId"]
                old_deployments={x.get("deploymentId") for x in client.request("GET",f"/api/deployment.allByCompose?composeId={compose_id}")}
            else:
                old_deployments=set()
                mutation_possible=True
                result=client.request("POST","/api/compose.create",{"environmentId":args.environment_id,"name":name,"appName":provider_id,"description":description,"composeType":"stack","composeFile":compose})
                compose_id=result["composeId"]
            mutation_possible=True
            client.request("POST","/api/compose.update",review_update(compose_id,description,compose))
            persisted=client.request("GET",f"/api/compose.one?composeId={compose_id}")
            require_exact_review_compose(persisted,compose_id=compose_id,environment_id=args.environment_id,name=name,app_name_prefix=provider_id,description=description,compose=compose)
            # Once the request starts, an HTTP error or polling timeout cannot prove the queue job is inactive.
            deploy_may_be_active=True
            client.request("POST","/api/compose.deploy",{"composeId":compose_id,"title":args.sha})
            try:
                deployment_id=wait_for_deployment(client,f"/api/deployment.allByCompose?composeId={compose_id}",old_deployments,args.sha)
            except DeploymentFailedTerminal:
                deployment_failed_terminal=True
                raise
        except Exception as deployment_error:
            if mutation_possible and (not deploy_may_be_active or deployment_failed_terminal):
                try:
                    environment=client.request("GET",f"/api/environment.one?environmentId={args.environment_id}")
                    failed=[]
                    for item in review_composes(environment):
                        metadata=review_metadata(item)
                        if item.get("name")==name and metadata.get("repository")==args.base_repo and metadata.get("pr")==args.pr and metadata.get("sha")==args.sha: failed.append(item)
                    if len(failed)>1: raise ValueError("failed review ownership is ambiguous")
                    if failed: revoke_review_compose(client,failed[0]["composeId"],name,args.dns_suffix,args.sha if deployment_failed_terminal else None)
                except Exception as cleanup_error:
                    raise RuntimeError("review deployment failed and exact-owned cleanup failed") from deployment_error
            elif mutation_possible:
                print("warning: review deployment state is uncertain; preserving exact-owned reconciliation anchor", file=sys.stderr)
            raise
        print(json.dumps({"name":name,"composeId":compose_id,"deploymentId":deployment_id},separators=(",",":"))); return 0
    if args.command in ("count-reviews", "list-reviews"):
        environment=client.request("GET",f"/api/environment.one?environmentId={args.environment_id}")
        found=[]
        for item in review_composes(environment):
            metadata=review_metadata(item)
            if metadata.get("repository")==args.base_repo and isinstance(metadata.get("pr"),int) and (args.command != "count-reviews" or args.exclude_pr is None or metadata["pr"] != args.exclude_pr): found.append({"pr":metadata["pr"],"sha":metadata.get("sha"),"createdAt":item.get("createdAt"),"composeId":item.get("composeId")})
        print(len(found) if args.command=="count-reviews" else json.dumps(found,separators=(",",":"))); return 0
    name=review_name(args.pr)
    environment=client.request("GET",f"/api/environment.one?environmentId={args.environment_id}")
    matches=[]
    for item in review_composes(environment):
        metadata=review_metadata(item)
        if item.get("name")==name and item.get("composeId")==args.expected_compose_id and metadata.get("repository")==args.base_repo and metadata.get("pr")==args.pr and metadata.get("sha")==args.expected_sha: matches.append(item)
    if len(matches)!=1: raise ValueError("review ownership is missing or ambiguous")
    item=matches[0]
    revoke_review_compose(client,item["composeId"],name,args.dns_suffix,args.expected_sha)
    if args.command == "revoke-review":
        print(json.dumps({"revoked":name})); return 0
    delete_review_record(client,item["composeId"],args.environment_id)
    print(json.dumps({"deleted":name})); return 0

if __name__ == "__main__":
    try: raise SystemExit(main())
    except (KeyError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr); raise SystemExit(2)
