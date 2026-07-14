# Dokploy deployment

This directory is the trusted deployment boundary for plans 081–085. All committed values
are variable names or immutable public image references; operators enter domains, IDs,
credentials, recipients, schedules, and node labels through protected Dokploy/GitHub
environments. Never use verbose HTTP tracing.

## Capability gate

Set the shared GitHub Actions repository variable `DOKPLOY_URL` to the complete HTTPS
origin, for example `https://dokploy.example.com`. Keep `DOKPLOY_TOKEN` as a separate
GitHub Environment secret in `review`, `review-cleanup`, `staging`, and `production` so
each environment can use the narrowest available token. Treat repository Actions-variable
administration as part of the deployment trust boundary: changing this URL redirects where
those tokens are sent. If that administration must be less trusted, define the same
`DOKPLOY_URL` variable separately in each GitHub Environment instead.

The trust root is operational, not just YAML. Protect `main` with required pull-request
approval, required status checks, last-push approval, and admin enforcement. Configure
all four Environments for protected-`main`-only deployment and no admin bypass. Keep `review`,
`review-cleanup`, and `staging` free of human approval gates so label-driven review, teardown,
and post-merge staging remain automatic; require a production reviewer as the final human gate.
Store `DOKPLOY_TOKEN` only in each Environment; store
`DOKPLOY_GIT_PROVIDER_ID` and `DOKPLOY_REGISTRY_ID` only in `review`, `staging`, and
`production`. Do not leave repository-level copies that silently become a shared fallback.
An unset protection rule or Environment secret is a rollout blocker, not permission to use a
repository-wide credential.

The review lifecycle first requires `settings.getDokployVersion` to return exactly `v0.29.12`.
The client then targets Dokploy's documented `x-api-key` API (`compose.create`, `compose.update`,
`compose.one`, `compose.deploy`, `compose.cleanQueues`, `compose.stop`, and
Application update/deploy; checked against v0.29.12 on 2026-07-13). Before production, record the installed Dokploy version and verify in staging: Stack
`env_file`, external secret scoping, `deploy.labels`, digest pulls on every worker,
Compose/Application deploy endpoints, isolated review networking, and idempotent domain IDs.
Also verify separate least-privilege tokens, Hetzner versioning/lifecycle/noncurrent-version
behavior, and OCI artifact support for `release.json`. A failed row blocks that feature; it
does not authorize a weaker fallback. The current repository cannot truthfully pre-fill
these environment-specific results.

The public delivery entrypoint is `dokploy.sh`. It requires Bash, `curl`, `jq`, and either
`sha256sum` or `shasum`; `render-release.py` remains Python because it constructs the release
artifact rather than calling Dokploy. The shell client canonicalizes JSON and keeps API
headers, request bodies, and transport responses in per-call mode-`0700` temporary workspaces
on the ephemeral Actions runner. Long-lived credential-bearing registry, Application, and
Compose responses plus rendered Stacks are redirected into command-level private workspaces;
traps remove both workspace layers on normal exit and signals. Credential-bearing response
payloads are never emitted; machine-readable stdout contains only validated non-secret IDs and
deployment evidence.

## Long-lived stack

1. Label exactly one database node with `role=db` and set Dokploy
   `BUILDHOUND_DB_NODE_ID` to that node's immutable Swarm ID, obtained with
   `docker node ls --filter 'node.label=role=db' --format '{{.ID}}'`. From a Swarm manager,
   export the same value and run `deploy/dokploy/verify-db-node.sh` before every deployment.
   The preflight fails unless the label identifies exactly one Ready/Active node with that
   ID; both Stack services also require the ID so relabelling cannot move PGDATA or backup
   credentials. Label eligible application workers `role=staging` or `role=prod` for their
   environment, create the encrypted ingress network, PGDATA volume, and scoped external
   secrets. The release client constrains both the server Stack service and the separate site
   Application to the role derived from the trusted deployment target. Build and deploy the digest-addressed
   `deploy/dokploy/db/Dockerfile` image so the trusted volume guard is available on every
   worker; raw Dokploy delivery never depends on a checkout-local file.
