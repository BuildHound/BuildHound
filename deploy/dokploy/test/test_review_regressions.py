import re
import stat
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]


class ReviewRegressionPolicyTest(unittest.TestCase):
    def read(self, path: str) -> str:
        return (ROOT / path).read_text()

    def test_long_lived_tls_uses_hetzner_dns_challenge_resolver(self):
        for path in ("deploy/dokploy/stack.yaml", "deploy/dokploy/staging-stack.yaml"):
            stack = self.read(path)
            with self.subTest(path=path):
                self.assertIn(
                    "traefik.http.routers.buildhound.tls.certresolver="
                    "letsencrypt-dns-hetzner",
                    stack,
                )
                self.assertNotIn(
                    "traefik.http.routers.buildhound.tls.certresolver=letsencrypt\n",
                    stack,
                )

    def test_long_lived_services_use_environment_specific_placement(self):
        for path in ("deploy/dokploy/stack.yaml", "deploy/dokploy/staging-stack.yaml"):
            stack = self.read(path)
            server = stack.split("  server:", 1)[1].split("  db:", 1)[0]
            db = stack.split("  db:", 1)[1].split("  backup:", 1)[0]
            backup = stack.split("  backup:", 1)[1].split("\nnetworks:", 1)[0]
            with self.subTest(path=path):
                self.assertIn("node.labels.role == ${BUILDHOUND_APP_ROLE}", server)
                self.assertNotIn("node.labels.buildhound.traefik", stack)
                for service in (db, backup):
                    self.assertIn("node.labels.role == db", service)
                    self.assertIn("node.id == ${BUILDHOUND_DB_NODE_ID}", service)

    def test_stack_tmpfs_uses_converter_facing_volume_mounts(self):
        stack = self.read("deploy/dokploy/stack.yaml")
        staging = self.read("deploy/dokploy/staging-stack.yaml")
        review = self.read("deploy/dokploy/review-stack.yaml")
        services = {
            "server": (
                stack.split("  server:", 1)[1].split("  db:", 1)[0],
                67108864,
            ),
            "backup": (
                stack.split("  backup:", 1)[1].split("\nnetworks:", 1)[0],
                67108864,
            ),
            "staging-server": (
                staging.split("  server:", 1)[1].split("  db:", 1)[0],
                67108864,
            ),
            "staging-backup": (
                staging.split("  backup:", 1)[1].split("\nnetworks:", 1)[0],
                67108864,
            ),
            "review-site": (
                review.split("  site:", 1)[1].split("  server:", 1)[0],
                33554432,
            ),
            "review-server": (
                review.split("  server:", 1)[1].split("  db:", 1)[0],
                67108864,
            ),
        }
        for name, (service, size) in services.items():
            with self.subTest(service=name):
                self.assertIn("\n    read_only: true", service)
                self.assertNotIn("\n    tmpfs:", service)
                self.assertRegex(
                    service,
                    re.compile(
                        rf"\n    volumes:\s*\n"
                        rf"      - type: tmpfs\s*\n"
                        rf"        target: /tmp\s*\n"
                        rf"        tmpfs:\s*\n"
                        rf"          size: {size}\s*(?:\n|$)",
                        re.MULTILINE,
                    ),
                )

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

    def test_staging_uses_env_credentials_without_swarm_secrets(self):
        staging = self.read("deploy/dokploy/staging-stack.yaml")
        production = self.read("deploy/dokploy/stack.yaml")
        staging_db = staging.split("  db:", 1)[1].split("  backup:", 1)[0]
        staging_backup = staging.split("  backup:", 1)[1].split("\nnetworks:", 1)[0]
        production_db = production.split("  db:", 1)[1].split("  backup:", 1)[0]
        production_backup = production.split("  backup:", 1)[1].split("\nnetworks:", 1)[0]

        self.assertNotIn("\nsecrets:", staging)
        self.assertNotIn("\n    secrets:", staging)
        self.assertIn("POSTGRES_PASSWORD: ${BUILDHOUND_DB_PASSWORD}", staging_db)
        self.assertIn("PGPASSWORD: ${BUILDHOUND_DB_PASSWORD}", staging_backup)
        self.assertIn("AWS_ACCESS_KEY_ID: ${BUILDHOUND_S3_ACCESS_KEY_ID}", staging_backup)
        self.assertIn("AWS_SECRET_ACCESS_KEY: ${BUILDHOUND_S3_SECRET_ACCESS_KEY}", staging_backup)
        self.assertIn("AWS_DEFAULT_REGION: ${BUILDHOUND_S3_REGION}", staging_backup)
        self.assertNotIn("POSTGRES_PASSWORD_FILE", staging)
        self.assertNotIn("PGPASSFILE", staging)
        self.assertNotIn("S3_CREDENTIALS_FILE", staging)

        self.assertIn("POSTGRES_PASSWORD_FILE: /run/secrets/db_password", production_db)
        self.assertIn("PGPASSFILE: /run/secrets/pgpass", production_backup)
        self.assertIn("S3_CREDENTIALS_FILE: /run/secrets/s3_credentials", production_backup)
        self.assertNotIn("AWS_ACCESS_KEY_ID:", production)
        self.assertNotIn("AWS_SECRET_ACCESS_KEY:", production)

    def test_backup_gate_uses_only_completed_final_objects(self):
        backup = self.read("deploy/dokploy/backup/backup-loop.sh")
        selector = self.read("deploy/dokploy/select-backup.sh")
        workflow = self.read(".github/workflows/deploy-release.yml")
        self.assertIn("started-at=$started_at", backup)
        self.assertIn('staged_key="$key.partial"', backup)
        self.assertIn('"s3://$S3_BUCKET/$staged_key" \\\n    "s3://$S3_BUCKET/$key"', backup)
        self.assertNotIn("s3api copy-object", backup)
        self.assertIn("--metadata-directive REPLACE", backup)
        self.assertIn("completed-at=$completed_at,buildhound-backup-complete=true", backup)
        self.assertIn('.Metadata["started-at"]', selector)
        self.assertIn('.Metadata["completed-at"]', selector)
        self.assertIn('.Metadata["buildhound-backup-complete"] == "true"', selector)
        self.assertIn('[ "$started_epoch" -le "$completed_epoch" ]', selector)
        self.assertIn('[ "$completed_epoch" -le $((modified_epoch + 300)) ]', selector)
        self.assertIn('^backups/buildhound-[0-9]{8}T[0-9]{6}Z\\.dump\\.age$', selector)
        self.assertIn('| sort | reverse | .[0] // empty', selector)
        self.assertIn('select-backup.sh --latest --expected-release-id "$expected"', workflow)
        self.assertIn('select-backup.sh --object "$BACKUP_OBJECT"', workflow)
        self.assertIn("get-bucket-versioning", selector)
        self.assertIn('.Status == "Enabled"', selector)
        self.assertIn('.VersionId != "null"', selector)
        self.assertIn("expectedReleaseId", selector)
        self.assertIn('--expected-current-release-id "$selected_predecessor"', workflow)

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

    def test_review_deploy_uses_protected_run_scoped_digests(self):
        publisher = self.read(".github/workflows/review-images.yml")
        workflow = self.read(".github/workflows/review-environment.yml")
        self.assertIn("\n  pull_request:\n", publisher)
        self.assertIn("permissions: {contents: read}", publisher)
        self.assertNotIn("packages: write", publisher)
        self.assertNotIn("docker/login-action", publisher)
        self.assertIn("push: false", publisher)
        self.assertIn("persist-credentials: false", publisher)
        source_checkout = workflow.index("- name: Check out the exact untrusted review source")
        build = workflow.index("- name: Build review server without registry credentials")
        login = workflow.index("- uses: docker/login-action@v3")
        publish = workflow.index("name: Publish exact review images and record push digests")
        deploy = workflow.index("- name: Deploy and verify trusted review manifest")
        self.assertLess(source_checkout, build)
        self.assertLess(build, login)
        self.assertLess(login, publish)
        self.assertLess(publish, deploy)
        self.assertIn("persist-credentials: false", workflow[:build])
        self.assertIn('docker push "$image" >"$log" || return 1', workflow[publish:deploy])
        self.assertIn("runId:$runId", workflow[publish:deploy])
        self.assertNotIn("docker buildx imagetools inspect", workflow)

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

        for path, step_name in (
            (".github/workflows/deploy-release.yml", "- name: Explicit Dokploy deploy"),
            (".github/workflows/review-environment.yml", "- name: Deploy and verify trusted review manifest"),
        ):
            with self.subTest(integration_scope=path):
                step = self.read(path).split(step_name, 1)[1].split("      - ", 1)[0]
                self.assertIn("${{ secrets.DOKPLOY_GIT_PROVIDER_ID }}", step)
                self.assertIn("${{ secrets.DOKPLOY_REGISTRY_ID }}", step)

    def test_production_bootstrap_requires_staging_proof_manual_current_and_attestation(self):
        workflow = self.read(".github/workflows/deploy-release.yml")
        self.assertIn("deployments: read", workflow)
        self.assertIn("statuses: read", workflow)
        self.assertIn("environment: ${{ needs.resolve.outputs.target }}", workflow)
        production_gate = workflow.index("production)\n")
        staging_proof = workflow.index('resolve_staging_proof "$staging_run_id"')
        backup_gate = workflow.index('if [ "$bootstrap" = true ]')
        explicit_deploy = workflow.index("bash deploy/dokploy/dokploy.sh deploy-release")
        self.assertLess(production_gate, staging_proof)
        self.assertLess(staging_proof, backup_gate)
        self.assertLess(backup_gate, explicit_deploy)
        bootstrap = workflow[backup_gate:explicit_deploy]
        self.assertIn('mode=$(jq -er .mode "$workdir/staging/staging.json")', workflow[:backup_gate])
        self.assertIn('expected=manual', bootstrap)
        self.assertIn('require-manual-current --compose-id "$COMPOSE_ID"', bootstrap)
        self.assertIn('select-backup.sh --object "$BACKUP_OBJECT"', bootstrap)
        # First automatic staging deploy (plan 088): the bootstrap detector is
        # gated to staging/automatic, fails closed without the manual anchor,
        # and the deploy step consumes the step's effective bootstrap output.
        detector = workflow.index(
            'state=$(bash deploy/dokploy/dokploy.sh staging-bootstrap-state'
        )
        self.assertLess(detector, backup_gate)
        self.assertIn(
            '[ "$bootstrap" != true ] && [ "$TARGET" = staging ] && [ "$MODE" = automatic ]',
            workflow,
        )
        self.assertIn('echo "bootstrap=$bootstrap" >> "$GITHUB_OUTPUT"', workflow)
        self.assertIn("BOOTSTRAP: ${{ steps.backup.outputs.bootstrap }}", workflow)
        self.assertIn('if [ "$bootstrap_bom" = true ]; then test "$rollback_compatible" = true; fi', workflow)
        self.assertIn("bootstrap+=(--bootstrap-manual-current)", workflow[backup_gate:])
        self.assertIn('"${bootstrap[@]}"', workflow[backup_gate:])
        self.assertIn('proof+=(--proven-release-id "$VALIDATED_RELEASE_ID")', workflow)
        self.assertIn('"${proof[@]}"', workflow)
        self.assertIn('schema:2', workflow)
        self.assertIn('backup:$backup[0]', workflow)

    def test_review_success_drives_auto_staging_and_manual_production(self):
        review = self.read(".github/workflows/review-environment.yml")
        release = self.read(".github/workflows/deploy-release.yml")

        review_smoke = review.index('jq -e --arg id "$build_id"')
        review_proof = review.index("name: review-attestation")
        review_status = review.index(
            "- name: Validate attestation and record successful exact-SHA review"
        )
        self.assertLess(review_smoke, review_proof)
        self.assertLess(review_proof, review_status)
        self.assertIn("schema:1", review[review_smoke:review_proof])
        self.assertIn("headSha:$headSha", review[review_smoke:review_proof])
        self.assertIn("serverImage:$serverImage", review[review_smoke:review_proof])
        self.assertIn("siteImage:$siteImage", review[review_smoke:review_proof])
        self.assertIn(
            'context="buildhound/review-deployed/pr-$PR"', review[review_status:]
        )
        self.assertIn("statuses/$SHA", review[review_status:])

        self.assertIn("workflow_run:", release)
        self.assertIn('workflows: ["Publish deployment images"]', release)
        self.assertIn("types: [completed]", release)
        self.assertIn("branches: [main]", release)
        self.assertIn("workflow_dispatch:", release)
        self.assertIn("queue: max", release)
        self.assertIn("github.event.workflow_run", release)
        self.assertIn('context="buildhound/review-deployed/pr-$review_pr"', release)
        self.assertIn("deploy-review", release)
        self.assertIn("review-attestation", release)
        self.assertIn(
            'gh run download "$review_run_id" --repo "$GITHUB_REPOSITORY" '
            '--name review-attestation',
            release,
        )
        self.assertIn(
            'gh run download "$run_id" --repo "$GITHUB_REPOSITORY" '
            '--name staging-attestation',
            release,
        )
        self.assertIn("inputs.bootstrap_bom", release)
        self.assertIn(
            'expected_attempt_id="${review_run_id}.${run_attempt}"', release
        )
        self.assertIn('--arg attemptId "$expected_attempt_id"', release)
        self.assertIn('$proof.attemptId == $attemptId', release)
        self.assertNotIn("attemptPrefix", release)

        automatic_gate = release.index('if [ "$EVENT_NAME" = workflow_run ]')
        dispatch_gate = release.index('elif [ "$EVENT_NAME" = workflow_dispatch ]')
        production_gate = release.index("production)\n", dispatch_gate)
        explicit_deploy = release.index("bash deploy/dokploy/dokploy.sh deploy-release")
        self.assertLess(automatic_gate, dispatch_gate)
        self.assertLess(dispatch_gate, production_gate)
        self.assertIn("target=staging", release[automatic_gate:dispatch_gate])
        self.assertIn("mode=automatic", release[automatic_gate:dispatch_gate])
        self.assertNotIn("target=production", release[automatic_gate:dispatch_gate])
        self.assertIn("github.event_name", release[:explicit_deploy])
        self.assertIn("workflow_dispatch", release[:explicit_deploy])
        self.assertIn('actions/workflows/deploy-release.yml', release)
        self.assertIn('test "$(jq -r .workflow_id <<<"$run")" = "$workflow_id"', release)
        self.assertIn('test "$(jq -r .event <<<"$run")" = workflow_run', release)
        self.assertIn(
            'test "$(jq -r .head_repository.full_name <<<"$run")" = "$GITHUB_REPOSITORY"',
            release,
        )
        self.assertIn(
            'test "$(jq -r .head_sha <<<"$run")" = "$review_sha"', release
        )
        self.assertNotIn(".pull_requests[]", release)
        self.assertIn('prefix="https://github.com/${GITHUB_REPOSITORY}/actions/runs/$run_id/job/"', release)
        self.assertIn(
            'compare/${SOURCE_COMMIT}...${latest}', release
        )
        self.assertIn(
            'current-release-state --compose-id "$COMPOSE_ID"', release
        )
        self.assertIn(
            'compare/${current_source}...${SOURCE_COMMIT}', release
        )
        self.assertIn(
            'require_deployment_progress \\\n                "$selected_predecessor" "$VALIDATED_RELEASE_ID"',
            release,
        )

        self.assertIn("staging)\n              app_role=staging", release)
        self.assertIn("production)\n              app_role=prod", release)
        self.assertIn('--app-role "$app_role"', release)
        self.assertNotIn("BUILDHOUND_APP_ROLE: ${{", release)

        staging_attestation = release.index("- name: Record staging attestation")
        self.assertIn(
            "review:{runId:$reviewRun,attemptId:$reviewAttempt,pr:$reviewPr,headSha:$reviewSha}",
            release[staging_attestation:],
        )
        self.assertIn("source:{runId:$sourceRun,commit:$sourceCommit,artifact:$sourceArtifact}", release[staging_attestation:])
        self.assertIn('($proof.review | keys) == ["attemptId","headSha","pr","runId"]', release)
        self.assertIn('"$deployment_progress" "$TARGET" "$ROLLBACK_COMPATIBLE"', release)

    def test_review_cleanup_is_not_blocked_by_environment_approval(self):
        review = self.read(".github/workflows/review-environment.yml")
        reconcile = self.read(".github/workflows/reconcile-reviews.yml")
        self.assertNotIn("concurrency:", review.split("jobs:", 1)[0])
        expected = (
            "concurrency: {group: review-environment-global, queue: max, "
            "cancel-in-progress: false}"
        )
        self.assertIn(expected, review)
        self.assertIn(expected, reconcile)

    def test_release_artifact_binds_stack_guard_and_db_wrapper(self):
        publish = self.read(".github/workflows/publish-deploy-images.yml")
        deploy = self.read(".github/workflows/deploy-release.yml")
        stack = self.read("deploy/dokploy/stack.yaml")
        staging_stack = self.read("deploy/dokploy/staging-stack.yaml")
        db_image = self.read("deploy/dokploy/db/Dockerfile")
        self.assertIn("matrix: {image: [server, site, backup, db]}", publish)
        self.assertIn(
            "cp release.json deploy/dokploy/stack.yaml deploy/dokploy/staging-stack.yaml "
            "deploy/dokploy/volume-guard.sh release-artifact/",
            publish,
        )
        self.assertIn("--migrations-dir buildhound-server/src/main/resources/db/migration", publish)
        self.assertNotIn("--migration-id", publish)
        self.assertIn("manifest=/tmp/release/staging-stack.yaml", deploy)
        self.assertIn("manifest=/tmp/release/stack.yaml", deploy)
        self.assertIn('--manifest "$manifest"', deploy)
        self.assertIn("--volume-guard /tmp/release/volume-guard.sh", deploy)
        self.assertIn("${BUILDHOUND_POSTGRES_IMAGE}", stack)
        self.assertIn("${BUILDHOUND_POSTGRES_IMAGE}", staging_stack)
        self.assertNotIn("file: ./volume-guard.sh", stack)
        self.assertIn("COPY --chmod=0555 deploy/dokploy/volume-guard.sh", db_image)

    def test_review_image_cleanup_is_exact_owned_and_enabled(self):
        cleanup = self.read("deploy/dokploy/delete-review-images.sh")
        review = self.read(".github/workflows/review-environment.yml")
        reconcile = self.read("deploy/dokploy/reconcile-reviews.sh")
        self.assertIn('tags == [$tag]', cleanup)
        self.assertIn('commits/$sha/pulls', cleanup)
        self.assertIn("HEAD_REF_FORCE_PUSHED_EVENT", cleanup)
        self.assertIn("beforeCommit{oid}", cleanup)
        self.assertNotIn('actions/workflows/$workflow_id/runs', cleanup)
        self.assertIn("packages: write", review)
        self.assertIn("delete-review-images.sh", review)
        self.assertIn("delete-review-images.sh", reconcile)

        review_job, failed_cleanup = review.split("  reconcile_failed_review:", 1)
        self.assertNotIn("Reconcile failed exact-owned review", review_job)
        self.assertIn("deploy_outcome: ${{ steps.deploy_status.outputs.outcome }}", review_job)
        self.assertIn("publish_outcome: ${{ steps.deploy_status.outputs.publish_outcome }}", review_job)
        self.assertIn("- id: deploy_status", review_job)
        self.assertIn('OUTCOME: "${{ steps.deploy.outcome }}"', review_job)
        self.assertIn('PUBLISH_OUTCOME: "${{ steps.publish.outcome }}"', review_job)
        self.assertIn("if: ${{ always() }}", review_job)
        self.assertIn("needs: review", failed_cleanup)
        self.assertIn("needs.review.result == 'failure'", failed_cleanup)
        self.assertIn("needs.review.result == 'cancelled'", failed_cleanup)
        self.assertIn("needs.review.outputs.deploy_outcome == 'failure'", failed_cleanup)
        self.assertIn("needs.review.outputs.publish_outcome == 'failure'", failed_cleanup)
        self.assertIn("environment: review-cleanup", failed_cleanup)
        self.assertIn("[.[] | select(.pr == $pr)] | length", failed_cleanup)
        self.assertIn('test "$review_count" -le 1', failed_cleanup)
        self.assertNotIn(".pr == $pr and .sha == $sha", failed_cleanup)
        exact_attempt = (
            'if [ "$deployed_sha" = "$SHA" ] && '
            '[ "$attempt_id" = "$ATTEMPT_ID" ]; then'
        )
        self.assertIn(exact_attempt, failed_cleanup)
        self.assertIn('--expected-attempt-id "$attempt_id"', failed_cleanup)
        self.assertIn('keep_sha="$deployed_sha"', failed_cleanup)
        self.assertIn('KEEP_SHA="$keep_sha" REVIEW_PR="$PR"', failed_cleanup)
        self.assertIn('if [ "$cleanup_attempted_review" = true ]; then', failed_cleanup)
        sha_match = failed_cleanup.index(exact_attempt)
        compose_scrub = failed_cleanup.index("scrub-review")
        image_cleanup = failed_cleanup.index("delete-review-images.sh")
        retire_guard = failed_cleanup.index('if [ "$cleanup_attempted_review" = true ]; then')
        compose_retire = failed_cleanup.index("retire-review --base-repo")
        self.assertLess(sha_match, compose_scrub)
        self.assertLess(compose_scrub, failed_cleanup.index('keep_sha="$deployed_sha"'))
        self.assertLess(compose_scrub, image_cleanup)
        self.assertLess(image_cleanup, retire_guard)
        self.assertLess(retire_guard, compose_retire)

        post_success_cleanup = review_job.split(
            "- name: Delete superseded exact-owned review images", 1
        )[1].split("- name: Retire exact owned review", 1)[0]
        self.assertIn("steps.deploy.outcome == 'success'", post_success_cleanup)
        self.assertIn("if ! deploy/dokploy/delete-review-images.sh; then", post_success_cleanup)
        self.assertIn("::warning::Superseded review-image cleanup failed", post_success_cleanup)

        retire_step = review.split("- name: Retire exact owned review", 1)[1]
        first_recheck = retire_step.index('data=$(gh api "repos/${GITHUB_REPOSITORY}/pulls/$PR")')
        compose_scrub = retire_step.index("bash deploy/dokploy/dokploy.sh scrub-review")
        image_cleanup = retire_step.index('REVIEW_PR="$PR" deploy/dokploy/delete-review-images.sh')
        compose_retire = retire_step.index("bash deploy/dokploy/dokploy.sh retire-review")
        self.assertLess(first_recheck, compose_scrub)
        self.assertLess(compose_scrub, image_cleanup)
        self.assertLess(image_cleanup, compose_retire)

    def test_reconciler_is_executable_and_fail_closed(self):
        path = ROOT / "deploy/dokploy/reconcile-reviews.sh"
        reconciler = path.read_text()
        self.assertTrue(path.stat().st_mode & stat.S_IXUSR)
        self.assertIn("must be a positive base-10 integer", reconciler)
        self.assertIn("GitHub PR lookup failed; preserving review", reconciler)
        self.assertIn("later reviews will still be reconciled", reconciler)

    def test_delivery_uses_shell_client_without_git_provider_checkout(self):
        paths = (
            ".github/workflows/deploy-release.yml",
            ".github/workflows/publish-deploy-images.yml",
            ".github/workflows/review-environment.yml",
            "deploy/dokploy/reconcile-reviews.sh",
        )
        for path in paths:
            with self.subTest(path=path):
                content = self.read(path)
                self.assertNotIn("deploy/dokploy/dokploy.py", content)
        review_client = self.read("deploy/dokploy/lib/review.sh")
        delivery_client = self.read("deploy/dokploy/dokploy.sh")
        integrations = self.read("deploy/dokploy/lib/integrations.sh")
        self.assertIn('sourceType: "raw"', review_client)
        self.assertIn("githubId: null", review_client)
        self.assertNotIn('persisted=$(dokploy_api GET "compose.one', review_client)
        self.assertNotIn('compose=$(dokploy_api GET "compose.one', review_client)
        self.assertNotIn('result=$(dokploy_api POST compose.create', review_client)
        self.assertIn('registryId:null', delivery_client)
        self.assertIn('buildRegistryId:null', delivery_client)
        self.assertIn('rollbackRegistryId:null', delivery_client)
        self.assertIn('autoDeploy:false', delivery_client)
        self.assertIn("site Application lacks Dokploy v0.29 Docker-provider pull credentials", delivery_client)
        application_update = delivery_client.split("POST application.update", 1)[0].rsplit("body=$(jq", 1)[1]
        self.assertNotIn("username:", application_update)
        self.assertNotIn("password:", application_update)
        self.assertIn("github.githubProviders", integrations)
        self.assertIn("github.getGithubRepositories", integrations)


if __name__ == "__main__":
    unittest.main()
