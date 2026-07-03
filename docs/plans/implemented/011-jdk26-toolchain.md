# 011 — Build with a JDK 26 toolchain, target Java 21

## Source

Owner request (2026-07-02): "update the gradle toolchain to jdk 26 but set source and
target compatibility to java21 for the gradle plugin; for kotlin compilation use
`-Xjdk-release=21` and `-jvm-target 21`".

## Scope

**In:**

- All JVM modules build with a **JDK 26 toolchain** (`jvmToolchain(26)`); the
  `org.gradle.toolchains.foojay-resolver-convention` settings plugin (1.0.0, latest on
  the Plugin Portal today) auto-provisions it where no local JDK 26 exists.
- **Compatibility stays Java 21** — the consumer floor (Gradle daemons on JDK 21+) and
  the server's JRE-21 runtime image are unchanged:
  - Kotlin: `jvmTarget = JVM_21` + `-Xjdk-release=21` (caps the visible JDK API surface
    at 21, the Kotlin analog of javac `--release`) in every JVM compilation.
  - The Gradle plugin module additionally sets `java.sourceCompatibility` /
    `targetCompatibility = VERSION_21` (as requested).
- Escape hatch: the toolchain version resolves from the `buildhound.toolchain` gradle
  property (default 26). Environments that cannot provision 26 (this sandbox's egress
  policy blocks api.foojay.io; only JDK 21 is installed) set `buildhound.toolchain=21`
  in their **user-home** gradle.properties — never committed. Emitted bytecode is
  identical either way (release 21); CI proves the real 26 toolchain.
- CI keeps launching Gradle on JDK 21 (the floor job's Gradle 8.14.3 cannot *run* on
  26); the toolchain is provisioned by foojay on the runners. Dockerfile unchanged
  (build stage provisions the toolchain the same way).
- CLAUDE.md conventions + architecture decision log updated.

**Out:** raising the JVM floor (stays 21); JDK-26-only language/API features (blocked
by release 21 anyway); base-image changes.

## Risks

- Supply chain: the foojay resolver plugin is a new settings-classpath dependency —
  official Gradle org, pinned version.
- Gradle 8.14 (floor) must *compile with* a 26 toolchain while running on 21 —
  validated by the CI floor job; Kotlin 2.4 daemons support running on JDK 26.
- `-Xjdk-release` + `jvmTarget` guarantee no >21 API/bytecode sneaks in; the plugin's
  functional tests still run on the host Gradle/JDK (21) as the real consumer check.

## CI fallout, fixed post-push

- The toolchain drives the `org.gradle.jvm.version` variant attribute: modules
  advertised "requires JVM 26" and every 21-JVM daemon refused to resolve them →
  `targetCompatibility=21` on JVM modules + explicit `TargetJvmVersion` attribute on
  the KMP jvm target.
- KGP requires Gradle ≥ 8.14.4 to compile with a JDK 26 toolchain → floor job bumped
  from 8.14.3 (patch-level, floor stays "8.14").
- TestKit spawns the fixture Gradle on the test JVM; with tests executing on the 26
  toolchain, the floor Gradle (8.14) failed to run at all → the plugin's test tasks
  now use a JDK 21 launcher, which is also the truthful consumer floor.

## Review round (fixed post-merge of the fallout)

- `options.release=21` on all JavaCompile tasks: Kotlin was API-capped but javac was
  not — the first .java file would have silently reopened the >21-API hole; this also
  makes the toolchain escape hatch provably artifact-neutral.
- Toolchain vendor pinned to Adoptium on the default 26 path (shrinks foojay's
  resolution space); overrides stay vendor-free for local JDKs.
- Docker build uses `-Pbuildhound.toolchain=21` (release-21 bytecode either way; drops
  the per-image JDK download and its network dependency).
- Architecture rule 10 gained the KGP apiVersion-2.0 shelf-life contingency.
- Noted for a future build-infra chunk: the per-module toolchain/compat blocks now
  repeat 4x — the architecture's convention-plugin threshold is met.
- Accepted: the javap major-version check stays a manual/one-off verification (the
  compile flags make it near-tautological); the escape hatch is CI-unexercised.

## Test strategy

Existing full suite on both CI jobs (now compiling via the 26 toolchain); locally via
the 21 override. No behavior change expected — bytecode target identical.

## Exit criteria

CI green on both jobs with the 26 toolchain; `javap`-level check that emitted class
files are major version 65 (Java 21); local builds keep working via the override.