2. Set `BUILDHOUND_DB_ALLOW_INIT=true` for the first deployment only. After the marker is
   written, wait for database readiness and prove authenticated restart persistence before
   removing it. The guard refuses empty, foreign, wrong-instance, and wrong-major volumes;
   even a marked-but-empty PGDATA requires explicit re-initialization.
3. Render and validate with `BUILDHOUND_APP_ROLE=staging` or
   `BUILDHOUND_APP_ROLE=prod`, `docker stack config -c deploy/dokploy/stack.yaml`, and
   `deploy/dokploy/validate-stack.sh`. The delivery workflow passes the same trusted mapping
   to `deploy-release --app-role`; deploy exact image digests explicitly.
4. Set `BUILDHOUND_ROBOTS_HEADER=noindex, nofollow` in staging and `all` in production.
   Reject `storage: IN-MEMORY` in logs. Verify authenticated DB read/write, restart
   persistence, a ceiling-sized public ingest returning 202, and a Traefik 429 response.
5. Bootstrap with `openssl rand -hex 32`, then remove bootstrap values after first boot.

The backup service streams `pg_dump --format=custom` through `age` to a non-eligible
`.partial` S3 key, then uses the AWS CLI's multipart-capable server-side copy to create the
final `.dump.age` key with an explicit completion marker and removes the staged object. Do
not replace this with the single-request `s3api copy-object`, which stops at 5 GiB. Only final objects carrying that marker
are eligible for deployment gates or restore. Only the public recipient is online. Mount
its `pgpass` secret for UID/GID 10001 with mode `0400` so libpq will accept it. Configure
bucket versioning and lifecycle to preserve at least 14 production or 7 staging recovery
points, delete abandoned `.partial` objects promptly, and delete current/noncurrent backup
versions no later than the 90-day raw-telemetry retention ceiling. Alert when the last
successful object exceeds 24 hours, a task fails, or disk
pressure rises. Production and staging use separate Hetzner projects and keys.
Every promotion gate calls `select-backup.sh`, requires bucket versioning status `Enabled`,
and rejects S3's mutable `null`/`None` version sentinels. Automatic staging polls for the
lexically newest exact final backup for the currently deployed release and fails closed if
that newest object is incomplete, stale, or belongs to another release; it never falls back
to an older object. Manual bootstrap and production validate the operator-selected exact key.

Quarterly, run `restore.sh` as UID/GID 10001 with the offline age key, S3 credentials, and
a `pgpass` mount whose mode is `0400`, restoring into a fresh, explicitly initialized
volume. Pass both the attested final key as `BACKUP_OBJECT` and its immutable evidence value
as `BACKUP_VERSION_ID`; the script heads and downloads only that exact S3 version. It decrypts
to private temporary files, validates the custom archive, and only then restores it in a
single transaction. Validate SQL, start an unrouted
current-image canary so Flyway runs, verify through the API, run the retention sweep so
expired telemetry is not resurrected, then switch routing while retaining the old volume.
Record measured RPO and RTO; the provisional acceptance targets
are 24 hours and 4 hours. Take a fresh verified backup before migrations and roll forward
if backward compatibility is not proven.

## Site and delivery

Build `site/Dockerfile`, run it non-root with read-only root plus bounded tmpfs for `/tmp`
(which contains the rendered page), then create separate production/staging Dokploy Applications and deploy
the exact digest. `BUILDHOUND_SITE_DASHBOARD_URL` must be an HTTPS origin; staging sets
noindex. `security.txt` is intentionally absent until an owner supplies a monitored contact.

