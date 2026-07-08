# 068 — Cache-miss diagnostics: non-relocatable-task detector + fingerprint volatility scoring

## Source

- **Research finding F18** (`new-insight`), [docs/research/ingest-corpus-analysis.md](../research/ingest-corpus-analysis.md)
  §5 F18 + cross-cutting rec §7.1 ("analysis over data already in the payload"; feeds the F4 rules
  engine and the F21 `/diagnosis` + MCP surface). Synthesized in the adversarial ingest-corpus pass —
  no single upstream article; the load-bearing **Verification recasts** in the finding shape this plan.
- Underlying mechanisms: [cache-miss-input-fingerprints.md](../research/cache-miss-input-fingerprints.md)
  (salted input fingerprints, plan [022](implemented/022-input-fingerprints-compare.md)); the
  internal-adapters `CacheOrigin` taxonomy (plan [038](implemented/038-internal-adapters.md)).
- Spec: [§3.7](../build-telemetry-spec.md) (pseudonymization / per-project salt), [§5](../build-telemetry-spec.md) (query API).

## Scope

This slice is **server-side only** — no plugin, commons, or payload-schema change. Both signals are
analysis over data plans 022/038 already collect CC-safely.

**In**

- **Detector 1 — non-relocatability.** Pure `RelocatabilityDetector` (sibling of `FlakyDetector`/
  `BottleneckCalculator`) over fleet rows `(taskPath, module?, hostnameHash, origin, cacheable?, durationMs)`,
  flattening `extensions["internalAdapters"].tasks[].origin` (plan 038) joined by path to core `tasks`.
- **Detector 2 — fingerprint volatility.** Pure `FingerprintVolatilityDetector` over per-salt-stream
  `(hostnameHash, startedAt, fingerprints.build)` sequences (plan 022); a per-key volatility score plus
  credential/timestamp/run-id **name-pattern** notes.
- One read route `GET /v1/rollups/cache-miss-diagnostics?days=` + a `BuildStore.cacheMissDiagnostics(...)`
  method on **both** stores (parity), + server-internal `@Serializable` response DTOs.
- Guarded **opaque-JsonElement** decode of the internal-adapters block — server keeps **no** dependency on
  `buildhound-internal-adapters` (plan 039 decoupling invariant; server treats `extensions` as opaque today).

**Out (deferred / explicitly not this slice)**

- **No** commons/payload schema change, **no** new payload field, **no** golden-file edit (`origin` = plan 038,
  `fingerprints.build` = plan 022 already ship). The only new serializable types are server-internal
  response DTOs with no golden-file contract — a deliberate, safe outcome, not an oversight.
- **No** confirmed "set `PathSensitivity` NONE/RELATIVE" fix — normalization strategy is observable via
  **no** public/internal API, so detector 1 emits a ranked **candidate** ("investigate path sensitivity"), never a fix.
- **No** secret auto-discovery — only user-allowlisted `fingerprints {}` keys are scored (privacy forbids env dumps).
- Per-`InternalTaskDetail.propertyHashes` volatility (plan 038, opt-in) — same detector, deferred; build-level `fingerprints.build` only here.
- **No** dashboard panel — the endpoint is consumed later by the F21 `/v1/builds/{id}/diagnosis` + MCP tool and the F4 rules engine (plan 050 likewise ships no dashboard).

## Design

- **No schema change.** Both detectors read fields that already exist: `origin` (038, in the opaque
  `internalAdapters` extension), `environment.hostnameHash` (§3.7), `tasks[].cacheable` (016), `fingerprints.build` (022).

