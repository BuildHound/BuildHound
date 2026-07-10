# CLAUDE.md

**BuildHound** (buildhound.dev): an open-source Develocity alternative —
a Gradle settings plugin that collects build/task/cache/test telemetry, a multi-tenant
Ktor ingestion service shipped as an OCI image, a standalone per-build HTML artifact, and
CI assets. Apache-2.0.

## Source-of-truth documents

| Document | Role |
|---|---|
| `docs/build-telemetry-spec.md` | What we are building (v0.1, locked decisions included) |
| `docs/build-telemetry-roadmap.md` | Phase ordering and exit criteria |
| `docs/build-telemetry-research.md` | Why — landscape, APIs, risk register (§6) |
| `docs/architecture.md` | How we build it well — **living** architecture + best practices (Gradle plugin, KMP, OCI). Update it whenever development produces a better insight |
| `docs/brand/DESIGN-V2.md` | Required brand and product-design system for public sites, dashboards, and HTML report interfaces |
| `docs/plans/` | One committed plan per feature (see workflow below) |

## Modules

- `buildhound-commons` — KMP shared module: payload schema v1 (kotlinx-serialization) +
  `CiEnvironmentProvider` SPI. The contract everything builds against; golden-file tests
  pin every schema version.
- `buildhound-gradle-plugin` — settings plugin (id `dev.buildhound`).
  Collector `BuildService`, Flow-API finalizer, `buildhound {}` DSL. TestKit tests in
  the `functionalTest` source set. Must stay configuration-cache safe.
- `buildhound-server` — Ktor ingest service (`POST /v1/builds`, `/health`), storage behind
  `BuildStore`. OCI image via `buildhound-server/Dockerfile`, local stack via
  `deploy/compose.yaml` (TimescaleDB). JVM 21.
- `buildhound-report` — standalone HTML artifact (zero network — enforced by test), embedded
  into the plugin at build time.
- `buildhound-ci-assets` — deliberately **not** a Gradle module: Azure YAML template, shell
  metric CLI.

## Commands

```bash
./gradlew build                          # everything: compile, unit + functional tests
./gradlew :buildhound-gradle-plugin:functionalTest   # TestKit tests only
./gradlew :buildhound-server:test               # server tests only
./gradlew :buildhound-server:run                # run the server locally (port 8080)
docker build -f buildhound-server/Dockerfile -t buildhound-server .   # OCI image (from repo root!)
docker compose -f deploy/compose.yaml up --build        # server + TimescaleDB
```

The repo keeps `org.gradle.configuration-cache=true`; never disable it to make something
pass — fix the CC violation instead.

## Development workflow (required)

Every feature follows **plan → commit → implement → review → merge**:

### 1. Plan first, commit the plan

Before writing implementation code, derive a short plan from the spec/roadmap (or the
feature request) and write it to `docs/plans/NNN-short-title.md` — structure described in
`docs/plans/README.md`. **Commit the plan before implementation starts** (its own commit,
e.g. `plan: task event collector (spec §3.2)`). This keeps design intent reviewable and
separates "what we intended" from "what we built". If implementation diverges, update the
plan file in the same PR and say why.

### 2. Implement

Follow the committed plan and the binding rules in `docs/architecture.md`. Hard
constraints that always apply:

- The plugin must never fail a build; every failure path degrades to a `warn` log.
- Configuration-cache compatibility for all plugin code.
- Schema changes are additive only; add golden files, never edit them.
- Any work that creates or modifies a public site, product dashboard, or HTML report
  interface must use `docs/brand/DESIGN-V2.md`, reuse its tokens and assets, and follow its
  status semantics, typography, component, and responsive rules. The files under
  `docs/brand/v2/` are static reference fixtures, not production components. The older
  `docs/brand/DESIGN.md`, `docs/brand/DESIGN-GPT.md`, `docs/brand/site/`, and
  `docs/brand/gpt/` directions are historical references, not implementation sources.
  Reconcile an intentional divergence in `docs/brand/DESIGN-V2.md` before coding it.
  Production reports may adopt token values, semantic rules, system-font roles, and inline
  SVG path geometry, but must not copy `<link>`, `<img>`, `url()`, `@import`, webfont, or
  relative asset references from the fixtures. Never weaken
  `buildhound-report/src/test/kotlin/dev/buildhound/report/ReportAssetsTest.kt` to adopt V2.
  Runtime adoption requires a separate implementation plan.
- No internal Gradle APIs on the always-on core path (v1). All internal-API use is quarantined in
  the bundled `buildhound-internal-adapters` module and stays dormant until a
  `buildhound { internalAdapters { } }` toggle is set — enabling a toggle is the per-feature consent
  (plan 074). Core's own source references no internal Gradle type. (Honest scope: dormancy holds on a
  daemon where no build has enabled a toggle; a known CC-hit warm-daemon edge is tracked in plan 075.)
- Tokens/secrets only via providers/env — never in code, DSL literals, logs, or images.

### 3. Review with clean-context agents