`render-release.py` creates canonical schema-2 `release.json`, binding the Stack,
volume-guard, and the numerically ordered `{migration ID, source checksum}` set; the release
artifact carries the exact Stack/guard source snapshots and `dokploy.sh release-id` gives the
BOM's content digest. Delivery runs the current protected-base client,
verifies the successful main publish and snapshot checksums, and deploys the artifact's
manifest/config rather than whatever is currently in the checkout. Promotion is deliberately
ordered. Adding the `deploy-review` label automatically triggers a protected-base review of
that exact PR head without a human Environment approval gate;
`synchronize` or `reopened` redeploys while the label remains, and removing the label or closing
the PR retires the review. A successful review must pass wildcard TLS, public site/health, and
authenticated ingest/read smoke before it records exact-SHA status and a run-scoped attestation.
After a qualifying labelled PR is merged, completion of the trusted main-branch image publisher
triggers staging through `workflow_run`. Staging accepts only the matching successful review
proof, deploys one release ID, and carries that proof forward. Production is never triggered by
that chain: it is `workflow_dispatch`-only, accepts only the same staging-proven release ID via
`--proven-release-id`, and still requires protected-Environment approval plus an explicitly
selected fresh backup. The optional first-BOM staging bootstrap may also be dispatched manually.
Review mutation concurrency is acquired at the job level only after the selected Environment's
non-reviewer protection rules pass, allowing close/unlabel cleanup to proceed independently.
Review, main publication, and release deployment groups use `queue: max` so additional lifecycle
events wait instead of silently replacing an older pending run.
Because queued-run ordering is not a promotion guarantee, every schema-2 Dokploy deployment title
also records its source commit. Immediately before a staging or production mutation, delivery
proves that the candidate is an ancestor of current `main` and compares it with the currently
deployed source. Staging accepts only identical or forward movement. Production does the same by
default; moving backward requires the explicit protected rollback attestation, and diverged
history always fails closed. This prevents an out-of-order or replayed run from silently
downgrading an environment while still allowing a qualifying release to deploy when a later,
unlabelled commit has reached `main`.
The review images exercise the approved PR head; they are not promoted byte-for-byte. After
merge, trusted CI rebuilds the merge commit and the PR/merge identity binds that new BOM to the
successful source-lineage review.
Deployment evidence binds both the latest Flyway migration identity and the cumulative
ordered-history checksum. A forward deployment recomputes the candidate prefix through the
currently deployed migration and rejects any changed, removed, renamed, or inserted prior
migration before Dokploy mutation. A lower migration (or the one-time transition from
legacy evidence without a full history checksum) requires the protected
`rollback_compatibility_attested` input; same-version or forward history rewrites are always
rejected. Without proven backward compatibility, pause and roll forward.
The single `bootstrap_bom` exception accepts a fresh completed backup explicitly marked
`manual` only when Dokploy's latest successful deployment is its exact `Manual deployment`
sentinel. Staging uses it to move the verified plan-081 deployment onto its first BOM.
Production additionally requires the same staging-proven BOM, its protected Environment
approval, and `rollback_compatibility_attested`; this is the only first-production-BOM path.
Every later backup records the currently successful Compose release ID. The shell client re-reads
and requires that exact predecessor immediately before its first Dokploy mutation, closing the
gap between backup selection, lineage validation, and deployment.

Release and review Compose resources intentionally remain `sourceType: raw`. Dokploy v0.29.12
does not persist a registry ID on a Compose; the client instead resolves
`DOKPLOY_REGISTRY_ID`, verifies that its registry hostname matches the private image host, and
runs Dokploy's credential test against the exact local or remote manager selected by the
Compose to refresh that manager's Docker login. Dokploy deploys raw Stacks with
`--with-registry-auth`, so workers receive that pull authorization. Review Composes constrain
all services to `role=review`; the same manager credential preflight supplies worker pull
authorization. The separately managed site
remains a Docker-source Application. On Dokploy v0.29.12, attaching any of `registryId`,
`buildRegistryId`, or `rollbackRegistryId` makes that Application re-tag and push its source
image; this is incompatible with the protected digest reference and is not needed for pulls.
The client therefore clears and reasserts all three registry relations. The site's actual
`RegistryAuth` path reads the Application's legacy Docker-provider `username`, `password`, and
`registryUrl` fields. Configure those fields once on each staging/production site Application.
The client verifies their non-empty presence and exact registry host before any mutation,
preserves them during the image update, disables Application auto-deploy so refresh hooks cannot
bypass promotion, and reasserts both properties after deployment. In this Dokploy
version, both `registry.one` and `application.one` return stored credentials, so the protected
runner and deploy token necessarily receive those values over TLS during preflight and state
verification. The client confines those responses to trapped mode-`0700` temporary workspaces,
keeps xtrace disabled, and never prints, resends, or stores the credentials in shell variables
or GitHub secrets. Treat access to the protected Environment runner and deploy token as access
to those Dokploy credentials; this is an upstream v0.29.12 security boundary.

