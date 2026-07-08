# `springboot-legacy` — a big, deliberately mis-configured Spring Boot build

A synthetic **50-module** Spring Boot build that exists so the BuildHound plugin has a large build
whose telemetry looks **bad on purpose**. Every Gradle anti-pattern here is intentional and
documented inline — **do not copy this project's build configuration into a real project.**

It applies the in-development plugin exactly like the other samples (an included build), so any
change to `buildhound-gradle-plugin/` or `buildhound-commons/` is picked up on the next build. For
the server, dashboard, and local-dev credentials, see the shared [`../README.md`](../README.md).

## What's in it

50 modules, 2–3 levels of nesting, with a real inter-module dependency graph
(`apps → services:<svc>:{api,domain,persistence,web} → libs:*`):

| Group | Count | Modules |
|---|---|---|
| `libs:*` | 6 | `common`, `util`, `validation`, `serialization`, `config`, `security` |
| `services:<svc>:*` | 40 | 10 services (`orders`, `payments`, `inventory`, `shipping`, `catalog`, `users`, `notifications`, `pricing`, `reviews`, `search`) × `api` / `domain` / `persistence` / `web` |
| `apps:*` | 4 | `gateway`, `admin`, `batch`, `scheduler` — Spring Boot apps (`@SpringBootApplication` + `bootJar`) that fan in over the service graph |

Each module carries one trivial class that references a type from a dependency, so compilation does
real, ordered work and the DAG produces non-trivial task / critical-path telemetry.

- **Gradle 9.6.1** (latest — the point is a *modern* Gradle with a *bad config*)
- **Spring Boot 3.5.16** + `io.spring.dependency-management` 1.1.7, pulled onto the **buildscript
  classpath** and applied with `apply plugin:` (legacy style, not the `plugins {}` DSL + catalog)
- `settings.gradle.kts` (Kotlin) + **Groovy** `build.gradle` module files — a realistic legacy mix

## The intentional anti-patterns

All in [`gradle.properties`](gradle.properties) and the root [`build.gradle`](build.gradle):

- **Configuration cache OFF**, **build cache OFF**, **`org.gradle.parallel=false`** — the modules
  build one at a time, nothing is reused. (`org.gradle.configureondemand` is left **off**: with the
  cross-project config below it would corrupt the task graph — a correctness bug, not just a slow build.)
- A root **`subprojects {}` cross-project configuration** block — couples all 50 modules to the root,
  inflates the configuration phase, and is incompatible with the configuration cache / isolated projects.
- The plain **`java`** plugin (no `java-library` api/implementation split).
- **Per-project `repositories { mavenCentral() }`** (settings keeps `repositoriesMode` relaxed so
  these are allowed instead of being centralised once).
- **Hardcoded, repeated dependency versions** (no version catalog / platform module).
- A small **`-Xmx1g`** heap for a 50-module build.

> One thing here is *not* an anti-pattern but a necessary fix: the root build qualifies each module's
> `group` and `archivesName` with its path. Nested modules reuse leaf names (`api`/`domain`/`web`
> across 10 services); with a single shared group + version those collapse to one module identity and
> Gradle's conflict resolution silently drops all-but-one from a consumer's classpath (and app
> `bootJar`s hit duplicate `web-1.0.0.jar` entries). See the inline comments in `build.gradle`.

## Run it

```bash
cd samples/springboot-legacy
./gradlew build            # ~50 modules compile serially; the 4 apps produce bootJars
```

Prerequisite: **JDK 21+** (the plugin's floor — asserted in `settings.gradle.kts`). If your default
JDK can't provision the plugin's build toolchain (JDK 26 via foojay), pass `-Pbuildhound.toolchain=21`.
No Android SDK is needed.

On completion, the plugin writes an HTML report under `build/buildhound/` and (if the server is up)
uploads the payload. The tell-tale signals of the bad config show up as telemetry:

- `cc=DISABLED` (configuration cache not requested)
- `hitRate=0.00` (no cache reuse)
- a serial task graph / low parallel utilisation

That "what's slow / what's wrong" picture is exactly what BuildHound is meant to surface.
