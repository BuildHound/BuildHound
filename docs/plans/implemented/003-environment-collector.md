# 003 — EnvironmentCollector: machine, identity, toolchain, daemon/CC state

## Source

Roadmap Phase 1: "EnvironmentCollector". Spec §3.2 (collection pipeline), §3.7
(privacy & identity), §4 (`environment` + `toolchain` payload blocks).

## Scope

**In:**

- `EnvironmentValueSource` (CC-safe `ValueSource`) in the plugin collecting: os, arch,
  cores, ramMb, hostname, username, Gradle version, JDK version.
- Pseudonymization per §3.7: `hostnameHash`/`userId` as prefixed truncated
  HMAC-SHA256 (`h_…`/`u_…`) keyed by a per-project salt. **Interim salt decision:** the
  server-issued per-project salt (§3.7) needs the tenancy chunk, so until then the salt
  is generated once into `<rootDir>/.gradle/buildhound/identity.salt` (32 random bytes,
  created at apply time, never logged, `.gradle/` is conventionally git-ignored).
  Swapping the salt source later changes no schema.
- `identity { pseudonymize = true }` DSL block (default **true**). `false` sends
  plaintext hostname/username; `strict` mode (send nothing) arrives with the payload
  chunk — modeled then as an enum, additively.
- Daemon-reuse + configuration-cache state via documented v0 heuristics (spec §3.2
  says "from start parameters + heuristics; refined later"): a per-daemon-JVM execution
  counter (`daemonReused = executions > 1`) and config-vs-execution counter comparison →
  `HIT | MISS_STORED | DISABLED` (`INCOMPATIBLE` deferred).
- Finalizer logs a one-line environment summary (mode/CC/daemon/os) so functional tests
  can assert collection through a real build, including on CC reuse.

**Out (later chunks):** git VCS info (needs an exec-backed ValueSource), AGP/KGP/KSP
versions (needs project-classpath introspection), payload assembly (chunk 4), server
salt fetch, `strict` identity mode, scrubber.

## Design

Pure hashing helper (`IdentityHashing`) + a `ValueSource` returning a
`java.io.Serializable` DTO (safest CC shape); the FlowAction receives it as a
`Provider` parameter and maps it to `EnvironmentInfo`/`ToolchainInfo` (commons) when
logging — actual payload embedding is chunk 4. ValueSources re-execute on CC reuse, so
environment stays fresh on hits. Daemon counters live in a plugin `object`
(per-classloader; documented as heuristic). All failure paths degrade to `warn` + null
fields, never fail the build.

## Divergence from the first draft

Salt IO moved from apply time into `EnvironmentValueSource.obtain()` (execution time):
configuration-phase file access is tracked as a CC fingerprint input, so creating the
salt during apply invalidated the very next build's cache entry (caught by the
CC store→hit functional test). Architecture doc §2 gained a rule for this. Bonus: the
salt is now only created when `pseudonymize=true` actually needs it.

## Test strategy

- Unit: `IdentityHashing` (stable for same salt+input, differs across salts, prefix +
  length shape, no plaintext leak).
- Functional (TestKit): summary line present with CC on; second run (CC reuse) still
  logs a fresh summary with `cc=HIT`; first run shows `cc=MISS_STORED`;
  `--no-configuration-cache` run shows `cc=DISABLED`; pseudonymized run leaks neither
  username nor hostname in output; `identity { pseudonymize = false }` accepted by DSL.

## Risks

- CC safety: no `Project`/`Settings` capture in the ValueSource or FlowAction;
  only Serializable params. Salt IO at execution time inside the ValueSource (see
  Divergence — config-phase file IO is a CC fingerprint input).
- Privacy: salt + plaintext identity must never appear in logs or payloads when
  pseudonymize=true; RAM via `OperatingSystemMXBean` guarded (com.sun.management may
  be absent on exotic JVMs → null).
- Heuristics can misreport in daemons shared across builds — accepted for v0, spec
  allows refinement later.

## Notes for chunk 4 (payload assembly)

- `enabled=false` now gates the finalizer and the environment probe (incl. salt
  creation); full mode gating (`local` opt-in file, `strict` identity) must land with
  payload assembly, before anything is uploaded.
- With `pseudonymize=false`, plaintext currently flows through fields *named*
  `hostnameHash`/`userId` — decide the wire representation in chunk 4 (agentName
  handling from plan 002 lands there too).
- Accepted: `daemonReused` is not asserted in functional tests (daemon allocation is
  TestKit-controlled and would be flaky).

## Exit criteria

`./gradlew build` green including new functional tests; no schema change; no
plaintext identity in any log line when pseudonymizing.
