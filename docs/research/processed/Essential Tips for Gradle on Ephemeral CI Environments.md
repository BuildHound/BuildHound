---
title: "Essential Tips for Gradle on Ephemeral CI Environments"
source: "https://blog.gradle.org/gradle-on-ephemeral-ci-1"
author:
  - "[[Louis Jacomet]]"
published: 2026-07-07
created: 2026-07-07
description: "Ephemeral CI replaces long-lived, stateful build agents with short-lived, disposable environments, typically powered by containers and cloud services like GitHub Actions or Docker, to ensure isolated, reliable, and repeatable builds. While this approach improves consistency, s..."
tags:
  - "clippings"
---
Learn practical strategies to optimize Gradle builds in ephemeral CI environments and keep your pipelines fast without relying on persistent state.

## Introduction

Ephemeral CI replaces long-lived, stateful build agents with short-lived, disposable environments, typically powered by containers and cloud services like GitHub Actions or Docker, to ensure isolated, reliable, and repeatable builds. While this approach improves consistency, security, and scalability, it also eliminates persistent state, requiring new strategies for caching and setup efficiency.

This post covers practical strategies to overcome those limitations and keep Gradle fast. With the right optimizations, Gradle’s flexibility and tooling support ephemeral workflows well.

## Common Gradle Optimizations

While numerous Gradle optimizations are widely recommended and beneficial in traditional CI, their effectiveness in ephemeral CI environments varies significantly. Many common optimizations rely on persistence and statefulness, making them less effective or more complex to maintain in ephemeral contexts.