- **Detector 1 rule (origin enum is *required*, not a sharpener).** The core-only path is impossible —
  core `FROM_CACHE` is undifferentiated local/remote and per-machine salting makes cross-host
  "matching fingerprints" unobservable — so the detector self-gates on the origin enum. **Fleet gate:**
  if the window carries **zero** `REMOTE_HIT` anywhere, stay silent (can't distinguish non-relocatable
  from "no remote cache"). **"Miss" means non-`REMOTE_HIT` *execution* = `STORED`|`MISS`**, not the
  literal `MISS` enum: a cold cacheable task normally emits `STORED` (executed-and-stored) per
  `OriginClassifier`, so keying on literal `MISS` would find nothing. **Candidate =** a cacheable task
  executed (`STORED`|`MISS`) on ≥2 distinct `hostnameHash`, `REMOTE_HIT` on **0**, with the fleet gate
  holding (a sibling task `REMOTE_HIT` somewhere) — `STORED` on ≥1 host is positive evidence it is
  *written but never relocated*. Rank by (cross-host executed count × wasted wall = Σ non-`REMOTE_HIT`
  `durationMs`). Note = ranked-candidate string, never a confirmed fix.

- **Detector 2 (single-salt-stream volatility).** Group by `hostnameHash`. This is an **exact** partition,
  not a proxy: `IdentityHashing.hostnameHash` HMACs the hostname with the **same** per-project salt file
  the fingerprints use (plan 022 divergence note), so under `pseudonymize=true` a salt regeneration mints
  a *new* `hostnameHash` — the salt within one group is provably constant. Order each group by `startedAt`;
  per key, volatility = fraction of **consecutive-build** transitions whose salted hash changed (need ≥2
  consecutive builds in the stream — a `MIN_STREAM` gate like `BottleneckCalculator.MIN_SAMPLES`).
  Aggregate per key **across** streams (report max + contributing stream count) but **never pool hashes
  across streams** (differing salts would inflate volatility). Credential shaping: strip the
  `env-`/`sysProps-`/`gradleProp-` prefix, match `.*_(KEY|TOKEN|SECRET)$` (+ epoch-timestamp / CI-run-id
  patterns); note from the new detector's small pattern table, cross-referencing the existing exact-name
  catalog `BuildComparator.NOTES` (**not** extracted — refactoring a shipped, tested plan-022 file is churn F18 doesn't need).

- **Store methods + parity.** New `BuildStore.cacheMissDiagnostics(projectId, days, nowMs): CacheMissDiagnostics`
  on `InMemoryBuildStore` and `PostgresBuildStore`. Both read windowed payloads (`InMemory` holds them;
  Postgres reads the jsonb payload — `origin`/`fingerprints` live only there, not in `task_executions`),
  flatten to the two row shapes via **guarded** JsonElement navigation, and defer to the two pure
  detectors (the plan 026/032/036 both-stores-agree-byte-for-byte discipline). The jsonb scan is gated to
  builds carrying the block and bounded by a `MAX_DIAGNOSTIC_ROWS` cap (mirroring
  `FlakyDetector.MAX_OUTCOME_ROWS`) + the retention window (plan 042).

- **Endpoint.** `get("/rollups/cache-miss-diagnostics")` in `queryRoutes` (`Routes.kt:339`), gated
  `authenticatedProject(tokens, TokenScope::allowsRead)`, wrapped in `runQuery` (503 on storage outage).
  Response `CacheMissDiagnostics(remoteCacheObserved: Boolean, nonRelocatable: List<NonRelocatableCandidate>,
  volatileInputs: List<VolatileInput>)`; `remoteCacheObserved=false` drives an honest empty state.

## Test strategy

- **`RelocatabilityDetectorTest` (unit).** No `REMOTE_HIT` anywhere ⇒ empty (fleet gate). `STORED` on 2
  hosts + `REMOTE_HIT`=0 while a sibling task `REMOTE_HIT`s ⇒ ranked candidate. A task that `REMOTE_HIT`s
  on the 2nd host ⇒ not flagged (relocated fine). Literal `MISS` also counts as a non-hit execution.
  Single host ⇒ not flagged. `cacheable=null` (plan-016/IP gap) still classifies from origin.
- **`FingerprintVolatilityDetectorTest` (unit).** Key changing every build in one stream ⇒ 1.0; stable
  key ⇒ 0.0; a key equal-within-each-of-two-streams-but-differing-across (distinct salts) ⇒ **0.0**
  (streams not pooled — the load-bearing case); `<2` builds ⇒ excluded; `env-GITHUB_TOKEN` matched after
  prefix strip and credential-noted; a non-credential volatile key still scored, generically noted.
- **Store parity (`InMemory` + Postgres via Testcontainers).** Same ingested set ⇒ byte-identical
  `CacheMissDiagnostics`; a payload with malformed `internalAdapters` JSON is skipped, not fatal.
- **Route (`testApplication`).** 401 no token; 403 ingest-scope token; tenant-scoped (a foreign build is
  invisible → not in the window); happy path names the non-relocatable task + the volatile credential key;
  `remoteCacheObserved=false` empty state when no build carries a `REMOTE_HIT`.

## Risks

- **Origin enum required (named, not hand-waved).** Without the opt-in, off-by-default internal-adapters
  `origin`, detector 1 cannot exist (undifferentiated `FROM_CACHE` + per-machine salt). Mitigation: an
  absent block yields an honest empty state — never a false "relocatable" verdict.
- **`STORED`-vs-`MISS` semantics.** Keying on the literal `MISS` enum gives near-zero recall; the rule
  spans `STORED`|`MISS` (non-`REMOTE_HIT` execution). Codified in the detector rule + a unit test.
- **Candidate, not a fix.** `PathSensitivity` normalization is observable via no API; the note is
  "investigate", ranked, never "change annotation X" — enforced by the DTO wording and test assertions.
- **Self-gating confound.** No `REMOTE_HIT` anywhere ⇒ stay silent, so "remote cache disabled" is never
  mislabeled non-relocatable (fleet gate + empty-state test).
- **Salt-stream correctness / privacy.** Volatility per `hostnameHash` group is exact under
  `pseudonymize=true` (shared salt). Documented limitation: under `pseudonymize=false`, `hostnameHash` is
  the plaintext hostname and won't split on a salt regen ⇒ possible inflation — acceptable, noted in the DTO KDoc.
- **Privacy (§3.7).** Only user-allowlisted fingerprint **key names** are scored (name-pattern matching is
  privacy-sound); values are salted hashes already in the payload; no env dumps, no auto-discovery. Output
  carries Gradle-internal task paths (not PII) and per-host **counts**, never `hostnameHash` values.
- **Additive-schema / goldens.** Zero payload-schema change and zero golden edits by design; new types are server-internal DTOs.
- **Isolated projects.** `cacheable` (plan-016 `whenReady` dictionary, intentionally empty under IP) and
  the internal-ops block are typically absent under IP ⇒ detector 1 degrades to empty; detector 2
  (build-level fingerprints) is unaffected. The plan-021 IP CI job guards regressions.
- **Multi-tenancy.** A single read route, token- and tenant-scoped via `authenticatedProject`/`allowsRead`;
  every window read is `project.id`-scoped — no cross-tenant pooling of origins or fingerprints.
- **Never-fail (server analogue).** Guarded JsonElement decode skips a malformed `internalAdapters` block
  (never 500); `runQuery` returns 503 on outage; the jsonb scan is bounded by `MAX_DIAGNOSTIC_ROWS` + the retention window.

## Exit criteria

- `GET /v1/rollups/cache-miss-diagnostics` is read-scope-gated and tenant-scoped; both stores return
  byte-identical results for the same window.
- A fleet with a cacheable task executed (`STORED`) on ≥2 hosts, `REMOTE_HIT` on 0, alongside a sibling
  `REMOTE_HIT`, ranks that task as a non-relocatable **candidate** (note = "investigate", not a fix);
  a task that `REMOTE_HIT`s cross-host is absent; a window with no `REMOTE_HIT` returns
  `remoteCacheObserved=false` and no candidates.
- An allowlisted `env-*_TOKEN` fingerprint whose salted hash changes every consecutive build **on one
  machine** scores ~1.0 with a credential note; the same key across two salt streams is **not** pooled.
- No commons/payload/golden change; internal-adapters stays an opaque `extensions` entry (no new module dependency).
- `./gradlew build` green (new unit, parity/Testcontainers, and route tests included).
