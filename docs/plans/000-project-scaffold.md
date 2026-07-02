# 000 — Project scaffold (roadmap phase 0)

**Source:** roadmap "Phase 0 — Foundations"; spec §2 (modules), §3.1–3.2 (plugin
architecture), §4 (schema v1), §5 (server stack), §8 (distribution).

**Scope (in):** multi-module Kotlin build with version catalog; `btp-commons` as KMP
module with schema v1 models + golden-file tests + `CiEnvironmentProvider` SPI; settings
plugin skeleton proving the riskiest assumptions (BuildService task-event collector +
Flow-API finalizer under configuration cache, incl. CC reuse) with TestKit; Ktor server
skeleton with idempotent `POST /v1/builds` behind a `BuildStore` boundary; OCI image +
compose stack; CI workflow; docs folder with research/spec/roadmap; living architecture
doc; CLAUDE.md workflow.

**Scope (out):** payload assembly/upload in the plugin, environment/VCS collectors,
Postgres/Timescale persistence, auth, dashboard, HTML report content, metric CLI
implementation — all phase 1+.

**Design:** see `docs/architecture.md` (module table, dependency rule, JVM floors).

**Test strategy:** golden-file contract tests (commons), TestKit functional tests with
`--configuration-cache` twice (plugin), `testApplication` route tests (server), template
self-containment test (report).

**Risks:** CC-safety of Flow API + `ServiceReference` (validated by the functional
tests); kotlin-test wiring in custom source sets (solved: explicit catalog deps).

**Exit criteria (met):** `./gradlew build` green with configuration cache on; plugin
applies cleanly and captures events on a fixture build incl. CC reuse; schema golden file
round-trips; server accepts/dedupes/rejects payloads; image + compose defined.
