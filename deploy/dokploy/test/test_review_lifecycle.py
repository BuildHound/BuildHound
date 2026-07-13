import contextlib
import importlib.util
import io
import json
import os
import sys
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).parents[3]
SPEC = importlib.util.spec_from_file_location("dokploy_review_lifecycle", ROOT / "deploy/dokploy/dokploy.py")
dokploy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(dokploy)
SHA = "a" * 40


def persisted_review(update: dict, environment_id: str = "review-environment") -> dict:
    return update | {
        "environmentId": environment_id,
        "name": "mr42",
        "appName": dokploy.review_provider_id("BuildHound/BuildHound", 42) + "-Ab12Cd",
        "serverId": None,
        "composeStatus": "idle",
        "domains": [],
        "mounts": [],
        "backups": [],
        "github": None,
        "gitlab": None,
        "bitbucket": None,
        "gitea": None,
        "server": None,
        "environment": {"environmentId": environment_id, "env": None, "project": {"env": None}},
    }


class ReviewLifecycleTest(unittest.TestCase):
    def argv(self, *extra: str) -> list[str]:
        return [
            "dokploy.py",
            "deploy-review",
            "--base-repo", "BuildHound/BuildHound",
            "--head-repo", "BuildHound/BuildHound",
            "--sha", SHA,
            "--state", "open",
            "--environment-id", "review-environment",
            "--dns-suffix", "reviews.example.test",
            "--ingress-network", "buildhound-review-ingress",
            "--pr", "42",
            "--label-present",
            "--server-image", "ghcr.io/buildhound/server@sha256:" + "1" * 64,
            "--site-image", "ghcr.io/buildhound/site@sha256:" + "2" * 64,
            *extra,
        ]

    def environment(self) -> dict[str, str]:
        return {
            "DOKPLOY_URL": "https://dokploy.example.test",
            "DOKPLOY_TOKEN": "dokploy-token",
            "BUILDHOUND_REVIEW_DB_PASSWORD": "db-password",
            "BUILDHOUND_REVIEW_TOKEN": "review-token",
        }

    def test_review_update_snapshots_and_waits_for_exact_deployment(self):
        calls = []

        class Fake:
            deployed = False
            update = None

            def __init__(self, *_):
                pass

            def request(self, method, path, body=None):
                calls.append((method, path, body))
                if path.startswith("/api/environment.one"):
                    return {"compose": [{
                        "name": "mr42",
                        "composeId": "compose-42",
                        "serverId": None,
                        "description": json.dumps({"repository": "BuildHound/BuildHound", "pr": 42, "sha": "b" * 40}),
                    }]}
                if path == "/api/compose.update":
                    Fake.update = body
                    return body
                if path.startswith("/api/compose.one"):
                    return persisted_review(Fake.update)
                if path.startswith("/api/deployment.allByCompose"):
                    old = {"deploymentId": "old-deployment", "title": "b" * 40, "status": "done"}
                    if Fake.deployed:
                        return [old, {"deploymentId": "new-deployment", "title": SHA, "status": "success"}]
                    return [old]
                if path == "/api/compose.deploy":
                    Fake.deployed = True
                return {}

        output = io.StringIO()
        with (
            mock.patch.object(dokploy, "Client", Fake),
            mock.patch.object(sys, "argv", self.argv()),
            mock.patch.dict(os.environ, self.environment(), clear=False),
            contextlib.redirect_stdout(output),
        ):
            self.assertEqual(dokploy.main(), 0)

        result = json.loads(output.getvalue())
        self.assertEqual(result["deploymentId"], "new-deployment")
        endpoints = [(method, path) for method, path, _ in calls]
        snapshot = endpoints.index(("GET", "/api/deployment.allByCompose?composeId=compose-42"))
        update = endpoints.index(("POST", "/api/compose.update"))
        deploy = endpoints.index(("POST", "/api/compose.deploy"))
        self.assertLess(snapshot, update)
        self.assertLess(update, deploy)
        self.assertGreater(len(endpoints) - 1, deploy)
        update_body = next(body for method, path, body in calls if method == "POST" and path == "/api/compose.update")
        self.assertEqual(update_body["sourceType"], "raw")
        self.assertFalse(update_body["randomize"])
        self.assertFalse(update_body["isolatedDeployment"])

    def test_failed_review_deployment_revokes_and_preserves_owned_compose(self):
        calls = []

        class Fake:
            environment_reads = 0
            update = None
            deleted = False

            def __init__(self, *_):
                pass

            def request(self, method, path, body=None):
                calls.append((method, path, body))
                if path.startswith("/api/environment.one"):
                    Fake.environment_reads += 1
                    if Fake.environment_reads == 1:
                        return {"compose": []}
                    if Fake.deleted:
                        return {"compose": []}
                    return {"compose": [{
                        "name": "mr42",
                        "composeId": "compose-42",
                        "serverId": None,
                        "description": json.dumps({"repository": "BuildHound/BuildHound", "pr": 42, "sha": SHA}),
                    }]}
                if path == "/api/compose.create":
                    return {"composeId": "compose-42"}
                if path == "/api/compose.update":
                    Fake.update = body
                    return body
                if path.startswith("/api/compose.one"):
                    return persisted_review(Fake.update)
                if path == "/api/compose.delete":
                    Fake.deleted = True
                    return {}
                if path.startswith("/api/deployment.allByCompose"):
                    return [{"deploymentId": "failed-deployment", "title": SHA, "status": "failed"}]
                return {}

        with (
            mock.patch.object(dokploy, "Client", Fake),
            mock.patch.object(dokploy, "wait_for_review_routes_gone"),
            mock.patch.object(dokploy.time, "sleep"),
            mock.patch.object(sys, "argv", self.argv()),
            mock.patch.dict(os.environ, self.environment(), clear=False),
            self.assertRaisesRegex(RuntimeError, "failed terminal"),
        ):
            dokploy.main()

        endpoints = [path for _, path, _ in calls]
        self.assertLess(endpoints.index("/api/compose.cleanQueues"), endpoints.index("/api/compose.stop"))
        self.assertLess(endpoints.index("/api/compose.deploy"), endpoints.index("/api/compose.stop"))
        self.assertNotIn("/api/compose.delete", endpoints)

    def test_uncertain_active_deployment_preserves_reconciliation_anchor(self):
        calls = []

        class Fake:
            update = None
            def __init__(self, *_): pass
            def request(self, method, path, body=None):
                calls.append((method, path, body))
                if path.startswith("/api/environment.one"): return {"compose": []}
                if path == "/api/compose.create": return {"composeId": "compose-42"}
                if path == "/api/compose.update": Fake.update = body; return body
                if path.startswith("/api/compose.one"): return persisted_review(Fake.update)
                return {}

        error = io.StringIO()
        with (
            mock.patch.object(dokploy, "Client", Fake),
            mock.patch.object(dokploy, "wait_for_deployment", side_effect=RuntimeError("Dokploy deployment did not reach success within 10 minutes")),
            mock.patch.object(sys, "argv", self.argv()),
            mock.patch.dict(os.environ, self.environment(), clear=False),
            contextlib.redirect_stderr(error),
            self.assertRaisesRegex(RuntimeError, "10 minutes"),
        ):
            dokploy.main()

        endpoints = [path for _, path, _ in calls]
        self.assertIn("/api/compose.deploy", endpoints)
        self.assertNotIn("/api/compose.stop", endpoints)
        self.assertNotIn("/api/compose.delete", endpoints)
        self.assertIn("preserving exact-owned reconciliation anchor", error.getvalue())

    def test_cleanup_preserves_compose_while_deployment_is_running(self):
        calls = []
        class Fake:
            def request(self, method, path, body=None):
                calls.append((method, path, body))
                if path.startswith("/api/compose.one"):
                    return {"composeId": "compose-42", "composeStatus": "running"}
                return {}

        with self.assertRaisesRegex(RuntimeError, "still active"):
            dokploy.revoke_review_compose(Fake(), "compose-42", "mr42", "reviews.example.test", None)
        endpoints = [path for _, path, _ in calls]
        self.assertEqual(endpoints[0], "/api/compose.cleanQueues")
        self.assertNotIn("/api/compose.stop", endpoints)
        self.assertNotIn("/api/compose.delete", endpoints)

    def test_cleanup_quiesces_a_waiting_job_without_a_deployment_record(self):
        calls = []
        class Fake:
            deleted = False
            def request(self, method, path, body=None):
                calls.append((method, path, body))
                if path.startswith("/api/deployment.allByCompose"): return []
                if path.startswith("/api/compose.one"): return {"composeId": "compose-42", "composeStatus": "idle"}
                if path == "/api/compose.delete": Fake.deleted = True; return {}
                if path.startswith("/api/environment.one"): return {"compose": [] if Fake.deleted else [{"composeId": "compose-42"}]}
                return {}

        with (
            mock.patch.object(dokploy.time, "sleep"),
            mock.patch.object(dokploy, "wait_for_review_routes_gone"),
        ):
            dokploy.revoke_review_compose(Fake(), "compose-42", "mr42", "reviews.example.test", SHA)
            dokploy.delete_review_record(Fake(), "compose-42", "review-environment")
        endpoints = [path for _, path, _ in calls]
        self.assertEqual(endpoints[:2], ["/api/compose.cleanQueues", "/api/deployment.allByCompose?composeId=compose-42"])
        self.assertEqual(endpoints.count("/api/compose.cleanQueues"), 6)
        self.assertIn("/api/compose.stop", endpoints)
        self.assertIn("/api/compose.delete", endpoints)

    def test_route_removal_requires_both_public_hosts_to_return_404(self):
        class Gone:
            def open(self, request, timeout):
                raise dokploy.urllib.error.HTTPError(request.full_url, 404, "not found", {}, None)

        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *_): return False

        class Live:
            def open(self, *_args, **_kwargs): return Response()

        with mock.patch.object(dokploy.urllib.request, "build_opener", return_value=Gone()):
            dokploy.wait_for_review_routes_gone("mr42", "reviews.example.test", timeout_seconds=0)
        with (
            mock.patch.object(dokploy.urllib.request, "build_opener", return_value=Live()),
            self.assertRaisesRegex(RuntimeError, "remained reachable"),
        ):
            dokploy.wait_for_review_routes_gone("mr42", "reviews.example.test", timeout_seconds=0)

    def test_review_api_schema_and_hidden_state_fail_closed(self):
        for value in (None, {}, {"compose": "not-a-list"}, {"compose": ["not-an-object"]}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                dokploy.review_composes(value)
        for description in (None, "[]", "not-json", json.dumps({"repository": "BuildHound/BuildHound", "pr": 42})):
            with self.subTest(description=description), self.assertRaises(ValueError):
                dokploy.review_metadata({"description": description})

        update = dokploy.review_update("compose-42", json.dumps({"repository": "BuildHound/BuildHound", "pr": 42, "sha": SHA}, separators=(",", ":")), "services: {}")
        exact = persisted_review(update)
        dokploy.require_exact_review_compose(exact, compose_id="compose-42", environment_id="review-environment", name="mr42", app_name_prefix=dokploy.review_provider_id("BuildHound/BuildHound", 42), description=update["description"], compose="services: {}")
        for key, value in (("command", "curl attacker.invalid"), ("domains", [{"host": "other.example"}]), ("serverId", "production-worker")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                dokploy.require_exact_review_compose(exact | {key: value}, compose_id="compose-42", environment_id="review-environment", name="mr42", app_name_prefix=dokploy.review_provider_id("BuildHound/BuildHound", 42), description=update["description"], compose="services: {}")
        with self.assertRaisesRegex(ValueError, "application name"):
            dokploy.require_exact_review_compose(exact | {"appName": dokploy.review_provider_id("BuildHound/BuildHound", 42)}, compose_id="compose-42", environment_id="review-environment", name="mr42", app_name_prefix=dokploy.review_provider_id("BuildHound/BuildHound", 42), description=update["description"], compose="services: {}")

    def test_deployment_wait_fails_closed_on_ambiguous_or_failed_evidence(self):
        class Fake:
            def __init__(self, items):
                self.items = items

            def request(self, *_):
                return self.items

        ambiguous = [
            {"deploymentId": "one", "title": SHA, "status": "success"},
            {"deploymentId": "two", "title": SHA, "status": "success"},
        ]
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            dokploy.wait_for_deployment(Fake(ambiguous), "/deployments", set(), SHA, timeout_seconds=0)
        with self.assertRaisesRegex(RuntimeError, "failed terminal"):
            dokploy.wait_for_deployment(
                Fake([{"deploymentId": "one", "title": SHA, "status": "failed"}]),
                "/deployments", set(), SHA, timeout_seconds=0,
            )
        with self.assertRaisesRegex(RuntimeError, "10 minutes"):
            dokploy.wait_for_deployment(Fake([]), "/deployments", set(), SHA, timeout_seconds=0)

    def test_count_excludes_target_pr_for_an_update(self):
        class Fake:
            def __init__(self, *_):
                pass

            def request(self, *_):
                return {"compose": [
                    {"composeId": "target", "description": json.dumps({"repository": "BuildHound/BuildHound", "pr": 42, "sha": SHA})},
                    {"composeId": "other", "description": json.dumps({"repository": "BuildHound/BuildHound", "pr": 43, "sha": SHA})},
                ]}

        argv = [
            "dokploy.py", "count-reviews",
            "--base-repo", "BuildHound/BuildHound",
            "--environment-id", "review-environment",
            "--exclude-pr", "42",
        ]
        output = io.StringIO()
        with (
            mock.patch.object(dokploy, "Client", Fake),
            mock.patch.object(sys, "argv", argv),
            mock.patch.dict(os.environ, self.environment(), clear=False),
            contextlib.redirect_stdout(output),
        ):
            self.assertEqual(dokploy.main(), 0)
        self.assertEqual(output.getvalue().strip(), "1")

    def test_review_hosts_reject_non_dns_input_and_overlong_labels(self):
        name = dokploy.review_name(42)
        self.assertEqual(
            dokploy.review_hosts(name, "reviews.example.test"),
            ("mr42.reviews.example.test", "mr42.dashboard.reviews.example.test"),
        )
        for suffix in ("reviews.example.test/path", "Reviews.example.test", "localhost", "reviews..test"):
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                dokploy.review_hosts(name, suffix)
        with self.assertRaises(ValueError):
            dokploy.review_hosts("r" * 64, "reviews.example.test")

    def test_every_unapproved_ingress_network_is_rejected_before_api_mutation(self):
        class Fake:
            def __init__(self, *_):
                pass

            def request(self, *_):
                raise AssertionError("Dokploy API must not be called")

        for network in ("dokploy-network", "ingress", "another-review-network"):
            with (
                self.subTest(network=network),
                mock.patch.object(dokploy, "Client", Fake),
                mock.patch.object(sys, "argv", self.argv("--ingress-network", network)),
                mock.patch.dict(os.environ, self.environment(), clear=False),
                self.assertRaisesRegex(ValueError, "unexpected review ingress network"),
            ):
                dokploy.main()

    def test_trusted_stack_hardens_both_public_services_and_both_routers(self):
        stack = (ROOT / "deploy/dokploy/review-stack.yaml").read_text()
        site = stack.split("  site:", 1)[1].split("  server:", 1)[0]
        server = stack.split("  server:", 1)[1].split("  db:", 1)[0]
        self.assertIn('user: "101:101"', site)
        self.assertIn('user: "10001:10001"', server)
        for service in (site, server):
            self.assertIn("cap_drop: [ALL]", service)
            self.assertIn('security_opt: ["no-new-privileges:true"]', service)
            self.assertRegex(service, r"routers\.\$\{BUILDHOUND_REVIEW_PROVIDER_ID\}-(?:site|server)\.middlewares=\$\{BUILDHOUND_REVIEW_PROVIDER_ID\}-noindex")
            self.assertRegex(service, r"routers\.\$\{BUILDHOUND_REVIEW_PROVIDER_ID\}-(?:site|server)\.tls=true")
            self.assertIn("traefik.swarm.network=${DOKPLOY_REVIEW_INGRESS_NETWORK}", service)
        self.assertNotIn(".tls.certresolver=", stack)
        self.assertNotIn(".tls.domains", stack)

    def test_workflow_revalidates_smokes_and_tears_down_without_pr_code(self):
        workflow = (ROOT / ".github/workflows/review-environment.yml").read_text()
        reconciler = (ROOT / ".github/workflows/reconcile-reviews.yml").read_text()
        self.assertIn("pull_request_target:", workflow)
        self.assertIn("types: [closed, unlabeled]", workflow)
        self.assertIn("github.event.label.name == 'deploy-review'", workflow)
        self.assertIn("environment: ${{ github.event_name == 'workflow_dispatch'", workflow)
        self.assertIn("'review-cleanup'", workflow)
        self.assertIn('with: {ref: "${{ github.event.repository.default_branch }}"}', workflow)
        final_check = workflow.index("# This is the final external check before the trusted client mutates Dokploy.")
        mutation = workflow.index("result=$(python3 deploy/dokploy/dokploy.py deploy-review", final_check)
        tls_preflight = workflow.index("- name: Verify pre-warmed wildcard TLS")
        self.assertLess(tls_preflight, final_check)
        self.assertIn("certificate-check.$DNS_SUFFIX|*.$DNS_SUFFIX", workflow[tls_preflight:final_check])
        self.assertIn("certificate-check.dashboard.$DNS_SUFFIX|*.dashboard.$DNS_SUFFIX", workflow[tls_preflight:final_check])
        self.assertIn('-verify_hostname "$host" -verify_return_error', workflow[tls_preflight:final_check])
        self.assertIn('grep -Fx "DNS:$wildcard"', workflow[tls_preflight:final_check])
        self.assertIn("gh api", workflow[final_check:mutation])
        self.assertIn("grep -Fx deploy-review", workflow[final_check:mutation])
        self.assertIn('DOKPLOY_REVIEW_INGRESS_NETWORK', workflow)
        self.assertIn('--ingress-network "$INGRESS_NETWORK"', workflow)
        self.assertIn('network != "buildhound-review-ingress"', workflow)
        self.assertIn('--exclude-pr "$PR"', workflow)
        self.assertIn('status=$(curl "${common[@]}" --output /dev/null --request POST', workflow)
        self.assertIn("jq -e --arg id \"$build_id\" '.buildId == $id' /tmp/review-read.json", workflow)
        self.assertIn("review-site-headers", workflow)
        self.assertIn("review-dashboard-headers", workflow)
        self.assertGreaterEqual(workflow.count("grep -Fxi 'X-Robots-Tag: noindex, nofollow'"), 2)
        self.assertNotIn('echo "$BUILDHOUND_REVIEW_TOKEN"', workflow)
        self.assertIn("packages: write", workflow)
        self.assertIn("delete-review-images.sh", workflow)
        self.assertIn("- name: Reconcile failed exact-owned review", workflow)
        self.assertNotIn("steps.deploy.outputs.compose_id != ''", workflow)
        review_job, failed_cleanup = workflow.split("  reconcile_failed_review:", 1)
        self.assertNotIn("Reconcile failed exact-owned review", review_job)
        self.assertIn("deploy_outcome: ${{ steps.deploy_status.outputs.outcome }}", review_job)
        self.assertIn("- id: deploy_status", review_job)
        self.assertIn('OUTCOME: "${{ steps.deploy.outcome }}"', review_job)
        self.assertIn("if: ${{ always() }}", review_job)
        self.assertIn("needs: review", failed_cleanup)
        self.assertIn("if: ${{ always() && needs.review.outputs.deploy_outcome == 'failure' }}", failed_cleanup)
        self.assertIn("environment: review-cleanup", failed_cleanup)
        self.assertIn("[.[] | select(.pr == $pr)] | length", failed_cleanup)
        self.assertIn('test "$review_count" -le 1', failed_cleanup)
        self.assertNotIn(".pr == $pr and .sha == $sha", failed_cleanup)
        self.assertIn('review=$(jq -cer --argjson pr "$PR"', failed_cleanup)
        self.assertIn('deployed_sha=$(jq -er .sha <<<"$review")', failed_cleanup)
        self.assertIn('if [ "$deployed_sha" = "$SHA" ]; then', failed_cleanup)
        self.assertIn('keep_sha="$deployed_sha"', failed_cleanup)
        self.assertIn('KEEP_SHA="$keep_sha" REVIEW_PR="$PR"', failed_cleanup)
        self.assertIn('if [ "$cleanup_attempted_review" = true ]; then', failed_cleanup)
        sha_match = failed_cleanup.index('if [ "$deployed_sha" = "$SHA" ]; then')
        compose_revoke = failed_cleanup.index("revoke-review")
        image_cleanup = failed_cleanup.index("delete-review-images.sh")
        delete_guard = failed_cleanup.index('if [ "$cleanup_attempted_review" = true ]; then')
        compose_delete = failed_cleanup.index("delete-review --base-repo")
        self.assertLess(sha_match, compose_revoke)
        self.assertLess(compose_revoke, failed_cleanup.index('keep_sha="$deployed_sha"'))
        self.assertLess(compose_revoke, image_cleanup)
        self.assertLess(image_cleanup, delete_guard)
        self.assertLess(delete_guard, compose_delete)
        self.assertIn('echo "compose_id=$compose_id" >> "$GITHUB_OUTPUT"', workflow)
        self.assertIn('test "$review_count" -le 1', workflow)
        self.assertIn('[[ "$deployed_sha" =~ ^[0-9a-f]{40}$ ]]', workflow)
        self.assertIn("environment: review-cleanup", reconciler)
        self.assertIn('test "$GITHUB_REF" = "refs/heads/$DEFAULT_BRANCH"', reconciler)


if __name__ == "__main__":
    unittest.main()
