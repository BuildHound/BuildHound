import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]


class ReviewPolicyTest(unittest.TestCase):
    def test_trusted_stack_hardens_both_public_services_and_both_routers(self):
        stack = (ROOT / "deploy/dokploy/review-stack.yaml").read_text()
        site = stack.split("  site:", 1)[1].split("  server:", 1)[0]
        server = stack.split("  server:", 1)[1].split("  db:", 1)[0]
        # The review site remains an image-only service in the trusted Stack;
        # site/compose.yml is deliberately not a Swarm template.
        self.assertIn("image: ${BUILDHOUND_SITE_IMAGE}", site)
        self.assertIn(
            "BUILDHOUND_SITE_DASHBOARD_URL: https://${BUILDHOUND_REVIEW_DASHBOARD_HOST}",
            site,
        )
        self.assertIn("BUILDHOUND_SITE_NOINDEX: \"true\"", site)
        self.assertIn("BUILDHOUND_SITE_HOST: ${BUILDHOUND_REVIEW_SITE_HOST}", site)
        self.assertIn("BUILDHOUND_SITE_ROBOTS: noindex, nofollow", site)
        self.assertIn("constraints: [node.labels.role == review]", site)
        self.assertIn(
            "traefik.http.routers.${BUILDHOUND_REVIEW_PROVIDER_ID}-site.rule="
            "Host(`${BUILDHOUND_REVIEW_SITE_HOST}`)",
            site,
        )
        self.assertIn(
            "traefik.http.services.${BUILDHOUND_REVIEW_PROVIDER_ID}-site."
            "loadbalancer.server.port=8080",
            site,
        )
        self.assertIn('user: "101:101"', site)
        self.assertIn('user: "10001:10001"', server)
        for service in (site, server):
            self.assertIn("cap_drop: [ALL]", service)
            self.assertIn('security_opt: ["no-new-privileges:true"]', service)
            self.assertIn(".middlewares=${BUILDHOUND_REVIEW_PROVIDER_ID}-noindex", service)
            self.assertIn(".tls=true", service)
            # Traefik v3's swarm provider needs the ingress-reachable network
            # pinned per routed service (plan 088); the label must point at
            # the injected isolated network, never at a shared one.
            self.assertIn(
                "traefik.swarm.network=${BUILDHOUND_REVIEW_NETWORK}", service
            )
        self.assertNotIn(".tls.certresolver=", stack)
        self.assertNotIn(".tls.domains", stack)
        self.assertNotIn("networks:", stack)
        self.assertNotIn("traefik.docker.network", stack)
        self.assertNotIn("dokploy-network", stack)
        self.assertNotIn("external: true", stack)
        self.assertNotIn("DOKPLOY_REVIEW_INGRESS_NETWORK", stack)
        self.assertEqual(stack.count("constraints: [node.labels.role == review]"), 3)
        self.assertNotIn("node.labels.buildhound.traefik", stack)

    def test_workflow_uses_label_driven_lifecycle_and_protected_base_code(self):
        workflow = (ROOT / ".github/workflows/review-environment.yml").read_text()
        reconciler = (ROOT / ".github/workflows/reconcile-reviews.yml").read_text()
        self.assertIn("pull_request_target:", workflow)
        self.assertIn(
            "types: [labeled, synchronize, reopened, closed, unlabeled]", workflow
        )
        self.assertIn("branches: [main]", workflow)
        self.assertNotIn("workflow_dispatch:", workflow)
        self.assertIn(
            "github.event.action == 'labeled' && github.event.label.name == 'deploy-review'",
            workflow,
        )
        self.assertIn(
            "(github.event.action == 'synchronize' || github.event.action == 'reopened')",
            workflow,
        )
        self.assertIn(
            "contains(github.event.pull_request.labels.*.name, 'deploy-review')",
            workflow,
        )
        self.assertIn(
            "github.event.action == 'unlabeled' && github.event.label.name == 'deploy-review'",
            workflow,
        )
        self.assertIn("github.event.action == 'closed'", workflow)
        self.assertIn('ref: "${{ github.sha }}"', workflow)
        self.assertIn("environment: ${{", workflow)
        self.assertIn("'review-cleanup' || 'review'", workflow)
        final_check = workflow.index(
            "# This is the final external check before the trusted client mutates Dokploy."
        )
        mutation = workflow.index(
            "result=$(bash deploy/dokploy/dokploy.sh deploy-review", final_check
        )
        tls_preflight = workflow.index("- name: Verify pre-warmed wildcard TLS")
        self.assertLess(tls_preflight, final_check)
        tls = workflow[tls_preflight:final_check]
        self.assertIn("certificate-check.$DNS_SUFFIX|*.$DNS_SUFFIX", tls)
        self.assertIn(
            "certificate-check.dashboard.$DNS_SUFFIX|*.dashboard.$DNS_SUFFIX", tls
        )
        self.assertIn('-verify_hostname "$host" -verify_return_error', tls)
        self.assertIn('grep -Fx "DNS:$wildcard"', tls)
        self.assertIn("gh api", workflow[final_check:mutation])
        self.assertIn("grep -Fx deploy-review", workflow[final_check:mutation])
        self.assertNotIn("--ingress-network", workflow)
        self.assertNotIn("DOKPLOY_REVIEW_INGRESS_NETWORK", workflow)
        self.assertNotIn("BUILDHOUND_REVIEW_NETWORK_ISOLATION_VERIFIED", workflow)
        self.assertNotIn("BUILDHOUND_REVIEW_TRAEFIK_API_INSECURE_DISABLED", workflow)
        self.assertNotIn("TRAEFIK_API_INSECURE_DISABLED", workflow)
        self.assertIn('--exclude-pr "$PR"', workflow)
        self.assertIn("review-site-headers", workflow)
        self.assertIn("review-dashboard-headers", workflow)
        self.assertNotIn('echo "$BUILDHOUND_REVIEW_TOKEN"', workflow)
        smoke = workflow.index('jq -e --arg id "$build_id"')
        status = workflow.index(
            "- name: Validate attestation and record successful exact-SHA review"
        )
        self.assertLess(smoke, status)
        self.assertIn("--retry-max-time 300", workflow[mutation:status])
        self.assertIn('context="buildhound/review-deployed/pr-$PR"', workflow[status:])
        self.assertIn('statuses/$SHA', workflow[status:])
        self.assertIn("environment: review-cleanup", reconciler)
        self.assertIn(
            'test "$GITHUB_REF" = "refs/heads/$DEFAULT_BRANCH"', reconciler
        )
        # The reconciler is the only cleanup mechanism; failures must surface
        # (open marker issue) and recovery must self-heal (close it). The
        # simulate_failure knob fails before any Dokploy access.
        self.assertIn("if: failure()", reconciler)
        self.assertIn("if: success()", reconciler)
        self.assertIn("Review-environment converge is failing", reconciler)
        self.assertIn("issues: write", reconciler)
        simulate = reconciler.index("Simulate a converge failure")
        converge_call = reconciler.index("deploy/dokploy/reconcile-reviews.sh")
        dokploy_env = reconciler.index("DOKPLOY_TOKEN:")
        self.assertLess(simulate, converge_call)
        self.assertLess(simulate, dokploy_env)
        publisher = (ROOT / ".github/workflows/deploy.yml").read_text()
        self.assertIn("group: review-environment-global", publisher)

        client = (ROOT / "deploy/dokploy/lib/review.sh").read_text()
        self.assertIn("settings.getDokployVersion", client)
        self.assertIn("_review_supported_dokploy_version=v0.29.12", client)
        self.assertNotIn("docker.getStackContainersByAppName", client)
        cli = (ROOT / "deploy/dokploy/dokploy.sh").read_text()
        cli_review = cli.index("cmd_deploy_review()")
        cli_gate = cli.index("_review_require_supported_dokploy_version", cli_review)
        cli_integrations = cli.index("dokploy_require_integrations", cli_gate)
        self.assertLess(cli_gate, cli_integrations)
        update = client.index("_review_update_body()")
        verify = client.index("_review_require_exact_compose()", update)
        deploy = client.index("dokploy_api POST compose.deploy", verify)
        self.assertIn("isolatedDeployment: true", client[update:verify])
        self.assertLess(update, verify)
        self.assertLess(verify, deploy)

        scrub = client.index("scrub_review()")
        scrub_update = client.index("dokploy_api POST compose.update", scrub)
        scrub_deploy = client.index("dokploy_api POST compose.deploy", scrub_update)
        self.assertLess(scrub_update, scrub_deploy)
        # Plan 089: the cleanup path carries no queue-drain or anchor
        # re-verification choreography; convergence covers those failures.
        self.assertNotIn("compose.cleanQueues", client)
        self.assertNotIn("_review_require_materialized_anchor", client)
        # Scrub preserves the original activation stamp; only retire_review
        # (below it) stamps retiredAt for the host GC retention window.
        retire = client.index("retire_review()", scrub)
        self.assertNotIn("date -u '+%Y-%m-%dT%H:%M:%SZ'", client[scrub:retire])
        self.assertIn("retiredAt:$retiredAt", client[retire:])

        anchor = (ROOT / "deploy/dokploy/review-anchor.yaml").read_text()
        self.assertIn("@sha256:", anchor)
        self.assertIn("replicas: 0", anchor)
        self.assertIn("constraints: [node.labels.role == review]", anchor)
        self.assertNotIn("environment:", anchor)

    def test_review_lifecycle_never_mutates_dokploy_outside_its_api(self):
        paths = (
            "deploy/dokploy/lib/review.sh",
            "deploy/dokploy/dokploy.sh",
            "deploy/dokploy/reconcile-reviews.sh",
            ".github/workflows/review-environment.yml",
        )
        forbidden = (
            "compose.delete",
            "docker network ",
            "docker service ",
            "docker stack ",
            "ssh ",
        )
        for path in paths:
            content = (ROOT / path).read_text()
            with self.subTest(path=path):
                for value in forbidden:
                    self.assertNotIn(value, content)
        # Plan 089: retirement runs only through the converge entrypoint; the
        # workflow's cleanup paths are thin fast-path calls into it.
        workflow = (ROOT / ".github/workflows/review-environment.yml").read_text()
        self.assertNotIn("retire-review", workflow)
        self.assertNotIn("scrub-review", workflow)
        self.assertEqual(workflow.count("deploy/dokploy/reconcile-reviews.sh"), 2)
        converge = (ROOT / "deploy/dokploy/reconcile-reviews.sh").read_text()
        self.assertIn("retire-review", converge)
        self.assertIn("scrub-review", converge)
        # Environment/name scoping (plan 089 exit criterion): converge only
        # ever acts through list-reviews' owned records inside the dedicated
        # review environment, and its summary logs mrNN names, never ids.
        self.assertIn('--environment-id "$ENVIRONMENT_ID"', converge)
        self.assertIn("list-reviews", converge)
        self.assertIn("converge summary:", converge)
        self.assertNotIn("composeId=", converge.split("converge summary:")[1])


if __name__ == "__main__":
    unittest.main()
