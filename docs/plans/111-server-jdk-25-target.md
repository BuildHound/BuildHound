# 111 — Server module targets JDK 25 LTS (plugin stays 21)

**Status: open.**

## Source

Feature request (owner, 2026-08-15): "update JDK to use version 26 — only change for the
backend/server modules, the plugin should stay on jdk 21". **Retargeted to 25 LTS by owner
decision after the security review** surfaced that 26 is non-LTS with Adoptium support ending
Sep 2026 (~1 month out) while 25 runs to at least Sep 2031, and that Trivy never scans the JRE
component of the image at all — so an unpatchable JVM CVE would also have been invisible. Narrows the 2026-07-02 decision-log
entries (plan 011), which set a JVM 21 floor for **all** modules and a JDK 26 build toolchain
emitting Java 21 bytecode, explicitly leaving "consumer floor and JRE-21 server image unchanged".
Both are partially superseded here — for `buildhound-server` only.

## Scope

**In:**
- `buildhound-server` emits **Java 26** bytecode: `jvmTarget = JVM_26`, `-Xjdk-release=26`,
  `options.release = 26`, `source/targetCompatibility = 26`.
- `buildhound-server/Dockerfile`: runtime to `eclipse-temurin:25-jre-jammy`, build stage to
  `26-jdk-jammy`, a COPYed digest-pinned `21-jdk-jammy` for the daemon JVM (all digest-pinned,
  resolved 2026-08-15), both `-Pbuildhound.toolchain=21` overrides removed (a JDK-21 toolchain
  cannot compile to release 25), plus `auto-download=false` and an explicit
  `installations.paths` so a future drift fails loudly instead of reaching for foojay.
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
- **Base-image CVE surface changes** with the digest bump. ⚠️ **Corrected after the security
  review:** `image-scan` does **not** cover the JRE. Trivy reports targets for the Ubuntu apt
  layer and for each jar, but the Temurin runtime is neither — Syft lists it in the SBOM while
  Trivy never emits a vulnerability count for it. That gap is pre-existing, not introduced here,
  but it means the JVM component of this bump is unscanned. Compounding it, the `image-scan` job
  sets `ignore-unfixed: true` and `continue-on-error: true`.
- **JDK 26 is not an LTS release.** Adoptium's roadmap ends Temurin 26 support in **Sep 2026**;
  21 runs to at least Dec 2029 and 25 (LTS) to at least Sep 2031. Combined with the scanner gap
  above, an unpatched JVM CVE after Sep 2026 would be both unfixable upstream and invisible in CI.
  Owner decision required — see the PR discussion.
- **Split floors are a footgun**: a future contributor moving code between commons and server can
  now introduce a 26-only API into a 21 module. The javac `options.release` pin on each module is
  the guard that makes that a compile error rather than a runtime `NoSuchMethodError`.
- No schema, payload, endpoint, or data-collection change — §3.2 review surface is limited to the
  base image and the build config.

## Verification record (2026-08-15)

**Superseded twice — recorded honestly, because both corrections came from review, not from the
original verification.**

*First pass (JDK 26).* `./gradlew build` green; class-file major 70 for the server and 65 for the
other seven modules; image built; container served `/health` and every resource route. All true,
and all insufficient:

- The "no foojay lookup in the build log" check **cannot** detect toolchain provisioning, which
  prints nothing. A 541 MB JDK was being fetched with the log clean. Absence of log evidence was
  read as evidence of absence.
- The green rebuild that "proved" the `auto-download=false` guard was a **warm BuildKit cache**
  serving a JDK provisioned by the earlier unguarded build. CI, with a cold cache, went red:
  `Cannot find a Java installation … matching {languageVersion=21}`.
- The container smoke curls from the *host* into a published port, so it can never detect a
  missing in-container binary — which is exactly how the `curl`-less JRE slipped through.

*Second pass (JDK 25 LTS, current).* Verification rules that follow from the above, and must be
used for any future base bump:

1. `docker build --no-cache` — a warm cache hides toolchain-resolution failures.
2. Assert on the **filesystem**, not the log: no `jdks/` directory may appear in the Gradle user
   home during the image build.
3. Execute the healthcheck **inside** the container (`docker exec`), never from the host.
4. Class-file major must read **69** (Java 25) for the server and **65** for every other module.
5. Check the base image's EOL window against today's date before pinning it.

## Exit criteria

Green `./gradlew build`; class-file major version confirms 26 for server classes and 21 for
plugin/commons/report; docker image builds from the repo root and passes the `/health` +
resource-route smoke; `docs/architecture.md` module table and decision log updated, `CLAUDE.md`
convention reworded; §3.1 reviews (`kotlin-gradle-reviewer` + `infra-reviewer`) and the mandatory
§3.2 security & privacy review plus `security-reviewer-infra` complete, findings fixed or accepted
in the PR.
