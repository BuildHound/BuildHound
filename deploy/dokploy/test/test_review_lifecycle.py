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

            def __init__(self, *_):
                pass

            def request(self, method, path, body=None):
                calls.append((method, path, body))
                if path.startswith("/api/environment.one"):
                    return {"compose": [{
                        "name": "review-buildhound-buildhound-42",
                        "composeId": "compose-42",
                        "description": json.dumps({"repository": "BuildHound/BuildHound", "pr": 42, "sha": "b" * 40}),
                    }]}
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
                    {"composeId": "target", "description": json.dumps({"repository": "BuildHound/BuildHound", "pr": 42})},
                    {"composeId": "other", "description": json.dumps({"repository": "BuildHound/BuildHound", "pr": 43})},
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
        name = dokploy.review_name("BuildHound/BuildHound", 42)
        self.assertEqual(
            dokploy.review_hosts(name, "reviews.example.test"),
            (f"{name}.reviews.example.test", f"dashboard-{name}.reviews.example.test"),
        )
        for suffix in ("reviews.example.test/path", "Reviews.example.test", "localhost", "reviews..test"):
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                dokploy.review_hosts(name, suffix)
        with self.assertRaises(ValueError):
            dokploy.review_hosts("r" * 60, "reviews.example.test")

    def test_trusted_stack_hardens_both_public_services_and_both_routers(self):
        stack = (ROOT / "deploy/dokploy/review-stack.yaml").read_text()
        site = stack.split("  site:", 1)[1].split("  server:", 1)[0]
        server = stack.split("  server:", 1)[1].split("  db:", 1)[0]
        self.assertIn('user: "101:101"', site)
        self.assertIn('user: "10001:10001"', server)
        for service in (site, server):
            self.assertIn("cap_drop: [ALL]", service)
            self.assertIn('security_opt: ["no-new-privileges:true"]', service)
            self.assertRegex(service, r"routers\.\$\{BUILDHOUND_REVIEW_NAME\}-(?:site|server)\.middlewares=\$\{BUILDHOUND_REVIEW_NAME\}-noindex")

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
        self.assertIn("gh api", workflow[final_check:mutation])
        self.assertIn("grep -Fx deploy-review", workflow[final_check:mutation])
        self.assertIn('--exclude-pr "$PR"', workflow)
        self.assertIn('status=$(curl "${common[@]}" --output /dev/null --request POST', workflow)
        self.assertIn("jq -e --arg id \"$build_id\" '.buildId == $id' /tmp/review-read.json", workflow)
        self.assertNotIn('echo "$BUILDHOUND_REVIEW_TOKEN"', workflow)
        self.assertIn("packages: write", workflow)
        self.assertIn("delete-review-images.sh", workflow)
        self.assertIn('test "$review_count" -le 1', workflow)
        self.assertIn('[[ "$deployed_sha" =~ ^[0-9a-f]{40}$ ]]', workflow)
        self.assertIn("environment: review-cleanup", reconciler)
        self.assertIn('test "$GITHUB_REF" = "refs/heads/$DEFAULT_BRANCH"', reconciler)


if __name__ == "__main__":
    unittest.main()
