# 111 — Server module targets JDK 26 (plugin stays 21)

**Status: open.**

## Source

Feature request (owner, 2026-08-15): "update JDK to use version 26 — only change for the
backend/server modules, the plugin should stay on jdk 21". Narrows the 2026-07-02 decision-log
entries (plan 011), which set a JVM 21 floor for **all** modules and a JDK 26 build toolchain
emitting Java 21 bytecode, explicitly leaving "consumer floor and JRE-21 server image unchanged".
Both are partially superseded here — for `buildhound-server` only.

## Scope

**In:**
- `buildhound-server` emits **Java 26** bytecode: `jvmTarget = JVM_26`, `-Xjdk-release=26`,
  `options.release = 26`, `source/targetCompatibility = 26`.
- `buildhound-server/Dockerfile`: both stages to `eclipse-temurin:26-*-jammy` (digest-pinned,
  digests resolved 2026-08-15), and **both** `-Pbuildhound.toolchain=21` overrides removed —
  a JDK-21 toolchain cannot compile to release 26.
- `docs/architecture.md`: module-table JVM floor for the server, plus a decision-log entry
  referencing the two 2026-07-02 rows it narrows.
- `CLAUDE.md` Conventions: "JVM 21 floor for **all** modules" is no longer true — reword to
  name the server's exception.

**Out (explicit decisions):**
- **The Gradle plugin, `buildhound-commons`, `buildhound-report`, `buildhound-internal-adapters`
  and the addons stay at 21.** Commons is the plugin's contract and report is embedded into the
  plugin at build time; raising either would raise the consumer floor, which the request excludes.
  A JVM-26 consumer reading JVM-21 producers is allowed by Gradle's `org.gradle.jvm.version`
  attribute, so nothing else has to move.
- **`buildhound-mcp` stays at 21.** It is a stdio server run on the *user's* JVM, not backend
  infrastructure — bumping it would make JDK 26 a user requirement. Flagged rather than assumed;
  a one-line follow-up if the owner wants it.
- No Ktor/Kotlin/dependency upgrades ride along, no source changes, no JDK 26 language features
  adopted in this plan.
- `deploy/compose.yaml` and the Dokploy stacks consume the built image by tag — unchanged.

## Design

- `buildhound-server/build.gradle.kts`: the four version pins above. `buildhound.toolchain` stays
  the local escape hatch, but the escape hatch now has a **floor of 26** for this module — setting
  it to 21 makes the module uncompilable. Say so in the comment; the property is documented in
  CLAUDE.md as the "26 can't be provisioned" workaround, and for this module it no longer is one.
- `Dockerfile`: `26-jdk-jammy@sha256:adafe495…` (build) and `26-jre-jammy@sha256:f0904b75…`
  (runtime). Removing `-Pbuildhound.toolchain=21` does **not** reintroduce the per-image JDK
  download the override existed to avoid — the base image *is* a JDK 26 — **provided** Gradle's
  toolchain auto-detection matches it against the `JvmVendorSpec.ADOPTIUM` pin the server script
  sets when `buildToolchain == 26`. If vendor matching fails, Gradle falls through to foojay
  provisioning inside the image. This is the plan's main empirical risk (see Risks).
- Kotlin 2.4.10 supports `JvmTarget.JVM_26` — verified by enumerating the enum in
  `kotlin-gradle-plugin-api-2.4.10-gradle813.jar`, not from memory.
- CI needs no change: every job that compiles the server already installs JDK 26 (`build`,
  `build-macos`, `build-windows`, `isolated-projects`, `functional-cc-off` use `java-version: 26`;
  `build-floor` and `overhead-budget` install `26` then `21` so 21 launches Gradle 8.14.4 while 26
  serves as the toolchain). Confirmed by reading `.github/workflows/ci.yml`, not assumed.

## Test strategy

No new tests — this is a compilation-target change, and the existing server suite is the
regression net. Verification is empirical and must prove the target actually moved:

1. **Class-file major version** of a compiled server class before/after (21 ⇒ 65; 26 ⇒ expected 70,
   confirmed by reading the byte, not asserted from memory). `./gradlew build` passing does **not**
   prove this on its own.
2. `docker build -f buildhound-server/Dockerfile -t buildhound-server .` from the repo root — the
   step that can fail on toolchain resolution inside the image.
3. `docker run` + `/health` and the resource routes the `server-image` job smoke-tests — proves
   JRE 26 runs 26 bytecode end to end.
4. Full `./gradlew build` — proves plugin/commons/report/adapters are untouched and still 21.

## Risks

- **Toolchain resolution inside the image** (above). Mitigated by proving the docker build locally
  before merge; if vendor matching fails the fix is to relax the vendor pin for the container path,
  not to restore `-Pbuildhound.toolchain=21` (which cannot work at release 26).
- **Base-image CVE surface changes** with the digest bump — `image-scan` covers it; a
  `security-reviewer-infra` pass on the new digests is part of §3.
- **Split floors are a footgun**: a future contributor moving code between commons and server can
  now introduce a 26-only API into a 21 module. The javac `options.release` pin on each module is
  the guard that makes that a compile error rather than a runtime `NoSuchMethodError`.
- No schema, payload, endpoint, or data-collection change — §3.2 review surface is limited to the
  base image and the build config.

## Exit criteria

Green `./gradlew build`; class-file major version confirms 26 for server classes and 21 for
plugin/commons/report; docker image builds from the repo root and passes the `/health` +
resource-route smoke; `docs/architecture.md` module table and decision log updated, `CLAUDE.md`
convention reworded; §3.1 reviews (`kotlin-gradle-reviewer` + `infra-reviewer`) and the mandatory
§3.2 security & privacy review plus `security-reviewer-infra` complete, findings fixed or accepted
in the PR.
