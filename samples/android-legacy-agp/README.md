# `android-legacy-agp` — a multi-module Android build on an older AGP

A ~10-module Android build pinned to an **older Android Gradle Plugin (8.5.2)** on **Gradle 8.14.5**.
It exercises the BuildHound plugin at the **bottom of its supported window** and surfaces an older
toolchain. Unlike the [`springboot-legacy`](../springboot-legacy/) sample, the Gradle configuration
here is deliberately **good** — the only "legacy" thing is the AGP version.

It applies the in-development plugin like the other samples (an included build). For the server,
dashboard, and local-dev credentials, see the shared [`../README.md`](../README.md).

## What's in it

~10 modules with nesting and a real DAG (`app → feature:* → core:* → library:*`):

| Group | Modules |
|---|---|
| app | `:app` (one `MainActivity`, a debug APK) |
| `feature:*` | `home`, `profile`, `settings` |
| `core:*` | `common`, `ui`, `data`, `network` |
| `library:*` | `analytics`, `logging` |

- **AGP 8.5.2**, **Kotlin 2.0.21**, `compileSdk 34`, `minSdk 24`
- **Gradle 8.14.5**
- `plugins {}` DSL + version catalog ([`gradle/libs.versions.toml`](gradle/libs.versions.toml)) —
  a clean, modern layout; the AGP version is the only old thing
- Good Gradle config: parallel + build cache + configuration cache (with `problems=warn`) all on

## Why Gradle 8.14.5 (and the expected warning)

The version window is exactly one Gradle minor wide:

- The BuildHound plugin's floor is **Gradle 8.14+** (JVM-21 / `BuildFeatures`).
- AGP 8.5 requires **Gradle ≥ 8.7** and does **not** run on Gradle 9 (it uses APIs removed there).

So **8.14.x** is the only line that satisfies both. Running AGP 8.5 on Gradle 8.14 emits, once per
build:

```
Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.
```

This is expected and intentional — it is *why* the sample stays on Gradle 8.14.x, and it is useful
demo fodder for BuildHound's deprecation / warnings surfacing.

## Run it

```bash
cd samples/android-legacy-agp
./gradlew :app:assembleDebug     # builds the whole DAG + a debug APK
```

Prerequisites:

- **JDK 21+** (the plugin's floor — asserted in `settings.gradle.kts`; AGP 8.5 itself is happy on
  JDK 17+). If your default JDK can't provision the plugin's build toolchain (JDK 26 via foojay),
  pass `-Pbuildhound.toolchain=21`.
- **Android SDK** — point Gradle at one via `ANDROID_HOME` / `ANDROID_SDK_ROOT`, or a git-ignored
  `local.properties` with `sdk.dir=/path/to/Android/sdk`. Without an SDK, AGP cannot even configure.

On completion, the plugin writes an HTML report under `build/buildhound/` and (if the server is up)
uploads the payload. Because the config here is good, the telemetry looks healthy (configuration
cache stored/reused, non-zero build-cache hit rate) — the opposite of the `springboot-legacy` sample.
