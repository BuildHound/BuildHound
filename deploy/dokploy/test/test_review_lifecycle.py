import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]


class ReviewPolicyTest(unittest.TestCase):
    def test_trusted_stack_hardens_both_public_services_and_both_routers(self):
        stack = (ROOT / "deploy/dokploy/review-stack.yaml").read_text()
        site = stack.split("  site:", 1)[1].split("  server:", 1)[0]
        server = stack.split("  server:", 1)[1].split("  db:", 1)[0]
        self.assertIn('user: "101:101"', site)
        self.assertIn('user: "10001:10001"', server)
        for service in (site, server):
            self.assertIn("cap_drop: [ALL]", service)
            self.assertIn('security_opt: ["no-new-privileges:true"]', service)
            self.assertIn(".middlewares=${BUILDHOUND_REVIEW_PROVIDER_ID}-noindex", service)
            self.assertIn(".tls=true", service)
            self.assertIn(
                "traefik.swarm.network=${DOKPLOY_REVIEW_INGRESS_NETWORK}", service
            )
        self.assertNotIn(".tls.certresolver=", stack)
        self.assertNotIn(".tls.domains", stack)

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
        self.assertIn('--ingress-network "$INGRESS_NETWORK"', workflow)
        self.assertIn('--exclude-pr "$PR"', workflow)
        self.assertIn("review-site-headers", workflow)
        self.assertIn("review-dashboard-headers", workflow)
        self.assertNotIn('echo "$BUILDHOUND_REVIEW_TOKEN"', workflow)
        smoke = workflow.index('jq -e --arg id "$build_id"')
        status = workflow.index(
            "- name: Validate attestation and record successful exact-SHA review"
        )
        self.assertLess(smoke, status)
        self.assertIn('context="buildhound/review-deployed/pr-$PR"', workflow[status:])
        self.assertIn('statuses/$SHA', workflow[status:])
        self.assertIn("environment: review-cleanup", reconciler)
        self.assertIn(
            'test "$GITHUB_REF" = "refs/heads/$DEFAULT_BRANCH"', reconciler
        )


if __name__ == "__main__":
    unittest.main()