Here’s a closer look at how these popular [Gradle optimizations](https://docs.gradle.org/current/userguide/performance.html) perform in ephemeral CI:

| # | Optimization for Local or Traditional CI Builds | Effective in Ephemeral CI? | Reason |
| --- | --- | --- | --- |
| 1 | [Enable the Build Cache](https://docs.gradle.org/current/userguide/performance.html#enable_build_cache) | ✅ | Highly effective when remote caching is leveraged; local caching alone has limited utility |
| 2 | [Tailor workflows](https://docs.gradle.org/current/userguide/performance.html#enable_specific_dev_workflows) (task avoidance) | ✅ | Helpful but requires careful planning and continuous adjustments |
| 3 | [Increase Gradle’s heap size](https://docs.gradle.org/current/userguide/performance.html#increase_heap_size) | ✅ | Immediate benefits without significant setup overhead |
| 4 | [Enable parallel execution](https://docs.gradle.org/current/userguide/performance.html#sec:enable_parallel_execution) | ✅ | Efficiently uses cloud resources |
| 5 | [Optimize the configuration phase](https://docs.gradle.org/current/userguide/performance.html#optimize_configuration) (lazy APIs, applying plugins selectively, etc.) | ✅ | Effective but requires rigorous management of configuration logic |
| 6 | [Optimize dependency resolution](https://docs.gradle.org/current/userguide/performance.html#optimize_dependency_resolution) (declarative syntax, remove dynamic versions, etc.) | ✅ | Helpful, though gains are often marginal in ephemeral CI |
| 7 | [Re-use the Dependency Cache](https://docs.gradle.org/current/userguide/dependency_resolution.html#sec:dependency_cache) | 🔷 | Manual reuse is fiddly; the free [`setup-gradle`](https://github.com/gradle/actions) action provides it on GitHub Actions, and Develocity’s **Artifact Cache** provides it on any CI |
| 8 | [Enable the Configuration Cache](https://docs.gradle.org/current/userguide/performance.html#enable_configuration_cache) | ✅ | Increasingly enabled by default in modern Gradle, and worth keeping on for ephemeral CI even without cross-run reuse; the [`setup-gradle`](https://github.com/gradle/actions) action can also cache it on GitHub Actions. See our [Configuration Cache on CI](https://blog.gradle.org/road-to-configuration-cache#configuration-cache-on-ci) guide |
| 9 | [Enable the Gradle Daemon](https://docs.gradle.org/current/userguide/performance.html#enable_daemon) | ❓ | A persistent daemon offers little benefit when each runner handles a single build; `--no-daemon` runs a single-use daemon that exits afterward rather than leaving a process behind |
| 10 | [Optimize Java/Android projects](https://docs.gradle.org/current/userguide/performance.html#optimize_java_projects) | ❓ | Highly dependent on specific project setups and build frequency |
| 11 | [Optimize incremental builds for custom tasks](https://docs.gradle.org/current/userguide/performance.html#enable_inc_builds_custom_tasks) (proper task inputs and outputs) | ❌ | Stateless builds can’t reuse incremental data effectively |

*Legend:*

- ✅ effective on ephemeral CI
- 🔷 effective with caching tooling: the [`setup-gradle`](https://github.com/gradle/actions) GitHub Action or [Develocity Universal Cache](https://gradle.com/develocity/product/universal-cache/)
- ❓ situational
- ❌ not effective

While these [optimizations](https://docs.gradle.org/current/userguide/performance.html) offer real benefits, their value in ephemeral CI varies: some apply directly, others need adapting, and a few don’t translate at all.

## Benchmarking Performance in Ephemeral CI

In this blog post, we’ll focus on strategies tailored for ephemeral CI, going beyond the usual solutions highlighted above and quantifying their real impact in practice. Rather than relying on assumptions or generic advice, we’ll use measurable data to show how each optimization performs in a clean, disposable CI setup.

These numbers are not intended as universal Gradle performance claims. They show the relative impact of startup optimizations for one small Spring Boot project, one Gradle version, one JDK image, and one local Docker environment. Larger builds may shift the balance toward dependency resolution, configuration, or execution-phase optimizations.

### Creating a Realistic Ephemeral Environment

We used Docker containers so each build starts in a fresh, isolated state, closely mimicking ephemeral CI.

We generated a sample project from the [Spring Boot](https://spring.io/projects/spring-boot) template using [Spring Initializr](https://start.spring.io/), specifically a Gradle (Kotlin DSL) project with the defaults. We’ll call this the **Spring Boot Project**.

The project looks as follows.

**Spring Boot Project Structure:**

```
spring-boot-app/
  ├── gradlew                            <-- Gradle Wrapper
  ├── gradlew.bat
  ├── gradle/                            <-- Gradle Wrapper configuration folder
  │   └── wrapper
  │       ├── gradle-wrapper.jar
  │       └── gradle-wrapper.properties  <-- Uses -bin Gradle distribution
  │── src/                               <-- Spring Boot source code
  │── build.gradle.kts                   <-- Gradle build script
  │── settings.gradle.kts                <-- Gradle settings file
  │── Dockerfile                         <-- Dockerfile (below)
  └── .dockerignore                      <-- Prevents unnecessary files
```

Our starter Dockerfile looks as follows.

**Spring Boot Project Dockerfile:**

```dockerfile
# Eclipse image
FROM eclipse-temurin:17-jdk AS build

# Copy Spring Boot project to app
WORKDIR /app
COPY . .

# Build Spring Boot using the Gradle Wrapper (-bin distribution)
RUN ./gradlew build --no-daemon

# Extract the built JAR
RUN mkdir -p target && cp build/libs/demo-0.0.1-SNAPSHOT.jar target/app.jar

# Run Spring Boot app
CMD ["java", "-jar", "target/app.jar"]
```

**Note:** Every build in this series runs with `--no-daemon`. This doesn’t skip the daemon entirely: Gradle still runs the build in a *single-use* daemon that is discarded when the build finishes. In a single-use ephemeral container there’s no later build to reuse a persistent daemon, so `--no-daemon` reflects how Gradle realistically runs in disposable CI without leaving a process behind.

### Experimental Methodology

To measure each optimization consistently, we build and run a Docker image of the **Spring Boot Project**:

1. **Docker image build**: We build a fresh Docker image for each scenario (no cached layers) to simulate ephemeral isolation:
	```shell
	docker build --no-cache -t gradle-springboot -f Dockerfile .
	```
2. **Timing**: We use the `time` command to capture the build duration for direct comparison:
	```shell
	time docker build --no-cache -t gradle-springboot -f Dockerfile .
	```
3. **Verification**: After the build, we run the container to confirm the Spring Boot application starts correctly:
	```shell
	docker run --rm -p 8080:8080 gradle-springboot
	```
	```
	2025-03-17T19:43:02.987Z  INFO 1 --- [demo] [main] com.example.demo.DemoApplication : Started DemoApplication in 0.37 seconds (process running for 0.584)
	```

### Establishing Baselines

We established a baseline by building the **Spring Boot Project** image with no optimizations applied. This is the reference point for every measurement below:

```
[+] Building 31.7s (11/11) FINISHED
```

This **31.7-second** baseline represents a typical non-optimized build. We’ll revisit it often.

### Experimental Steps

Each optimization was evaluated through the following steps:

1. **Dockerfile Update**: Integrate a specific Gradle optimization into the Dockerfile for our **Spring Boot Project**.
2. **Timed Builds**: Measure build times accurately using the steps described above.

For optimizations involving caching, we used a two-stage Docker build:

1. **Prime Stage**: Execute an initial “empty” Gradle project build (**Prime Project**) to populate Gradle caches.
2. **Final Stage**: Build the actual Spring Boot application (**Spring Boot Project**), restoring Gradle caches from the prime stage.

To keep Docker’s own caching from skewing the results, every timed build runs with `--no-cache`, base images are pre-pulled (so image-download time is excluded), and the prime image is built once and only *restored* from, so the timed builds measure cache restore, not cache creation. Each figure below is the average of five runs against one consistent baseline.

**NOTE:** All measurements in this post are pinned to Gradle 9.5.1. If you repeat the experiment with a newer Gradle version, rebuild the prime image and update any version-specific cache paths accordingly.

The Prime Gradle project contains an empty settings file and no build files or source code. The structure looks as follows.

**Prime Project Structure:**

```
empty-project/
  ├── gradlew                            <-- Gradle Wrapper
  ├── gradlew.bat
  ├── gradle/                            <-- Gradle Wrapper configuration folder
  │   └── wrapper
  │       ├── gradle-wrapper.jar
  │       └── gradle-wrapper.properties  <-- Uses the -bin Gradle distribution
  │── settings.gradle.kts                <-- EMPTY Gradle settings file
  │── Dockerfile                         <-- Dockerfile (below)
  └── .dockerignore                      <-- Prevents unnecessary files
```

The Dockerfile creates a Docker image from the prime project and pre-bakes all the files Gradle generates and downloads during its first run.

**Prime Project Dockerfile:**

```dockerfile
FROM eclipse-temurin:17-jdk AS gradle-cache

WORKDIR /app
COPY . .

# Run help using the Gradle Wrapper
RUN ./gradlew help
```

Priming pre-generates essential files and caches in the Gradle User Home (`~/.gradle`), which holds:

- downloaded dependencies (`modules-2`),
- compiled build scripts (`kotlin-dsl`, `scripts`),
- processed JAR files (`jars-9`),
- generated Gradle version-specific files (`generated-gradle-jars`),
- artifact transformations (`transforms-3`), and
- local build cache artifacts (`build-cache-1`).

Which directories exist depends on the build and the tasks already run. Caching the right components can dramatically reduce ephemeral build times.

Additionally, it contains:

- The `wrapper` folder, which stores downloaded Gradle distributions for consistent version usage,
- The `daemon` folder, which manages long-lived build processes to expedite repeated tasks, and
- The `jdks` folder, which is used by Gradle Toolchains to manage downloaded JDK installations when automatic provisioning is enabled.

Not all of these appear for a minimal project, though. With our **Prime Project** (empty `settings` file), only these are created:

```
.gradle/                              <-- Gradle User Home (default: ~/.gradle)
  ├── wrapper/dists/                  <-- Gradle distribution (downloaded by wrapper)
  ├── daemon/<version>/              <-- Gradle daemon runtime data
  └── caches/<version>/
      ├── generated-gradle-jars/      <-- Internal Gradle runtime jars
      └── jars-9/                     <-- Processed Gradle jars
```

Subsequent sections detail the measurable impact of applying advanced optimizations, such as caching the directories above.

## Gradle Lifecycle Phases

To optimize Gradle performance in ephemeral CI environments, it’s essential to understand the key phases of [Gradle’s lifecycle](https://docs.gradle.org/current/userguide/build_lifecycle.html). Each phase presents different optimization opportunities:

1. **Startup / Initialization**: Gradle prepares the environment for the build by launching the Java Virtual Machine (JVM), downloading and extracting the specified Gradle distribution (if needed), and starting the Gradle daemon.
2. **Configuration**: Gradle evaluates build scripts, applies plugins, and configures tasks. Tasks are identified and prepared, but not yet executed.
3. **Execution**: Gradle runs the tasks defined by the build configuration and resolves any task dependencies.

![Gradle Lifecycle Phases](https://blog.gradle.org/images/2026/gradle-ephemeral-ci-1/image1.png "Gradle Lifecycle Phases")

In ephemeral CI, the **Startup / Initialization** phase runs on every build, so improvements here can have an outsized effect on total build time.

## Optimizations for the Startup Phase

The **Gradle Startup phase** occurs before the official build lifecycle and prepares the environment for the build to run. Here’s what happens:

1. **Java Virtual Machine (JVM) Startup**
	- Starts the JVM using the specified Java version.
2. **Distribution Download**
	- Downloads the specified Gradle version when it is not already present (*typically a 100MB ZIP file*).
		- Extracts the distribution into Gradle’s User Home directory.
3. **Daemon Startup**
	- Starts a daemon to handle build execution, caching, and task parallelization.
4. **Distribution First Use**
	- Generates JARs on disk that don’t ship with the distribution.
		- Initializes the build environment using the downloaded Gradle version.

![Gradle Startup Phase](https://blog.gradle.org/images/2026/gradle-ephemeral-ci-1/image2.png "Gradle Startup Phase")

There are a number of options to optimize the **Gradle Startup phase**:

| # | Type of Optimization | Optimization |
| --- | --- | --- |
| [1](#1-use-the--bin-distribution-instead-of--all) | Distribution Availability | Use the Gradle `-bin` Distribution Instead of the `-all` Distribution |
| [2](#2-leverage-gradle-docker-images) | Distribution Availability | Leverage Gradle Docker Images |
| [3](#3-set-up-local-mirrors-if-the-network-is-slow) | Distribution Availability | Download Gradle from a Closer Location (Set Up a Local Mirror if the Network Is Slow) |
| [4](#4-save-and-restore-the-dists-directory) | Distribution Availability | Save and Restore the `dists` Directory |
| [5](#5-save-and-restore-the-generated-gradle-jars-directory) | Priming the Distribution | Save and Restore the `generated-gradle-jars` Directory |
| [6](#6-save-and-restore-the-wrapper-generated-gradle-jars-and-jars-9-directories) | Priming the Distribution | First-Use Elements (Save and Restore the `wrapper`, `generated-gradle-jars`, and `jars-9` Directories) |

It’s important to keep in mind that these optimizations can be combined, but the right combination depends on your CI setup, network, and Gradle version alignment. Test each one and combine as needed for the best results.

## Distribution Availability

Most builds use the Gradle Wrapper (`gradlew`) to download and run a pinned Gradle version, keeping builds consistent across machines, CI, and contributors, regardless of any locally installed Gradle.

When you run the wrapper, it fetches the designated Gradle version from [https://services.gradle.org/distributions/](https://services.gradle.org/distributions/), which hosts official Gradle releases in two main formats:

- `-bin` distribution: A minimal package containing only what’s needed to run Gradle (e.g., runtime libraries and scripts).
- `-all` distribution: A larger package that includes everything from the `-bin` distribution, plus source code, API documentation (Javadoc), and Gradle samples.

The chosen distribution is specified in the `gradle-wrapper.properties` file:

```
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip
```

### #1 Use the -bin Distribution Instead of -all

For ephemeral CI environments, prefer the `-bin` distribution due to its smaller size and quicker download times, significantly improving startup performance.

The **Spring Boot Project** Dockerfile already uses the `gradle-9.5.1-bin.zip` distribution. However, just to get an idea of how much we are already saving, we update the `gradle-wrapper.properties` to use the `-all` distribution:

```
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-all.zip
```

**Resulting Build Time:**

```
[+] Building 40.9s (11/11) FINISHED
```

| Scenario | Build Time |
| --- | --- |
| Baseline (`-bin` distribution) | 31.7s |
| Un-optimized (`-all` distribution) | 40.9s |
| Total Time Saved | 9.2s |

Using the lighter `-bin` distribution avoids 9.2s of download overhead, a **22%** improvement over the `-all` build (9.2s ÷ 40.9s). Note the gain is measured against the slower `-all` time, not the faster `-bin` baseline.

Confirming your wrapper uses the `-bin` distribution is a quick win that compounds across daily builds.

### #2 Leverage Gradle Docker Images

Containerized CI setups often [ship Gradle inside the image](https://hub.docker.com/_/gradle), which removes the need to download Gradle on every build.

The **Spring Boot Project** Dockerfile is updated to use a Docker image with Gradle already included:

```dockerfile
# Use the official Gradle image
FROM gradle:9.5.1-jdk17 AS build

# Copy Spring Boot project to app
WORKDIR /app
COPY . .

# Build with the image's own Gradle (NOT the wrapper) to actually leverage the image
RUN gradle build --no-daemon

# Extract the built JAR
RUN mkdir -p target && cp build/libs/demo-0.0.1-SNAPSHOT.jar target/app.jar

# Run Spring Boot app
CMD ["java", "-jar", "target/app.jar"]
```

**Resulting Build Time:**

```
[+] Building 34.6s (11/11) FINISHED   # via ./gradlew (the wrapper)
[+] Building 25.0s (11/11) FINISHED   # via gradle build (the image's Gradle)
```

| Scenario | Build Time |
| --- | --- |
| Baseline (stock JDK image) | 31.7s |
| Gradle image, via `./gradlew` | 34.6s |
| Gradle image, via `gradle build` | 25.0s |

How you invoke Gradle inside the image decides everything. If you keep calling the wrapper (`./gradlew`), it re-downloads its pinned distribution into the Gradle User Home and ignores the Gradle already in the image, so you carry a heavier base image for no gain and end up about **9% slower** than baseline. If you instead call the image’s own Gradle (`gradle build`), you skip that download and come out about **21% faster** than baseline.

So the official Gradle image helped only when the build used the Gradle installation already present in the image. If you take this route, pin the image tag and keep it aligned with the Wrapper version your project expects. If you continue invoking `./gradlew`, the image’s preinstalled Gradle distribution does not remove the Wrapper download cost.

### #3 Set Up Local Mirrors if the Network Is Slow

If your access to Gradle’s official distribution servers is slow or unreliable, configuring a local mirror can substantially reduce download times. The Gradle Wrapper can download the distribution from any URL configured in `gradle-wrapper.properties`, so teams can fetch distributions from a closer, more reliable source.

In this test scenario, we set up a local Python HTTP server on our machine to serve the Gradle `-bin` distribution, eliminating the latency involved in downloading from external servers.

```shell
python3 -m http.server 8000
```

The **Spring Boot Project** `gradle-wrapper.properties` is updated to fetch the Gradle distribution from our local mirror:

```
distributionUrl=http\://host.docker.internal:8000/gradle-9.5.1-bin.zip
```

**Resulting Build Time:**

```
[+] Building 25.9s (11/11) FINISHED
```

| Scenario | Build Time |
| --- | --- |
| Baseline (No mirror) | 31.7s |
| Optimized (Local mirror) | 25.9s |
| Total Time Saved | 5.8s |

By simply serving the Gradle distribution locally, we achieved an **18%** improvement in build speed.

If your network is slow or unreliable, a local mirror makes builds faster and more predictable.

### #4 Save and Restore the dists Directory

Every time the Gradle Wrapper (`gradlew`) executes, it downloads and stores the required Gradle distribution in the Gradle User Home directory `~/.gradle/wrapper/dists`. In ephemeral environments, this directory is typically lost between builds, leading to unnecessary redownloads and longer build times.

To avoid this inefficiency, we implemented a caching strategy that saves the `dists` directory created in our Prime Project and restores it in the **Spring Boot Project**. Note that `gradle-cache` here refers to the **Prime image built once** (shown above) and reused (not rebuilt on every run), so what’s measured is the cache *restore*, not the priming. (The same applies to the `generated-gradle-jars` and primed-image scenarios below.) The **Spring Boot Project** Dockerfile is updated accordingly:

```dockerfile
FROM eclipse-temurin:17-jdk AS build

WORKDIR /app
COPY . .

# Copy (Save and Restore) cached dirs from gradle-cache
COPY --from=gradle-cache /root/.gradle/wrapper/dists /root/.gradle/wrapper/dists

# Run build using the Gradle Wrapper
RUN ./gradlew build --no-daemon

# Extract the built JAR
RUN mkdir -p target && cp build/libs/demo-0.0.1-SNAPSHOT.jar target/app.jar

# Run Spring Boot app
CMD ["java", "-jar", "target/app.jar"]
```

**Resulting Build Time:**

```
[+] Building 24.7s (14/14) FINISHED
```

| Scenario | Build Time |
| --- | --- |
| Baseline (No caching) | 31.7s |
| Optimized (Wrapper distribution caching) | 24.7s |
| Total Time Saved | 7.0s |

With this caching strategy in place, we achieved a **22%** speed improvement, cutting redundant downloads on every build.

## Distribution First Use

After the distribution is downloaded and extracted, the first time a given Gradle version runs it performs one-time initialization the ZIP doesn’t ship with: it generates internal API JARs (`caches/<version>/generated-gradle-jars`), processes and instruments others (`caches/jars-9`), and compiles init and build scripts into the Gradle User Home. This first-use step typically adds several seconds on top of the download.

On ephemeral CI you pay it on *every* build, since the Gradle User Home is wiped each time. The two optimizations below cache these first-use artifacts by hand. Develocity’s **Setup Cache** targets this initialization work automatically, caching setup state such as compiled scripts, the task graph, and file hashes across builds; we return to it in detail [below](#the-problem-with-manual-caching).

## Priming the Distribution

We call pre-generating and restoring those first-use artifacts *priming* the distribution; the next two scenarios do it by hand.

### #5 Save and Restore the generated-gradle-jars Directory

Gradle stores generated JAR files in the Gradle User Home at `~/.gradle/caches/<version>/generated-gradle-jars`. These cached JARs help Gradle avoid regenerating internal runtime artifacts. In ephemeral environments, this directory is typically lost between builds.

To avoid this inefficiency, we save the `generated-gradle-jars` directory created in our **Prime Project** and restore it in the **Spring Boot Project**. The **Spring Boot Project** Dockerfile is updated accordingly:

```dockerfile
FROM eclipse-temurin:17-jdk AS build

WORKDIR /app
COPY . .

# Copy (Save and Restore) cached dirs from gradle-cache
COPY --from=gradle-cache /root/.gradle/caches/9.5.1/generated-gradle-jars /root/.gradle/caches/9.5.1/generated-gradle-jars

# Run build using the Gradle Wrapper
RUN ./gradlew build --no-daemon

# Extract the built JAR
RUN mkdir -p target && cp build/libs/demo-0.0.1-SNAPSHOT.jar target/app.jar

# Run Spring Boot app
CMD ["java", "-jar", "target/app.jar"]
```

**Resulting Build Time:**

```
[+] Building 30.0s (14/14) FINISHED
```

| Scenario | Build Time |
| --- | --- |
| Baseline (No caching) | 31.7s |
| Optimized (Generated JARs caching) | 30.0s |
| Total Time Saved | 1.7s |

Caching the generated JAR files reduces build time by 5%.

It’s modest on its own, but it removes redundant computation that adds up across many builds. Recent Gradle versions also generate these runtime JARs much faster ([gradle/gradle#36952](https://github.com/gradle/gradle/issues/36952)), so the win here is bigger if you are on versions lower than Gradle 9.5.0.

### #6 Save and Restore the wrapper, generated-gradle-jars, and jars-9 Directories

To avoid repeatedly recreating these elements in ephemeral setups, we run an initial Gradle task (e.g., `./gradlew help`) and then cache and reuse all generated directories.

The `wrapper`, `generated-gradle-jars`, and `jars-9` folders are generated in the **Prime Project** and restored in the **Spring Boot Project**. The **Spring Boot Project** Dockerfile is updated accordingly:

```dockerfile
FROM eclipse-temurin:17-jdk AS build

WORKDIR /app
COPY . .

# Copy (Save and Restore) cached dirs from gradle-cache
COPY --from=gradle-cache /root/.gradle/wrapper /root/.gradle/wrapper
COPY --from=gradle-cache /root/.gradle/caches/9.5.1/generated-gradle-jars /root/.gradle/caches/9.5.1/generated-gradle-jars
COPY --from=gradle-cache /root/.gradle/caches/jars-9 /root/.gradle/caches/jars-9

# Run build using the Gradle Wrapper
RUN ./gradlew build --no-daemon

# Extract the built JAR
RUN mkdir -p target && cp build/libs/demo-0.0.1-SNAPSHOT.jar target/app.jar

# Run Spring Boot app
CMD ["java", "-jar", "target/app.jar"]
```

**Resulting Build Time:**

```
[+] Building 24.0s (14/14) FINISHED
```

| Scenario | Build Time |
| --- | --- |
| Baseline (No caching) | 31.7s |
| Optimized (Prime distribution caching) | 24.0s |
| Total Time Saved | 7.7s |

By caching the Gradle `wrapper` and generated JAR directories, we achieved a **24%** reduction in build time.

Maintaining a pre-baked, primed Gradle image keeps these startup components ready on every run, improving both speed and reproducibility.

That convenience comes with upkeep. The restore steps copy from a version-specific path (`caches/9.5.1/...`), so a Gradle upgrade silently invalidates them until the Dockerfile is changed. The cached contents come from a separate Prime Project that has to stay in sync, and the `COPY --from` step used here only stands in for a real CI cache. Production setups add their own invalidation, sizing, and security handling. None of this is difficult for a single project; the cost is that it has to be done, and then maintained, for every project.

## The Problem with Manual Caching

While the optimizations above demonstrate measurable improvements, implementing and maintaining them reveals a harsh reality: manual caching in ephemeral CI is operationally complex and fragile.

Consider what we’ve built so far:

- Custom Docker images with primed Gradle distributions
- Multi-stage Dockerfiles copying specific cache directories
- Separate caching strategies for different components (wrapper, generated JARs, etc.)

Now multiply this complexity across:

- Multiple projects with different Gradle versions
- Dozens or hundreds of developers
- Various CI environments (GitHub Actions, Jenkins, GitLab CI, etc.)

The maintenance burden becomes overwhelming. Every new project needs custom Dockerfile logic. Every team member needs to understand the caching architecture. And when a build slows back down, working out which cache stopped being restored is a frustrating, time-consuming hunt.

If your builds run on **GitHub Actions**, much of this is already solved for you. The official [`setup-gradle`](https://github.com/gradle/actions) action caches the Gradle User Home between jobs (the Wrapper distributions, the Dependency Cache, and the Configuration Cache) from a single workflow step. It’s free, maintained by Gradle, and handles the save/restore keys and cleanup that the Dockerfiles above did by hand, so for most GitHub Actions users it’s the easiest and most efficient way to capture the wins from this post without the manual upkeep.

For teams that need this beyond GitHub Actions, the [Develocity Universal Cache](https://gradle.com/develocity/product/universal-cache/) takes the same idea further. Where the `setup-gradle` action is specific to GitHub Actions and Gradle, the Universal Cache works across any CI system and multiple build tools (Gradle, Maven, and npm). Rather than each project assembling and maintaining its own cache layout, it provides a single caching layer spanning setup, dependency artifacts, and task outputs, and that same cache architecture can serve both CI and developer machines.

The difference shows up at scale. The manual techniques above are per-project and per-CI-system: each new repository needs its own Dockerfile logic, each Gradle upgrade means revisiting the copy paths, and each CI platform needs its own cache wiring. That work grows with the number of projects, Gradle versions, and pipelines you run. Universal Cache is deployed once and shared, so adding a project or bumping a version doesn’t mean more work.

Universal Cache integrates three layers (the **Setup Cache**, **Artifact Cache**, and **Build Cache**), matching the phases this series covers.

### Automated Priming

Our most effective startup optimization (24%) required a separate Prime Project, an initial Gradle run, and hand-copied, version-specific cache directories. The **Setup Cache** produces the same result without that scaffolding:

- It caches the Gradle initialization state (compiled scripts, the task graph, and input file hashes), the compute-intensive work that runs before execution begins.
- It restores on every build automatically, with no Prime Project and no cache-copy steps in your Dockerfile.
- It tracks Gradle versions itself, so an upgrade doesn’t silently invalidate a hardcoded path.
- It supports Gradle 8.1+ (as well as Maven and npm) and can run on Develocity Edge, close to CI agents and developer machines for low-latency reads.

In effect, the multi-stage Dockerfile, the Prime Project, and the version-pinned `COPY --from` steps shown earlier can collapse into managed cache configuration, and the same cache architecture can benefit developers locally, not just CI.

It also changes how caching is debugged. Because the cache is a managed, observable service rather than directories copied between image layers, hit rates and cache activity are visible per build through [Build Scans](https://docs.gradle.org/current/userguide/build_scans.html). A cache miss or a stale entry is something you can see, rather than something inferred from an unexpectedly slow build.

The Setup Cache is one of the three layers. The **Artifact Cache** targets dependency downloads, and the **Build Cache** targets task outputs; these become more important as we move into the Configuration and Execution phases covered in Parts 2 and 3.

In larger, cacheable builds, teams adopting the Universal Cache can see build-time reductions of up to 50%, depending on project structure, network distance, dependency volume, and cache hit rates.

## Summary

Optimizing Gradle on ephemeral CI starts with the cost every fresh environment pays before useful build work begins: obtaining the Gradle distribution, initializing Gradle runtime files, and rebuilding parts of the Gradle User Home.

In this benchmark, the biggest startup wins came from avoiding repeated distribution work:

- using the `-bin` distribution instead of `-all`,
- serving the distribution from a faster local mirror,
- restoring the Wrapper distribution directory, or
- using a primed image with the Wrapper distribution and first-use Gradle runtime files already present.

The best result in this setup was the primed-image approach, which reduced the build from 31.7s to 24.0s. The official Gradle Docker image also helped when the build used the Gradle installation already present in the image, reducing the build to 25.0s.

In Part 2, we’ll move from startup costs to the **Configuration Phase** and look at how to reduce the work Gradle does before task execution begins.