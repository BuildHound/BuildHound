import re
import stat
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]


class ReviewRegressionPolicyTest(unittest.TestCase):
    def read(self, path: str) -> str:
        return (ROOT / path).read_text()

    def test_long_lived_tls_uses_hetzner_dns_challenge_resolver(self):
        routers = {
            "deploy/dokploy/stack.yaml": "buildhound-prod",
            "deploy/dokploy/staging-stack.yaml": "buildhound-staging",
        }
        for path, router in routers.items():
            stack = self.read(path)
            with self.subTest(path=path):
                self.assertIn(
                    f"traefik.http.routers.{router}.tls.certresolver="
                    "letsencrypt-dns-hetzner",
                    stack,
                )
                self.assertNotIn(
                    f"traefik.http.routers.{router}.tls.certresolver=letsencrypt\n",
                    stack,
                )

    def test_long_lived_traefik_object_names_are_disjoint(self):
        # Traefik router/middleware/service names are swarm-global. The
        # staging and production stacks coexist on one swarm; identical
        # names collide and 404 BOTH dashboards (first prod anchor,
        # 2026-07-17). Review stacks are exempt: their names carry the
        # interpolated ${BUILDHOUND_REVIEW_PROVIDER_ID} prefix.
        # [^.]+ (not [a-z0-9-]+): a name with uppercase or underscores must
        # still be captured, or a colliding name silently drops out of both
        # sets and the test loses its detection power (PR #69 infra review).
        pattern = re.compile(
            r"traefik\.http\.(?:routers|middlewares|services)\.([^.]+)\."
        )
        names = {
            path: set(pattern.findall(self.read(path)))
            for path in (
                "deploy/dokploy/stack.yaml",
                "deploy/dokploy/staging-stack.yaml",
            )
        }
        prod = names["deploy/dokploy/stack.yaml"]
        staging = names["deploy/dokploy/staging-stack.yaml"]
        self.assertTrue(prod, "no traefik object names found in stack.yaml")
        self.assertTrue(
            staging, "no traefik object names found in staging-stack.yaml"
        )
        self.assertFalse(
            prod & staging,
            f"traefik object names shared between prod and staging: {prod & staging}",
        )

    def test_long_lived_router_middleware_references_resolve(self):
        # A partial rename that updates a middleware definition but not the
        # router's middlewares= list (or vice versa) silently detaches rate
        # limiting / the robots header at runtime while the disjointness
        # test still passes (PR #69 reviews). Pin reference integrity.
        for path in (
            "deploy/dokploy/stack.yaml",
            "deploy/dokploy/staging-stack.yaml",
        ):
            stack = self.read(path)
            defined = set(
                re.findall(r"traefik\.http\.middlewares\.([^.]+)\.", stack)
            )
            referenced = set()
            for refs in re.findall(
                r"traefik\.http\.routers\.[^.]+\.middlewares=([^\n]+)", stack
            ):
                referenced.update(ref.strip() for ref in refs.split(","))
            with self.subTest(path=path):
                self.assertTrue(referenced, f"no middleware references in {path}")
                self.assertEqual(
                    referenced,
                    defined,
                    f"router middlewares= list and middleware definitions "
                    f"disagree in {path}",
                )

    def test_secret_modes_use_unambiguous_octal_literals(self):
        # Docker's YAML 1.2 loader parses a bare leading-zero int (0400) as
        # DECIMAL 400 = 0o620 — group-writable, so libpq ignores the pgpass
        # file entirely (first prod anchor, 2026-07-17). Only the explicit
        # 0o form (or a decimal) is parse-safe.
        for path in sorted(
            (ROOT / "deploy/dokploy").glob("*.yaml"),
        ):
            stack = path.read_text()
            with self.subTest(path=path.name):
                self.assertNotRegex(
                    stack,
                    r"mode:\s*0[0-9]",
                    "bare leading-zero mode literal parses as decimal under "
                    "YAML 1.2 — use the 0o form",
                )
        self.assertEqual(
            self.read("deploy/dokploy/stack.yaml").count("mode: 0o400"), 2
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
                # The server is multi-network (private + ingress); Traefik
                # v3's swarm provider skips services whose reachable network
                # it cannot determine (routes 404) — the ingress network must
                # be pinned explicitly, and never via the docker.network
                # variant (plan 088 live verification, staging run
                # 29431369681).
                self.assertIn(
                    "traefik.swarm.network=${DOKPLOY_INGRESS_NETWORK}", server
                )
                self.assertNotIn("traefik.docker.network", stack)
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
                    r"mode: 0o400",
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
        workflow = self.read(".github/workflows/deploy.yml")
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

    def test_staging_release_verification_proves_site_response_and_dashboard_link(self):
        verify = self.read("deploy/dokploy/verify-release.sh")
        deploy = self.read(".github/workflows/deploy.yml")
        staging = deploy[deploy.index("  deploy-staging:") : deploy.index("  deploy-production:")]
        self.assertIn('site_headers=$(mktemp)', verify)
        self.assertIn('site_body=$(mktemp)', verify)
        self.assertIn("--dump-header \"$site_headers\" --output \"$site_body\"", verify)
        self.assertIn('test "$site_status" = 200', verify)
        self.assertIn('attributes.get("href") == sys.argv[2]', verify)
        self.assertIn('BUILDHOUND_EXPECT_NOINDEX', verify)
        self.assertIn('tolower($0) ~ /^x-robots-tag:/', verify)
        self.assertIn('value == "noindex, nofollow"', verify)
        self.assertNotIn("IGNORECASE", verify)
        self.assertIn("count=0; exact=0", verify)
        self.assertIn('BUILDHOUND_SITE_URL/robots.txt', verify)
        self.assertIn('test "$robots_status" = 200', verify)
        self.assertIn("printf 'User-agent: *\\nDisallow: /\\n' | cmp -s", verify)
        self.assertIn("BUILDHOUND_EXPECT_NOINDEX: true", staging)
        self.assertIn('curl -fsS "$BUILDHOUND_SITE_URL/" | grep -q', verify)
        retry = verify.index("verify_staging_site()")
        complete = verify.index('test "$site_retry_ok" = true', retry)
        ingest = verify.index("ingest_ok=false", complete)
        self.assertIn("for _ in $(seq 1 20)", verify[retry:complete])
        self.assertIn("sleep 15", verify[retry:complete])
        self.assertLess(retry, complete)
        self.assertLess(complete, ingest)

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
        build_server = workflow.index("- name: Build review server without registry credentials")
        build_site = workflow.index("- name: Build review site without registry credentials")
        login = workflow.index("- uses: docker/login-action@v3")
        publish = workflow.index("name: Publish exact review images and record push digests")
        deploy = workflow.index("- name: Deploy and verify trusted review manifest")
        self.assertLess(source_checkout, build_server)
        self.assertLess(build_server, build_site)
        self.assertLess(build_site, login)
        self.assertLess(login, publish)
        self.assertLess(publish, deploy)
        self.assertIn("review-source/site/Dockerfile", workflow[build_site:login])
        self.assertIn(
            "${{ env.IMAGE_REGISTRY_PREFIX }}/buildhound-site:pr-"
            "${{ env.REVIEW_PR }}-${{ env.REVIEW_SHA }}",
            workflow[build_site:login],
        )
        self.assertIn("persist-credentials: false", workflow[:build_server])
        self.assertIn('docker push "$image" >"$log" || return 1', workflow[publish:deploy])
        self.assertIn('site="$IMAGE_REGISTRY_PREFIX/buildhound-site:pr-$PR-$SHA"', workflow[publish:deploy])
        self.assertIn('site_digest=$(push_digest "$site"', workflow[publish:deploy])
        self.assertIn("site:{image:$siteImage,digest:$siteDigest}", workflow[publish:deploy])
        self.assertIn("runId:$runId", workflow[publish:deploy])
        deploy_body = workflow[deploy:]
        self.assertIn("site=$(jq -er .images.site.image", deploy_body)
        self.assertIn("site_digest=$(jq -er .images.site.digest", deploy_body)
        self.assertIn('--site-image "$site@$site_digest"', deploy_body)
        self.assertNotIn("docker buildx imagetools inspect", workflow)

    def test_review_site_probe_precedes_dashboard_ingest_and_attestation(self):
        workflow = self.read(".github/workflows/review-environment.yml")
        site_probe = workflow.index('status=$(curl "${common[@]}" --dump-header /tmp/review-site-headers')
        site_status = workflow.index('test "$status" = 200', site_probe)
        site_noindex = workflow.index('value == "noindex, nofollow"', site_status)
        site_link = workflow.index("review site dashboard link does not match", site_noindex)
        dashboard_probe = workflow.index(
            'status=$(curl "${common[@]}" --dump-header /tmp/review-dashboard-headers',
            site_link,
        )
        ingest = workflow.index('"$dashboard_url/v1/builds"', dashboard_probe)
        read = workflow.index('"$dashboard_url/v1/builds/$build_id"', ingest)
        attestation = workflow.index("name: review-attestation", read)
        self.assertLess(site_probe, site_status)
        self.assertLess(site_status, site_noindex)
        self.assertLess(site_noindex, site_link)
        self.assertLess(site_link, dashboard_probe)
        self.assertLess(site_noindex, dashboard_probe)
        self.assertLess(dashboard_probe, ingest)
        self.assertLess(ingest, read)
        self.assertLess(read, attestation)

    def test_review_site_probe_rejects_duplicate_conflicting_robots_headers(self):
        workflow = self.read(".github/workflows/review-environment.yml")
        match = re.search(
            r"review-site-headers \| awk '([^']+)'",
            workflow,
        )
        self.assertIsNotNone(match)
        awk_program = match.group(1)
        self.assertIn(
            "END { print count \":\" exact }')\" = '1:1'",
            workflow,
        )

        def classify(headers: str) -> str:
            return subprocess.run(
                ["awk", awk_program],
                input=headers,
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip()

        self.assertEqual(
            classify("X-Robots-Tag: noindex, nofollow\n"),
            "1:1",
        )
        self.assertEqual(
            classify("x-robots-tag: noindex, nofollow\n"),
            "1:1",
        )
        self.assertEqual(
            classify(
                "X-Robots-Tag: noindex, nofollow\n"
                "X-Robots-Tag: index, follow\n"
            ),
            "2:1",
        )

    def test_review_smoke_uses_derived_hosts(self):
        workflow = self.read(".github/workflows/review-environment.yml")
        self.assertIn('site_host="$review_name.$DNS_SUFFIX"', workflow)
        self.assertIn('dashboard_host="$review_name.dashboard.$DNS_SUFFIX"', workflow)
        self.assertNotIn('dashboard_host="dashboard-$review_name.$DNS_SUFFIX"', workflow)

    def test_workflows_scope_dokploy_configuration(self):
        for path in (
            ".github/workflows/deploy.yml",
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
        deploy = self.read(".github/workflows/deploy.yml")
        self.assertIn('current-release-id --compose-id "$COMPOSE_ID"', deploy)

        for path, step_name in (
            (".github/workflows/deploy.yml", "- name: Explicit Dokploy deploy"),
            (".github/workflows/review-environment.yml", "- name: Deploy and verify trusted review manifest"),
        ):
            with self.subTest(integration_scope=path):
                step = self.read(path).split(step_name, 1)[1].split("      - ", 1)[0]
                self.assertIn("${{ secrets.DOKPLOY_GIT_PROVIDER_ID }}", step)
                self.assertIn("${{ secrets.DOKPLOY_REGISTRY_ID }}", step)

    def test_promotion_chain_is_one_gated_workflow(self):
        workflow = self.read(".github/workflows/deploy.yml")
        # Same-run trust model (plan 090): staging and production are jobs of
        # one workflow gated by GitHub environments; production consumes the
        # identical digest outputs staging just proved.
        self.assertIn("environment: staging", workflow)
        self.assertIn("environment: production", workflow)
        self.assertIn("needs: [publish, resolve_dispatch, deploy-staging]", workflow)
        self.assertIn("needs.deploy-staging.result == 'success'", workflow)
        # Per-job concurrency groups; never one shared group (a run waiting on
        # prod approval holds its group).
        self.assertIn(
            "concurrency: {group: deploy-staging, queue: max, cancel-in-progress: false}",
            workflow,
        )
        self.assertIn(
            "concurrency: {group: deploy-production, queue: max, cancel-in-progress: false}",
            workflow,
        )
        # The qualify gate is ported in full: exactly one merged same-repo PR
        # into the default branch carrying the label; direct pushes never
        # deploy.
        self.assertIn(".merged_at != null and .merge_commit_sha == $sha and", workflow)
        self.assertIn(
            ".head.repo.full_name == $repository and .base.repo.full_name == $repository and",
            workflow,
        )
        self.assertIn('.base.ref == $base', workflow)
        self.assertIn("grep -Fx deploy-review", workflow)
        self.assertIn('commits/$head_sha/statuses?per_page=100', workflow)
        self.assertIn('context "buildhound/review-deployed/pr-$pr"', workflow)
        self.assertIn('sort_by(.updated_at, .id) | last', workflow)
        self.assertIn('issues/$pr/events?per_page=100', workflow)
        self.assertIn('.event == "labeled" and .label.name == "deploy-review"', workflow)
        self.assertIn('.event == "reopened"', workflow)
        self.assertIn('sort_by(.created_at, .id) | last', workflow)
        self.assertIn('review_request_event_id=$(jq -er', workflow)
        self.assertIn('.updated_at >= $cutoff', workflow)
        self.assertIn('$proof.reviewRequestEventId == $review_request_event_id', workflow)
        self.assertIn("test \"$state\" = success", workflow)
        self.assertIn("test \"$creator\" = 'github-actions[bot]'", workflow)
        self.assertIn('actions/runs/$run_id', workflow)
        self.assertIn('.event == "pull_request_target"', workflow)
        self.assertIn('actions/workflows/review-environment.yml', workflow)
        self.assertIn('.workflow_id == $workflow_id', workflow)
        self.assertIn('echo "deploy=false" >> "$GITHUB_OUTPUT"', workflow)
        self.assertIn("needs.qualify.outputs.deploy == 'true'", workflow)
        self.assertIn(
            "permissions: {contents: read, pull-requests: read, actions: read, "
            "statuses: read, issues: read}",
            workflow,
        )
        # Role -> manifest binding lives solely in job wiring (the plan-090
        # replacement for the schema-3 per-role checksum recheck).
        staging_job = workflow.index("  deploy-staging:")
        production_job = workflow.index("  deploy-production:")
        staging_body = workflow[staging_job:production_job]
        production_body = workflow[production_job:]
        self.assertIn("--app-role staging", staging_body)
        self.assertIn('--site-application-id "$SITE_APPLICATION_ID"', staging_body)
        self.assertIn('--site-url "$BUILDHOUND_SITE_URL"', staging_body)
        self.assertIn('--site-dashboard-url "$BUILDHOUND_DASHBOARD_URL"', staging_body)
        self.assertIn("--site-noindex true", staging_body)
        self.assertIn("--manifest /tmp/release/staging-stack.yaml", staging_body)
        self.assertNotIn("--manifest /tmp/release/stack.yaml", staging_body)
        self.assertIn("--app-role prod", production_body)
        self.assertIn("--manifest /tmp/release/stack.yaml", production_body)
        self.assertNotIn("--manifest /tmp/release/staging-stack.yaml", production_body)
        self.assertIn(
            "cp release.json candidate/deploy/dokploy/staging-stack.yaml candidate/deploy/dokploy/volume-guard.sh /tmp/release/",
            staging_body,
        )
        self.assertIn(
            "cp release.json candidate/deploy/dokploy/stack.yaml candidate/deploy/dokploy/volume-guard.sh /tmp/release/",
            production_body,
        )
        # Staging bootstrap (plan 088 semantics carried over) stays gated to
        # automatic pushes and fails closed without the manual anchor.
        detector = workflow.index(
            'state=$(bash deploy/dokploy/dokploy.sh staging-bootstrap-state'
        )
        self.assertLess(staging_job, detector)
        self.assertLess(detector, production_job)
        self.assertIn('[ "$bootstrap" != true ] && [ "$MODE" = automatic ]', staging_body)
        self.assertIn('echo "bootstrap=$bootstrap" >> "$GITHUB_OUTPUT"', staging_body)
        self.assertIn("BOOTSTRAP: ${{ steps.backup.outputs.bootstrap }}", staging_body)
        self.assertIn('require-manual-current --compose-id "$COMPOSE_ID"', staging_body)
        # Production has no automatic bootstrap: dispatch-only flag, manual
        # anchor validation, operator-chosen backup object, and an explicit
        # rollback-compatibility attestation.
        self.assertNotIn("staging-bootstrap-state", production_body)
        self.assertIn('require-manual-current --compose-id "$COMPOSE_ID"', production_body)
        self.assertIn('test "$ROLLBACK_COMPATIBLE" = true', production_body)
        self.assertIn('select-backup.sh --object "$BACKUP_OBJECT"', production_body)
        # Staging has a provisioned separate Docker-image Application: it is
        # mandatory, uses the exact BOM site image through the delivery client,
        # and its deployment evidence plus public-site smoke cannot be skipped.
        self.assertIn('SITE_APPLICATION_ID: ${{ secrets.DOKPLOY_SITE_APPLICATION_ID }}', staging_body)
        self.assertIn('--site-application-id "$SITE_APPLICATION_ID"', staging_body)
        self.assertIn(
            "jq -e '.siteDeploymentId | type == \"string\" and length > 0' deployment-evidence.json >/dev/null",
            staging_body,
        )
        self.assertIn(
            'name: "staging-deployment-evidence-${{ github.run_attempt }}"',
            staging_body,
        )
        self.assertIn('BUILDHOUND_SITE_URL: ${{ vars.BUILDHOUND_SITE_URL }}', staging_body)
        self.assertNotIn("BUILDHOUND_SKIP_SITE_DEPLOY", staging_body)
        self.assertNotIn("--skip-site", staging_body)
        self.assertNotIn("BUILDHOUND_SKIP_SITE_CHECKS", staging_body)
        # Production retains the existing emergency skip wiring unchanged.
        self.assertIn("SKIP_SITE: ${{ vars.BUILDHOUND_SKIP_SITE_DEPLOY }}", production_body)
        self.assertIn("site+=(--skip-site)", production_body)
        self.assertIn(
            "BUILDHOUND_SKIP_SITE_CHECKS: ${{ vars.BUILDHOUND_SKIP_SITE_DEPLOY }}",
            production_body,
        )
        self.assertNotIn("--evidence-file", production_body)
        self.assertNotIn("production-deployment-evidence", production_body)
        # Dispatch-supplied identities are never trusted as-is (plan 090 §4):
        # provenance from this repo's deploy workflow on main, AND the
        # attestation's source commit must equal the dispatched sha (GHCR
        # tags are mutable), AND the signer workflow is pinned.
        self.assertIn("gh attestation verify", workflow)
        self.assertIn('--repo "$GITHUB_REPOSITORY"', workflow)
        self.assertIn("--source-ref refs/heads/main", workflow)
        self.assertIn(
            '--signer-workflow "$GITHUB_REPOSITORY/.github/workflows/deploy.yml"',
            workflow,
        )
        self.assertIn("attestation source commit does not match", workflow)
        # Both jobs keep controls at the trusted workflow revision and check
        # candidate material out separately, so historical rollback commits
        # need not understand current delivery-client flags.
        self.assertEqual(
            workflow.count("ref: ${{ github.sha }}"),
            2,
        )
        self.assertEqual(workflow.count("ref: ${{ env.SOURCE_COMMIT }}"), 2)
        self.assertEqual(workflow.count("path: candidate"), 2)
        self.assertEqual(workflow.count("candidate/deploy/dokploy/stack.yaml"), 3)
        self.assertEqual(workflow.count("candidate/deploy/dokploy/staging-stack.yaml"), 3)
        self.assertEqual(
            workflow.count("candidate/buildhound-server/src/main/resources/db/migration"),
            2,
        )
        self.assertEqual(
            workflow.count("current-source-commit --compose-id"), 2
        )
        self.assertEqual(
            workflow.count("rollback requires the compatibility attestation"), 2
        )
        # The retired trust chain must not resurface.
        self.assertNotIn("workflow_run", workflow)
        self.assertNotIn("staging-attestation", workflow)
        # Promotion reintroduces only the run-scoped review proof; it must
        # not revive the retired staging or cross-workflow lineage chain.
        self.assertIn("review-attestation", workflow)
        self.assertNotIn("schema:2", workflow)
        self.assertNotIn("proven-release-id", workflow)
        # Least-privilege carries over; no checkout persists credentials.
        self.assertIn("permissions: {contents: read}", workflow.split("jobs:", 1)[0])
        self.assertIn(
            "permissions: {contents: read, packages: write, id-token: write, attestations: write}",
            workflow,
        )
        self.assertNotIn("persist-credentials: true", workflow)
        self.assertEqual(
            workflow.count("persist-credentials: false"),
            workflow.count("actions/checkout@"),
        )

    def test_pr_38_legacy_review_proof_is_a_one_shot_strict_exception(self):
        workflow = self.read(".github/workflows/deploy.yml")
        legacy_keys = (
            '["attemptId","composeId","deploymentId","headSha","pr",'
            '"repository","runId","schema","serverImage","siteImage"]'
        )
        modern_keys = (
            '["attemptId","composeId","deploymentId","headSha","pr",'
            '"repository","reviewRequestEventId","runAttempt","runId",'
            '"schema","serverDigest","serverImage","siteDigest","siteImage"]'
        )
        self.assertIn('if [ "$pr" = 38 ] && [ "$head_branch" = codex/public-site-compose ]; then', workflow)
        self.assertIn("branch-pinned exception after this rollout reaches", workflow)
        self.assertIn('if $legacy_bootstrap == "true" then', workflow)
        self.assertIn(legacy_keys, workflow)
        self.assertIn(modern_keys, workflow)
        # The old payload has no runAttempt field, so current-attempt binding
        # must be reconstructed from the protected run ID + attempt number.
        self.assertIn('$proof.attemptId == ($run_id + "." + ($run_attempt | tostring))', workflow)
        self.assertIn('.created_at > $cutoff', workflow)
        self.assertIn('.event == "pull_request_target"', workflow)
        self.assertIn('.conclusion == "success"', workflow)
        self.assertIn('.workflow_id == $workflow_id', workflow)
        self.assertIn('capture("^(?<image>.+)@(?<digest>sha256:[0-9a-f]{64})$")', workflow)
        self.assertIn('($registry + "/buildhound-server")', workflow)
        self.assertIn('($registry + "/buildhound-site")', workflow)

    def test_dispatch_uses_trusted_controls_and_candidate_release_inputs(self):
        workflow = self.read(".github/workflows/deploy.yml")
        staging = workflow[workflow.index("  deploy-staging:") : workflow.index("  deploy-production:")]
        production = workflow[workflow.index("  deploy-production:") :]
        for job in (staging, production):
            with self.subTest(job=job[:40]):
                trusted = job.index("Check out trusted delivery controls")
                candidate = job.index("Check out candidate release inputs")
                render = job.index("Render trusted release inputs")
                deploy = job.index("Explicit Dokploy deploy")
                self.assertLess(trusted, candidate)
                self.assertLess(candidate, render)
                self.assertLess(render, deploy)
                self.assertIn("ref: ${{ github.sha }}", job[trusted:candidate])
                self.assertIn("ref: ${{ env.SOURCE_COMMIT }}", job[candidate:render])
                self.assertIn("path: candidate", job[candidate:render])
                self.assertIn("candidate/deploy/dokploy", job[render:deploy])
                # The release client itself remains outside candidate/.
                self.assertIn("bash deploy/dokploy/dokploy.sh deploy-release", job[deploy:])
                self.assertNotIn("bash candidate/deploy/dokploy/dokploy.sh", job)

    def test_stale_review_success_cannot_cross_relabel_or_reopen_cutoff(self):
        def proof_is_current(events, attested_event_id, status_updated_at):
            requests = [
                event
                for event in events
                if (
                    event["event"] == "labeled"
                    and event.get("label", {}).get("name") == "deploy-review"
                )
                or event["event"] == "reopened"
            ]
            if not requests:
                return False
            latest = max(requests, key=lambda event: (event["created_at"], event["id"]))
            return (
                attested_event_id == str(latest["id"])
                and status_updated_at >= latest["created_at"]
            )

        first_label = {
            "event": "labeled",
            "id": 101,
            "label": {"name": "deploy-review"},
            "created_at": "2026-07-17T09:00:00Z",
        }
        relabel = {
            "event": "labeled",
            "id": 102,
            "label": {"name": "deploy-review"},
            "created_at": "2026-07-17T11:00:00Z",
        }
        reopened = {
            "event": "reopened",
            "id": 103,
            "created_at": "2026-07-17T12:00:00Z",
        }
        # Even if an old run posts success after the newer request timestamp,
        # its unique lifecycle ID cannot cross the relabel/reopen boundary.
        delayed_success = "2026-07-17T13:00:00Z"
        self.assertFalse(
            proof_is_current([first_label, relabel], "101", delayed_success)
        )
        self.assertFalse(
            proof_is_current([first_label, reopened], "101", delayed_success)
        )
        self.assertTrue(
            proof_is_current(
                [first_label, relabel, reopened],
                "103",
                delayed_success,
            )
        )

    def test_review_success_drives_auto_staging_and_manual_production(self):
        review = self.read(".github/workflows/review-environment.yml")
        deploy = self.read(".github/workflows/deploy.yml")

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
        self.assertIn("serverDigest:$serverDigest", review[review_smoke:review_proof])
        self.assertIn("siteDigest:$siteDigest", review[review_smoke:review_proof])
        self.assertIn("runAttempt:$runAttempt", review[review_smoke:review_proof])
        self.assertIn(
            "reviewRequestEventId:$reviewRequestEventId",
            review[review_smoke:review_proof],
        )
        self.assertIn(
            "review_request_event_id: ${{ steps.pr.outputs.review_request_event_id }}",
            review,
        )
        self.assertIn(
            'REVIEW_REQUEST_EVENT_ID: "${{ needs.review.outputs.review_request_event_id }}"',
            review[review_status:],
        )
        self.assertIn(
            "$proof.reviewRequestEventId == $reviewRequestEventId",
            review[review_status:],
        )
        proof_keys = (
            '["attemptId","composeId","deploymentId","headSha","pr",'
            '"repository","reviewRequestEventId","runAttempt","runId",'
            '"schema","serverDigest","serverImage","siteDigest","siteImage"]'
        )
        self.assertIn(proof_keys, review)
        self.assertIn(proof_keys, deploy)
        self.assertIn('issues/$PR/events?per_page=100', review)
        self.assertIn('sort_by(.created_at, .id) | last.id', review)
        self.assertIn(
            'context="buildhound/review-deployed/pr-$PR"', review[review_status:]
        )
        self.assertIn("statuses/$SHA", review[review_status:])
        self.assertIn("Record pending exact-SHA review deployment", review)
        self.assertIn("Record failed exact-SHA review deployment", review)
        self.assertIn("state=pending", review)
        self.assertIn("state=failure", review)

        # The promotion chain triggers on push to main (label-qualified) plus
        # workflow_dispatch for redeploy/rollback — no cross-workflow chain.
        self.assertIn("push:", deploy)
        self.assertIn("branches: [main]", deploy)
        self.assertIn("workflow_dispatch:", deploy)
        self.assertIn("inputs.bootstrap_bom", deploy)
        self.assertIn("backup_object", deploy)
        self.assertIn("rollback_compatibility_attested", deploy)
        self.assertIn("queue: max", deploy)
        self.assertIn('gh run download "$run_id"', deploy)
        self.assertIn('actions/workflows/review-environment.yml', deploy)
        self.assertIn('.workflow_id == $workflow_id', deploy)
        self.assertIn('.head_branch == $branch', deploy)
        # Digests flow as same-run job outputs only.
        self.assertIn("server_image: ${{ steps.digests.outputs.server_image }}", deploy)
        self.assertIn("needs.publish.outputs.server_image", deploy)
        self.assertIn("needs.resolve_dispatch.outputs.server_image", deploy)

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
        deploy = self.read(".github/workflows/deploy.yml")
        stack = self.read("deploy/dokploy/stack.yaml")
        staging_stack = self.read("deploy/dokploy/staging-stack.yaml")
        db_image = self.read("deploy/dokploy/db/Dockerfile")
        self.assertIn(
            "cp release.json deploy/dokploy/stack.yaml deploy/dokploy/staging-stack.yaml "
            "deploy/dokploy/volume-guard.sh release-artifact/",
            deploy,
        )
        self.assertIn("--migrations-dir buildhound-server/src/main/resources/db/migration", deploy)
        self.assertNotIn("--migration-id", deploy)
        self.assertIn('--volume-guard /tmp/release/volume-guard.sh', deploy)
        self.assertIn("provenance: true", deploy)
        self.assertIn("sbom: true", deploy)
        self.assertIn("actions/attest-build-provenance", deploy)
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
        # Plan 089: both cleanup paths are thin fast-path calls into the same
        # idempotent converge entrypoint the cron runs — no bespoke per-PR
        # scrub/retire choreography in workflow YAML.
        self.assertIn("deploy/dokploy/reconcile-reviews.sh", failed_cleanup)
        self.assertIn(
            'TTL_HOURS: "${{ vars.BUILDHOUND_REVIEW_TTL_HOURS }}"', failed_cleanup
        )
        self.assertNotIn("scrub-review", failed_cleanup)
        self.assertNotIn("retire-review", failed_cleanup)

        post_success_cleanup = review_job.split(
            "- name: Delete superseded exact-owned review images", 1
        )[1].split("- name: Converge review environments", 1)[0]
        self.assertIn("steps.deploy.outcome == 'success'", post_success_cleanup)
        self.assertIn("if ! deploy/dokploy/delete-review-images.sh; then", post_success_cleanup)
        self.assertIn("::warning::Superseded review-image cleanup failed", post_success_cleanup)

        retire_step = review.split(
            "- name: Converge review environments (close/unlabel fast path)", 1
        )[1].split("  reconcile_failed_review:", 1)[0]
        self.assertIn("if: env.REVIEW_ACTION == 'retire'", retire_step)
        self.assertIn("deploy/dokploy/reconcile-reviews.sh", retire_step)

        # The converge script keeps scrub -> image cleanup -> retire ordering.
        converge_scrub = reconcile.index("scrub-review")
        converge_images = reconcile.index("delete-review-images.sh")
        converge_retire = reconcile.index("retire-review")
        self.assertLess(converge_scrub, converge_images)
        self.assertLess(converge_images, converge_retire)

    def test_reconciler_is_executable_and_fail_closed(self):
        path = ROOT / "deploy/dokploy/reconcile-reviews.sh"
        reconciler = path.read_text()
        self.assertTrue(path.stat().st_mode & stat.S_IXUSR)
        self.assertIn("must be a positive base-10 integer", reconciler)
        self.assertIn("GitHub PR lookup failed; preserving review", reconciler)
        self.assertIn("later reviews will still be reconciled", reconciler)

    def test_delivery_uses_shell_client_without_git_provider_checkout(self):
        paths = (
            ".github/workflows/deploy.yml",
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