Before each release deployment, the client also clears and reasserts the raw Compose's custom
command, provider, auto-deploy, randomization, and isolation fields. Attached Dokploy domains,
mounts, or backups are rejected before mutation; routing, storage, and backup behavior must
come only from the release-bound Stack. Required Compose, Environment, and Project environment
values are preserved exactly and verified after the update.

Store the parent Git-provider ID in the protected Environment secret
`DOKPLOY_GIT_PROVIDER_ID` and the registry ID in `DOKPLOY_REGISTRY_ID` for `review`, `staging`,
and `production`. The Git-provider secret is Dokploy's `gitProviderId`, not its derived
`githubId`. Before mutation the client resolves the matching GitHub connection and proves it
can access `GITHUB_REPOSITORY`. It does not attach that provider to the raw Stack: doing so
would replace the protected exact manifest with a mutable branch checkout. Keep both IDs out
of committed files; the same secret names may contain different least-privilege integration
records in each protected Environment.

The PR-triggered image job is CI-only: it has content-read
permission, no registry login, and no package, Dokploy, Environment, backup, or object-store
credential. Adding `deploy-review` to a same-repository PR starts the protected-base review job;
new pushes and reopened PRs redeploy automatically while that label remains. After protected
`review` Environment rules admit the trusted-base job, it
checks out the exact same-repository PR head separately with persisted Git credentials
disabled and builds both untrusted Dockerfiles before registry login. It revalidates the PR,
head, and label, then protected workflow code applies the two fixed tags, pushes them, parses
the exact push digests, and records a run-scoped manifest binding PR, head SHA, workflow run,
image names, and digests. The deploy step consumes only those digest references and rechecks
the PR once more immediately before Dokploy mutation. A mutable SHA tag is never deployment
authority. A successful smoke records the exact-SHA
`buildhound/review-deployed/pr-<PR>` status consumed by automatic staging after merge. Removing
`deploy-review` or closing the PR selects the automatic `review-cleanup` path and retires the
exact-owned review. Configure the `review`,
`staging`, and `production` GitHub Environments to allow deployments only from protected `main`;
the protected workflows also reject execution outside the default-branch context. Every eligible
Dokploy worker must
have private-registry pull credentials through the configured
`DOKPLOY_REGISTRY_ID`. Set a fixed `MAX_ACTIVE` and set
`BUILDHOUND_REVIEW_DNS_SUFFIX` to the suffix only. PR 10 then uses
`mr10.<review DNS suffix>` for the site and `mr10.dashboard.<review DNS suffix>` for the
dashboard. DNS must resolve both `*.<review DNS suffix>` and
`*.dashboard.<review DNS suffix>` to Traefik. Each repository sharing a Traefik provider
must use a unique review DNS suffix because the intentionally short public host does not
contain repository identity. The public name remains `mr10`; Traefik identifiers use an
automatically generated repository-scoped prefix, and Dokploy appends its required
six-character suffix to that prefix for the actual Stack application name.
The client enables Dokploy's **Isolated Deployments** option through `compose.update`, reads the
Compose back, and refuses to deploy unless `isolatedDeployment` is exactly `true`. Do not create
or attach a review network through Docker/Swarm commands. The legacy
`DOKPLOY_REVIEW_INGRESS_NETWORK` and `BUILDHOUND_REVIEW_NETWORK_ISOLATION_VERIFIED` variables are
unused and may be removed from the GitHub Environments. Every deployed
review records the validated `<run ID>.<run attempt>` in both ownership metadata and its
deployment title. Failure cleanup and the scheduled reconciler act only on that exact attempt;
the reconciler validates its GitHub run evidence and retires completed non-successful attempts
that force-cancellation may leave behind. Same-SHA redeploys of an active review are rejected
before mutation because they cannot safely replace a healthy review. A retired anchor may be
reactivated at the same SHA; every activation writes a fresh TTL timestamp.
Dokploy v0.29.12 defaults Traefik's static API setting to `api.insecure: true`. Because isolated
deployment attaches standalone Traefik to every review application network, that default exposes
the unauthenticated Traefik API internally to untrusted PR containers even when port 8080 is not
published on the host. Before setting
`BUILDHOUND_REVIEW_TRAEFIK_API_INSECURE_DISABLED=true` in the protected `review` Environment,
use Dokploy's web UI or admin API to set `api.insecure: false` and preferably
`api.dashboard: false`, remove any unprotected `api@internal` router, read the full static
configuration back, and reload Traefik through Dokploy. Reset the attestation after any Dokploy
or Traefik configuration/version change. The automatic review token must remain least-privilege;
do not grant it owner/admin access merely to read this global configuration live.
The long-lived dashboard router requests certificates only through Traefik's
`letsencrypt-dns-hetzner` resolver; that resolver must use Lego's Hetzner DNS-01 provider.
Before enabling reviews, pre-warm one certificate covering `*.<review DNS suffix>` and one
covering `*.dashboard.<review DNS suffix>` through Traefik's Lego Hetzner DNS-01 resolver.
Prefer Lego's `HETZNER_API_TOKEN_FILE` backed by a root-only read-only mount on Dokploy's
standalone Traefik container (a Swarm secret applies only when Traefik is itself a Swarm
service). Never put this DNS-write credential in GitHub or a review Stack. Review routers set
TLS without selecting an ACME resolver or domain, so Traefik serves the pre-warmed wildcard
instead of issuing per-PR certificates. With isolated deployments enabled, Dokploy creates a
network from the Compose application name, adds it to each service, and connects its standalone
Traefik. The trusted Stack deliberately defines no `dokploy-network`, external ingress network,
other network block, or `traefik.swarm.network` override. Dokploy's injected application network
is consequently the only network on every service. Through Dokploy's API or web UI, configure
the eligible review workers with `role=review`; all three review services use that constraint.
A successful Dokploy deployment row is only submission evidence. The protected workflow then
requires both public endpoints plus authenticated ingest/read smoke before recording success;
failure invokes exact-attempt cleanup. Do not grant the automatic token `docker:read` merely to
inspect task state: Dokploy v0.29.12 also uses that permission for cross-container restart, stop,
kill, removal, and file upload operations. Traefik may route to a different review worker across
the version's unencrypted isolated overlay; this is an accepted residual, not a same-node
guarantee. Do not replace the API and public smoke checks with host Docker or SSH mutation.
Before enabling the label trigger, verify the review environment has
no legacy `review-<repository>-<PR>` resources; remove any such test resources through Dokploy's
web UI rather than allowing duplicate ownership. Cleanup first
removes waiting Dokploy jobs, requires the Compose to remain non-running across a settling
check, and—once enqueue was attempted—requires either exact-SHA terminal evidence or a longer
queue-drain/non-running observation when a cancelled waiting job produced no deployment row.
It uses Dokploy's error-propagating Stack stop and waits until both HTTPS probes return 404.
Because an untrusted workload can forge a 404, cleanup then updates the Compose to the pinned,
zero-replica `review-anchor.yaml` Stack and verifies the complete isolated database state through
`compose.one`. A database update alone is not a scrub in Dokploy v0.29.12, so cleanup deploys that
inert definition under a unique title, waits for exactly one new successful deployment, reads and
semantically verifies the manager-side materialized file through `compose.getConvertedCompose`,
and stops the inert Stack again. Only then does it remove exact-owned package versions, mark
ownership metadata `retired:true`, and verify the final state. The activation timestamp is
preserved throughout cleanup so a failed registry cleanup remains immediately eligible for the
next reconciliation attempt. If enqueue or
polling leaves deployment state active or unknown, the client preserves the exact-owned Compose
anchor instead of racing an active Dokploy worker; remove the label and let cleanup reconcile
it after the job reaches a terminal state. Set
`BUILDHOUND_REVIEW_TTL_HOURS` to a base-10 value between 1 and 87600 in the review
environment. Prove
Dokploy isolation and public routing with the review smoke. This option separates Dokploy
applications; it is not an outbound firewall and must not be claimed as proof that a review
cannot call a public staging/production endpoint. Dokploy v0.29.12 `compose.delete` is intentionally
not called because it can leave the external isolated application network orphaned. Cleanup
removes public execution but retains one scrubbed, stopped Compose/network anchor per historical
PR for later reuse. Retired anchors do not count toward `MAX_ACTIVE` and scheduled reconciliation
skips them. Final anchor deletion is deferred until Dokploy exposes a supported exact lifecycle
that also removes its isolated network; operators must account for this bounded-per-PR accumulation.
The client rechecks PR state and verifies ownership metadata and exact returned IDs throughout.

