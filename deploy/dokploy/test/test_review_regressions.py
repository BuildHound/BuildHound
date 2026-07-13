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

    def test_backup_gate_uses_only_completed_final_objects(self):
        backup = self.read("deploy/dokploy/backup/backup-loop.sh")
        workflow = self.read(".github/workflows/deploy-release.yml")
        self.assertIn("started-at=$started_at", backup)
        self.assertIn('staged_key="$key.partial"', backup)
        self.assertIn('"s3://$S3_BUCKET/$staged_key" \\\n    "s3://$S3_BUCKET/$key"', backup)
        self.assertNotIn("s3api copy-object", backup)
        self.assertIn("--metadata-directive REPLACE", backup)
        self.assertIn("completed-at=$completed_at,buildhound-backup-complete=true", backup)
        self.assertIn('.Metadata["started-at"]', workflow)
        self.assertIn('.Metadata["buildhound-backup-complete"]', workflow)
        self.assertIn('test "$started_epoch" -le "$modified_epoch"', workflow)

    def test_restore_requires_s3_location(self):
        guard = next(
            line
            for line in self.read("deploy/dokploy/backup/restore.sh").splitlines()
            if line.startswith(": ")
        )
        self.assertIn('"${S3_ENDPOINT:?}"', guard)
        self.assertIn('"${S3_BUCKET:?}"', guard)

    def test_restore_validates_archive_before_transactional_restore(self):
        restore = self.read("deploy/dokploy/backup/restore.sh")
        head = restore.index("s3api head-object")
        marker = restore.index('[ "$complete" = true ]')
        download = restore.index("s3api get-object")
        pinned_version = restore.index('--version-id "$version_id"')
        decrypt = restore.index('-o "$archive" "$encrypted"')
        validate = restore.index('pg_restore --list "$archive"')
        apply = restore.index("pg_restore --exit-on-error --single-transaction")
        self.assertLess(head, marker)
        self.assertLess(marker, download)
        self.assertLess(download, pinned_version)
        self.assertLess(download, decrypt)
        self.assertLess(decrypt, validate)
        self.assertLess(validate, apply)
        self.assertNotIn("age -d -i \"$AGE_KEY_FILE\" | pg_restore", restore)

    def test_release_verification_uses_a_fresh_build_id(self):
        verify = self.read("deploy/dokploy/verify-release.sh")
        self.assertIn("uuid.uuid4()", verify)
        self.assertIn(".buildId = $build_id", verify)
        self.assertIn('--data-binary "@$request_payload"', verify)
        self.assertIn('v1/builds/$build_id', verify)

    def test_release_verification_validates_exact_https_origins_before_curl(self):
        verify = self.read("deploy/dokploy/verify-release.sh")
        validation = verify.index("from urllib.parse import urlsplit")
        first_curl = verify.index("curl -fsS")
        self.assertLess(validation, first_curl)
        for rejected_part in (
            'parsed.scheme != "https"',
            "parsed.username is not None",
            'parsed.path != ""',
            'parsed.query != ""',
            'parsed.fragment != ""',
        ):
            self.assertIn(rejected_part, verify[validation:first_curl])

    def test_review_digest_resolution_authenticates_to_ghcr(self):
        workflow = self.read(".github/workflows/review-environment.yml")
        login = workflow.index("- uses: docker/login-action@v3")
        inspect = workflow.index("docker buildx imagetools inspect")
        self.assertLess(login, inspect)
        self.assertIn("if: env.REVIEW_ACTION == 'deploy'", workflow[login:inspect])

    def test_review_smoke_uses_derived_hosts(self):
        workflow = self.read(".github/workflows/review-environment.yml")
        self.assertIn('site_host="$review_name.$DNS_SUFFIX"', workflow)
        self.assertIn('dashboard_host="$review_name.dashboard.$DNS_SUFFIX"', workflow)
        self.assertNotIn('dashboard_host="dashboard-$review_name.$DNS_SUFFIX"', workflow)

    def test_workflows_scope_dokploy_configuration(self):
        for path in (
            ".github/workflows/deploy-release.yml",
            ".github/workflows/review-environment.yml",
            ".github/workflows/reconcile-reviews.yml",
        ):
            with self.subTest(path=path):
                workflow = self.read(path)
                self.assertIn("${{ vars.DOKPLOY_URL }}", workflow)
                self.assertIn("${{ secrets.DOKPLOY_TOKEN }}", workflow)
                self.assertNotIn("${{ secrets.DOKPLOY_URL }}", workflow)
                self.assertNotIn("DOKPLOY_URL='${{ vars.DOKPLOY_URL }}'", workflow)
                self.assertNotIn("DOKPLOY_TOKEN='${{ secrets.DOKPLOY_TOKEN }}'", workflow)
        deploy = self.read(".github/workflows/deploy-release.yml")
        self.assertIn('current-release-id --compose-id "$COMPOSE_ID"', deploy)

    def test_production_bootstrap_requires_staging_proof_manual_current_and_attestation(self):
        workflow = self.read(".github/workflows/deploy-release.yml")
        self.assertIn("permissions: {contents: read, actions: read, deployments: read}", workflow)
        self.assertIn("environment: ${{ inputs.target }}", workflow)
        production_gate = workflow.index('if [ "$TARGET" = production ]')
        staging_proof = workflow.index('test "$(jq -r .releaseId /tmp/staging/staging.json)" = "$id"')
        backup_gate = workflow.index("if [ '${{ inputs.bootstrap_bom }}' = true ]")
        explicit_deploy = workflow.index("python3 deploy/dokploy/dokploy.py deploy-release")
        self.assertLess(production_gate, staging_proof)
        self.assertLess(staging_proof, backup_gate)
        self.assertLess(backup_gate, explicit_deploy)
        bootstrap = workflow[backup_gate:explicit_deploy]
        self.assertIn('buildhound-backup-complete', workflow[:backup_gate])
        self.assertIn('buildhound-release-id"]\' /tmp/backup-head.json)" = manual', bootstrap)
        self.assertIn('require-manual-current --compose-id "$COMPOSE_ID"', bootstrap)
        self.assertIn("if [ '${{ inputs.target }}' = production ]", bootstrap)
        self.assertIn("test '${{ inputs.rollback_compatibility_attested }}' = true", bootstrap)
        self.assertIn("bootstrap+=(--bootstrap-manual-current)", workflow[backup_gate:])
        self.assertIn('"${bootstrap[@]}"', workflow[backup_gate:])

    def test_release_artifact_binds_stack_guard_and_db_wrapper(self):
        publish = self.read(".github/workflows/publish-deploy-images.yml")
        deploy = self.read(".github/workflows/deploy-release.yml")
        stack = self.read("deploy/dokploy/stack.yaml")
        db_image = self.read("deploy/dokploy/db/Dockerfile")
        self.assertIn("matrix: {image: [server, site, backup, db]}", publish)
        self.assertIn("cp release.json deploy/dokploy/stack.yaml deploy/dokploy/volume-guard.sh release-artifact/", publish)
        self.assertIn("--migrations-dir buildhound-server/src/main/resources/db/migration", publish)
        self.assertNotIn("--migration-id", publish)
        self.assertIn("--manifest /tmp/release/stack.yaml --volume-guard /tmp/release/volume-guard.sh", deploy)
        self.assertIn("${BUILDHOUND_POSTGRES_IMAGE}", stack)
        self.assertNotIn("file: ./volume-guard.sh", stack)
        self.assertIn("COPY --chmod=0555 deploy/dokploy/volume-guard.sh", db_image)

    def test_review_image_cleanup_is_exact_owned_and_enabled(self):
        cleanup = self.read("deploy/dokploy/delete-review-images.sh")
        review = self.read(".github/workflows/review-environment.yml")
        reconcile = self.read("deploy/dokploy/reconcile-reviews.sh")
        self.assertIn('tags == [$tag]', cleanup)
        self.assertIn('actions/workflows/$workflow_id/runs', cleanup)
        self.assertIn("packages: write", review)
        self.assertIn("delete-review-images.sh", review)
        self.assertIn("delete-review-images.sh", reconcile)

        delete_step = review.split("- name: Delete exact owned review", 1)[1]
        first_recheck = delete_step.index('data=$(gh api "repos/${GITHUB_REPOSITORY}/pulls/$PR")')
        image_cleanup = delete_step.index('REVIEW_PR="$PR" deploy/dokploy/delete-review-images.sh')
        final_recheck = delete_step.index('data=$(gh api "repos/${GITHUB_REPOSITORY}/pulls/$PR")', image_cleanup)
        compose_cleanup = delete_step.index("python3 deploy/dokploy/dokploy.py delete-review")
        self.assertLess(first_recheck, image_cleanup)
        self.assertLess(image_cleanup, compose_cleanup)
        self.assertLess(image_cleanup, final_recheck)
        self.assertLess(final_recheck, compose_cleanup)

    def test_reconciler_is_executable_and_fail_closed(self):
        path = ROOT / "deploy/dokploy/reconcile-reviews.sh"
        reconciler = path.read_text()
        self.assertTrue(path.stat().st_mode & stat.S_IXUSR)
        self.assertIn("must be a positive base-10 integer", reconciler)
        self.assertIn("GitHub PR lookup failed; preserving review", reconciler)
        self.assertIn("later reviews will still be reconciled", reconciler)


if __name__ == "__main__":
    unittest.main()