After implementation, run **two separate reviews, each in a fresh context** (subagents
with no memory of the implementation conversation — they must judge the code on its own
merits, not on the author's intentions):

1. **Code & architecture review** — correctness, test coverage, simplicity, and
   conformance to `docs/architecture.md` (Gradle plugin rules §2, KMP rules §3, OCI rules
   §4, server rules §5). Also: does the code match the committed plan?
2. **Security & privacy review** — a single dedicated review that examines the change
   from *both* perspectives:
   - *Security:* token handling, injection surfaces, dependency risk, container
     hardening, authz on new endpoints, rate limiting, secrets in logs/layers.
   - *Privacy:* what new data is collected? Is it in the spec? Pseudonymization intact
     (spec §3.7), no absolute paths/env dumps/PII in payloads, scrubber coverage,
     retention implications, local-build opt-in respected.

Findings are fixed (or explicitly accepted with a note in the PR) before merge. When a
review invalidates an architectural assumption, update `docs/architecture.md` — including
its decision log — in the same PR.

#### Subagent fleet & review routing

The §3 reviews above run through the per-stack reviewer fleet (user scope,
`~/.claude/agents/`, hot-reloaded — nothing to install). Route by changed paths:

| Changed paths | Code & architecture review (§3.1) |
|---|---|
| `*.kt`, `*.kts`, `buildhound-commons`, `buildhound-gradle-plugin`, `buildhound-server` | `kotlin-gradle-reviewer` |
| `Dockerfile`, `deploy/compose.yaml`, CI yml, `buildhound-ci-assets`, deploy scripts | `infra-reviewer` |
| `buildhound-report` (HTML artifact) | `frontend-reviewer` (optional — flag if the rendered-build-data templating looks risky) |

**The §3.2 Security & privacy review stays authoritative and always runs for
plugin/commons/server changes** — no fleet agent covers Ktor app-security or BuildHound's
privacy spec (§3.7 pseudonymization, scrubber coverage). Do not substitute a generic
security-reviewer-* agent for it. The fleet only adds:
- `security-reviewer-infra` for Dockerfile/compose/CI paths (secrets in layers/traces,
  token scope, supply chain) — in addition to, not instead of, §3.2.

Cross-stack diffs (e.g. plugin change + Dockerfile bump) get both quality reviewers, each
scoped to its own paths, plus the mandatory §3.2 review.

The generic `code-reviewer` agent is scoped to KMP/Compose **mobile** projects — do not use
it here.

Operational agents:
- `gh-ci-babysitter` (background) — after every push to a branch with an open **GitHub**
  PR; watches Actions in chunked ≤8-min polls (the Bash tool caps a single call at 10 min),
  classifies failures, surfaces failed jobs marked `allow_failure`/continue-on-error.
- `review-env-verifier` (background) — after a deploy goes green; pass the base URL +
  checklist in the prompt.
- `plan-scaffolder` — when a `docs/plans/NNN-*.md` plan is approved: writes the handoff doc
  and emits the fresh-context kickoff prompt.

Standing rules:
- **Never set `CLAUDE_CODE_SUBAGENT_MODEL`** (env or settings) — it overrides every agent's
  `model` frontmatter and flattens the fleet's haiku/sonnet cost routing.
- Reviewers are report-only; implementation fixes findings after reviews complete.
- The `isolation: worktree` agent option branches from the **default branch** — never use
  it for agents that must land work on a feature branch; read-only agents only.

Not yet built (candidates if reviews keep flagging the same gaps): a
`config-cache-reviewer`-style pre-TestKit check, and a habit-agent for the "versions from
Maven Central metadata, never from memory" convention above.

### 4. Keep the architecture document alive

`docs/architecture.md` is a living document: improve it whenever a better practice is
found during development, a decision is made/reversed (decision log §7), or a phase retro
(roadmap guardrails) produces lessons. Best-practice updates are normal PR content, not a
special event.

## Conventions

- JVM 21 floor for **all** modules (owner decision, see architecture decision log); the
  plugin therefore requires Gradle running on JDK 21+. The build itself uses a JDK 26
  toolchain (auto-provisioned; bytecode/API stay 21 via `-Xjdk-release=21`) — set
  `buildhound.toolchain=21` in your user gradle.properties if 26 can't be provisioned.
- Coordinates (naming decision #6): Maven group `dev.buildhound`, plugin id
  `dev.buildhound`, packages `dev.buildhound.*`, env-var prefix `BUILDHOUND_`.
  Casing: **BuildHound** in prose/class names, `buildhound` in ids/modules/DSL.
- Version catalog (`gradle/libs.versions.toml`) is the only place versions live.
- **When adding a dependency, check what the latest released version is and use that** —
  query Maven Central (`https://repo.maven.apache.org/maven2/<group-path>/<artifact>/maven-metadata.xml`)
  or the Gradle Plugin Portal; never copy a version from memory, an old example, or
  another project. Same applies to tool upgrades (Gradle wrapper, base images).
- Commit messages: imperative mood, scoped prefix when useful
  (`plugin:`, `server:`, `commons:`, `docs:`, `plan:`).