Use a separate protected-main `review-cleanup` Environment for close/unlabel and scheduled
teardown. It carries the same secret names as `review`, but its Dokploy token is restricted
to the review environment and it has no human approval gate, so teardown is immediate. On
deploy-step failure, an always-evaluated dependent job also binds to `review-cleanup`; it
does not reuse the review-deploy credential. On
Dokploy v0.29.12 cleanup needs service read/create plus deployment read/create permission:
deployment evidence needs read, while `compose.update` and `compose.getConvertedCompose` are
guarded by service create, and the scrub deployment plus the
error-propagating `compose.stop` operation is guarded as a deployment mutation. A read-only token
cannot safely run this flow. Use an environment-specific custom-role
token and do not fall back to the repository-wide administration token before arbitrary PRs
are enabled. Dokploy v0.29.12 grants a newly created service to the creator member, so the
deploy and cleanup credentials must belong to that same least-privilege member, or the deploy
flow must explicitly grant the cleanup member access to the exact new service before broader
enablement. The automatic `review` Environment remains deploy-only and has no human reviewer gate.

Trusted cleanup also removes superseded and closed-review GHCR versions. It considers only
the fixed server/site packages, requires the version to have exactly one full-SHA tag for the
named PR, and verifies that exact SHA's repository/PR association before deleting the numeric
package-version ID. It re-reads and reasserts that the numeric version still carries only that
single tag immediately before deletion, and the trusted main publisher shares the review
lifecycle concurrency lock so repository workflows cannot retag it in between. When GitHub's
commit-to-PR endpoint no longer associates a displaced
force-pushed head, cleanup accepts only an exact `HeadRefForcePushedEvent.beforeCommit` from that
same repository and PR, with fail-closed cursor pagination. Because package write was removed from PR CI and tagging exists only in
protected-base delivery code, cleanup never trusts a mutable tag prefix alone. Shared,
ambiguous, or unverifiable versions are preserved and
fail strict teardown; prefix-only deletion is never used. Superseded-image collection after a
healthy deploy is warning-only so housekeeping cannot revoke a successful review, while
close/unlabel, failed-attempt, and TTL cleanup remain strict. Manager-side materialized scrubbing
precedes image cleanup; the stopped Compose remains the scheduled reconciler's retry anchor until
image cleanup succeeds, then becomes the scrubbed retired anchor described above.
