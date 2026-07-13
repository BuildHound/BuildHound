import re
import stat
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]


class ReviewRegressionPolicyTest(unittest.TestCase):
    def read(self, path: str) -> str:
        return (ROOT / path).read_text()

    def test_backup_secrets_are_private_to_backup_user(self):
        stack = self.read("deploy/dokploy/stack.yaml")
        backup = stack.split("  backup:", 1)[1].split("\nnetworks:", 1)[0]
        for secret in ("pgpass", "s3_credentials"):
            self.assertRegex(
                backup,
                re.compile(
                    rf"- source: {secret}\s+"
                    rf"target: {secret}\s+"
                    r'uid: "10001"\s+'
                    r'gid: "10001"\s+'
                    r"mode: 0400",
                    re.MULTILINE,
                ),
            )

    def test_backup_gate_uses_fresh_started_at_metadata(self):
        backup = self.read("deploy/dokploy/backup/backup-loop.sh")
        workflow = self.read(".github/workflows/deploy-release.yml")
        self.assertIn("started-at=$started_at", backup)
        self.assertNotIn("completed-at", backup)
        self.assertIn('.Metadata["started-at"]', workflow)
        self.assertIn('test "$started_epoch" -le "$modified_epoch"', workflow)
        self.assertNotIn('.Metadata["completed-at"]', workflow)

    def test_restore_requires_s3_location(self):
        guard = self.read("deploy/dokploy/backup/restore.sh").splitlines()[2]
        self.assertIn('"${S3_ENDPOINT:?}"', guard)
        self.assertIn('"${S3_BUCKET:?}"', guard)

    def test_review_digest_resolution_authenticates_to_ghcr(self):
        workflow = self.read(".github/workflows/review-environment.yml")
        login = workflow.index("- uses: docker/login-action@v3")
        inspect = workflow.index("docker buildx imagetools inspect")
        self.assertLess(login, inspect)
        self.assertIn("if: inputs.action == 'deploy'", workflow[login:inspect])

    def test_deploy_script_does_not_interpolate_dokploy_secrets(self):
        workflow = self.read(".github/workflows/deploy-release.yml")
        self.assertIn("DOKPLOY_URL: ${{ secrets.DOKPLOY_URL }}", workflow)
        self.assertIn("DOKPLOY_TOKEN: ${{ secrets.DOKPLOY_TOKEN }}", workflow)
        self.assertIn('current-release-id --compose-id "$COMPOSE_ID"', workflow)
        self.assertNotIn("DOKPLOY_URL='${{ secrets.DOKPLOY_URL }}'", workflow)
        self.assertNotIn("DOKPLOY_TOKEN='${{ secrets.DOKPLOY_TOKEN }}'", workflow)

    def test_reconciler_is_executable_and_fail_closed(self):
        path = ROOT / "deploy/dokploy/reconcile-reviews.sh"
        reconciler = path.read_text()
        self.assertTrue(path.stat().st_mode & stat.S_IXUSR)
        self.assertIn("must be a positive base-10 integer", reconciler)
        self.assertIn("GitHub PR lookup failed; preserving review", reconciler)
        self.assertIn("later reviews will still be reconciled", reconciler)


if __name__ == "__main__":
    unittest.main()
