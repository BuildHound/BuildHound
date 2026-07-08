---
title: "State of the Configuration Cache - On the Road to Gradle 9"
source: "https://blog.gradle.org/road-to-configuration-cache"
author:
published: 2025-03-14
created: 2026-07-07
description: "Introduction"
tags:
  - "clippings"
---
Explores Gradle’s journey to making builds faster and more efficient by introducing Configuration Cache, a feature that significantly reduces build times by caching and reusing the results of the configuration phase.

## Introduction

As Gradle 9.0 approaches, we’re sharing updates on the Configuration Cache—a key feature that significantly improves configuration time for large projects. In this major release, we plan to make the Configuration Cache the preferred execution mode, with the goal of enabling it by default in Gradle 10.0.

The Configuration Cache is one of Gradle’s most anticipated features; progress has been substantial. As Gradle Fellow Tony Robalik noted:

> “I’m excited about all the work Gradle has done to make the Configuration Cache stable and the preferred mechanism for running builds. At work, we’ve observed that enabling Configuration Cache globally will recover roughly 4 years of lost engineering time annually. The configuration cache also lays the foundation for Isolated Projects, which has been an eagerly awaited feature since Gradle first announced it several years ago!”.

Read this post to learn more about recent Configuration Cache performance and compatibility improvements, how to adopt it, and our plans for future releases.

## A Bit of History

Development often happens in small increments—you write some code, run tests, fix failures, and repeat. In a build tool, this means executing the same tasks repeatedly.

Gradle’s execution model consists of three phases:

1. **Initialization** – Discovers the project structure.
2. **Configuration** – Builds the task graph.
3. **Execution** – Runs tasks to perform the actual work.

In an incremental workflow, where the same tasks are requested without changes to the build scripts, the configuration phase typically produces an identical task graph each time.

![Build Cache](https://blog.gradle.org/images/2025/road-to-configuration-cache/image4.png "Build Cache")

Gradle excels at build caching, which caches the execution phase. This allows even massive projects and mono repositories to build in just a few seconds when no significant changes occur. However, generating the task graph for a complex project during the configuration phase can take a non-trivial amount of time. In some cases—like the project shown in the screenshot below—this overhead may even surpass the actual execution time.

![This Build Scan shows that the configuration phase takes 26 seconds out of total 32 seconds](https://blog.gradle.org/images/2025/road-to-configuration-cache/image1.png "This Build Scan shows that the configuration phase takes 26 seconds out of total 32 seconds")

Re-creating the task graph repeatedly is inefficient. Even for smaller projects with a configuration time of 5-10 seconds, it may be enough to push the total build time over the [magic 10-second boundary](https://www.uxtigers.com/post/time-scales-ux), breaking the developer’s concentration. Eliminating this unnecessary work helps maintain a smooth developer experience.

## Enter the Configuration Cache

Recognizing this inefficiency, Gradle introduced the Configuration Cache in [Gradle 6.6](https://blog.gradle.org/introducing-configuration-caching) as an experimental feature.

![Configuration Cache](https://blog.gradle.org/images/2025/road-to-configuration-cache/image7.png "Configuration Cache")

With the Configuration Cache enabled, the task graph is computed on the first run and then stored. On subsequent builds, when the same tasks are invoked, Gradle retrieves the cached task graph, verifies its validity for the current environment, and skips the configuration phase—proceeding directly to the execution phase. As you might expect, loading a cached task graph is significantly faster than rebuilding it from scratch.

![Storing and Loading the Configuration Cache](https://blog.gradle.org/images/2025/road-to-configuration-cache/image9.png "Storing and Loading the Configuration Cache")

Like many engineering optimizations, this performance boost comes at a cost. To enable caching, build scripts and plugins must follow [strict rules](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:requirements) that allow Gradle to serialize and deserialize the cached graph. This often requires effort from end users and plugin developers to ensure compatibility. However, beyond enabling configuration caching, these constraints also enhance task isolation, unlocking safer parallel execution—even for tasks within the same project.

![The Configuration Cache in Action](https://blog.gradle.org/images/2025/road-to-configuration-cache/image2.gif "The Configuration Cache in Action")

In [Gradle 8.1](https://docs.gradle.org/8.1/release-notes.html), the Configuration Cache was promoted to stable. Since then, its API has adhered to the same compatibility guarantees as other Gradle features.

The Gradle team has worked to make Core Plugins and Gradle itself fully Configuration Cache compatible. We’ve also collaborated with Community plugin maintainers and other stakeholders to refine and improve Configuration Cache adoption.

## Focus on ease of Adoption and Observability

### Plugin Compatibility

We are seeing increasing adoption of Configuration Cache support in the Gradle plugin ecosystem. Among the 50 most popular Gradle plugins, measured by downloads between January and November 2024, more than half already declare compatibility. Hundreds of maintainers and contributors have invested their time, including [dedicated hackathons](https://github.com/gradle/cc-hackathon-2022) and featured projects during [Hacktoberfest](https://community.gradle.org/events/hacktoberfest/2024/).

Prominent plugins, like the [Android Gradle Plugin](https://developer.android.com/build/releases/gradle-plugin), [Kotlin Gradle Plugin](https://kotlinlang.org/docs/gradle-configure-project.html), and [Spring Boot](https://docs.spring.io/spring-boot/gradle-plugin/index.html), have long supported the Configuration Cache. Other plugins like [Quarkus](https://quarkus.io/guides/gradle-tooling) have made significant progress towards adopting the Configuration Cache.

We’ve made substantial improvements to [Core Plugins](https://docs.gradle.org/current/userguide/plugin_reference.html), and below is a list of those that are now Configuration Cache compatible:

| JVM Languages and Frameworks | Native Languages | Code Analysis | Utility |
| --- | --- | --- | --- |
| ✅ Java | ✅ C++ Application | ✅ Checkstyle | ✅ Build Init |
| ✅ Java Library | ✅ C++ Library | ✅ CodeNarc | ✅ Signing |
| ✅ Java Platform | ✅ C++ Unit Test | ✅ JaCoCo | ✅ Java Plugin Development |
| ✅ Groovy | ✅ Swift Application | ✅ JaCoCo Report Aggregation | ✅ Groovy DSL Plugin Development |
| ✅ Scala | ✅ Swift Library | ✅ PMD | ✅ Kotlin DSL Plugin Development |
| ✅ ANTLR | ✅ XC Test | ✅ Test Report Aggregation | ✅ Project Report Plugin |
| ✅ WAR and EAR |  |  |  |
| ⚠️ Maven Publish |  |  |  |

For Community Plugins, a [GitHub Issue](https://github.com/gradle/gradle/issues/13490) tracks their Configuration Cache compatibility status.

While adoption is steadily increasing, there’s still plenty of work, and plugin maintainers could use your help! The Gradle team is here to support contributors with pull request reviews and guidance in the [#configuration-cache](https://slack.gradle.org/) channel on the Gradle Community Slack.

### Reporting Improvements

We continue to improve the [Configuration Cache Report](https://github.com/gradle/configuration-cache-report), making it an essential tool for [diagnosing configuration caching issues](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:troubleshooting). Recent updates enhance usability and visibility, providing more precise insights into cache misses and highlighting tasks declared as `notCompatibleWithConfigurationCache`:

![Configuration Cache Report](https://blog.gradle.org/images/2025/road-to-configuration-cache/image11.png "Configuration Cache Report")

We’re also refining Gradle’s built-in troubleshooting tools with more explicit CLI summaries, improved stack traces for pinpointing issues, and more detailed verbose/debug output. Observability remains a key focus as we enhance performance and compatibility insights.

We’re planning several improvements to build inputs management to enhance visibility and control:

- **More precise input source tracking** – The report will go beyond coarse-grained locations (like build scripts or plugins) to help identify the exact origin of a particular input.
- **Stronger safeguards against unnoticed build inputs** – Engineers will have better tools to detect unexpected inputs without manually parsing the HTML report.
- **Better integrations** – We’re working on deeper integrations with the Problems API and Build Scan® for Gradle to streamline diagnostics and insights.

### Integration with Build Scan® for Gradle

Build Scan® for Gradle offers deeper insights into [Configuration Cache usage](https://gradle.com/develocity/releases/2024.1#new-insights-into-configuration-caching-for-gradle-builds). You can see whether the cached task graph was reused, the size of the cache entry, and which build originally produced it in case of a cache hit:

![This Build Scan shows the Configuration Cache hit](https://blog.gradle.org/images/2025/road-to-configuration-cache/image10.png "This Build Scan shows the Configuration Cache hit")

Recent versions of Gradle and Develocity now display cache miss reasons:

![This Build Scan shows the Configuration Cache miss reason](https://blog.gradle.org/images/2025/road-to-configuration-cache/image6.png "This Build Scan shows the Configuration Cache miss reason")

### Suppressible Input Types

Gradle tracks various files, environment variables, and other external factors accessed during the configuration phase to ensure the cached task graph remains `UP-TO-DATE.` These [build configuration inputs](https://blog.gradle.org/improvements-in-the-build-configuration-input-tracking) improve build correctness but can initially reduce cache hit rates until scripts and plugins adapt.

Gradle 8.3 introduced a policy allowing new input types to be suppressible for at least one major version to balance compatibility and performance. This means builds can [opt out](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:adoption:changes_in_behavior) of detecting new inputs introduced in Gradle 8.x until at least Gradle 9, giving teams more time to transition.

## Focus on Performance and Hit Rate

### IDE Experience

In collaboration with JetBrains, we’ve enhanced the test launch experience. Starting with Gradle 8.4 and IntelliJ IDEA 2023.3, the Configuration Cache entry can now be reused when running different tests within the same test task. For example, running `FooTest` stores the cache entry, which can be reused when running `BarTest`.

In Gradle 8.7, we fixed Configuration Cache compatibility for IDEA-generated tasks that run `public static void main()` methods. Later, IntelliJ IDEA 2024.3 further improved the cache hit rate when alternating between running and debugging applications and tests. As a result, many day-to-day development tasks now run significantly faster.

### Size Optimizations

Configuration cache entries for large projects can take up significant disk space. Gradle 8.10 improved the stored format by deduplicating stored data. The cached entry for a [synthetic project](https://github.com/gradle/perf-android-large-2/tree/android-80-kotlin-18) we use for benchmarking shrunk four times from 270 MB to 65 MB.

![](https://www.youtube.com/watch?v=VyVF263DFx0)

For example, Google reported a 3.75x reduction in Configuration Cache size for AndroidX builds. While this came with increased storage time due to data serialization, the following feature addresses that overhead. The reduction significantly improved loading times, which is crucial since the cache is loaded multiple times during Configuration Cache hits:

![Configuration Cache string de-duplication strategy](https://blog.gradle.org/images/2025/road-to-configuration-cache/image5.png "Configuration Cache string de-duplication strategy")

### Parallel Configuration Store and Load

With the Configuration Cache, Gradle resolves build dependencies when storing the task graph. Previously, this was done on a single thread to maintain the usual concurrency guarantees—ensuring the configuration phase always runs sequentially. However, with the introduction of [Isolated Projects](https://docs.gradle.org/current/userguide/isolated_projects.html), currently in pre-alpha, parallel configuration is now possible. We plan to make Isolated Projects an incubating feature later this year, which may introduce further improvements.

This single-threaded approach initially made builds using the Configuration Cache slower than those using [`--parallel`](https://docs.gradle.org/current/userguide/performance.html#parallel_execution) without it. In contrast, [`--parallel`](https://docs.gradle.org/current/userguide/performance.html#parallel_execution) can often resolve dependencies concurrently for tasks in different projects, improving performance.

Starting with Gradle 8.11, [*parallel configuration caching*](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:usage:parallel) allows storing task graphs of different projects in parallel, significantly speeding up cache entry preparation. Similar to parallel task execution, this feature is opt-in since it introduces additional concurrency where execution was previously single-threaded. However, builds already using parallel execution can typically enable parallel configuration caching safely.

Restoring cached graphs is inherently safer due to isolation, so parallel loading is enabled by default. One early adopter reported a 50% reduction in configuration time for a build with ~600 projects, cutting it from 2m4s to 55s while reducing cache size from 700 MB to 400 MB.

For the synthetic project we use for benchmarking, we see a two-times speedup in both storing (with the configuration phase of the cache miss build going from 27 to 15 seconds) and loading (with the configuration phase of the cache hit build going from 3.6 to 1.5 seconds).

![Configuration Cache store and load times](https://blog.gradle.org/images/2025/road-to-configuration-cache/image3.png "Configuration Cache store and load times")

## Initiatives for 9.x and beyond

### Preferred Mode of Execution

Starting with Gradle 9.0, the Configuration Cache will be the preferred mode of execution. Gradle will gently prompt builds that haven’t enabled it yet, and new projects created with `gradle init` will have it enabled by default. This shift lays the foundation for future scalability improvements, such as Isolated Projects, while also simplifying the Gradle codebase.

However, we can’t enable the Configuration Cache universally just yet. Some builds rely on assumptions that may not hold when it’s enabled—such as expecting tasks to run sequentially or sharing mutable Java objects between tasks. While Gradle detects many of these cases and reports them as errors, some patterns, though valid, may behave differently. Enabling the Configuration Cache remains a deliberate choice to avoid disrupting existing builds.

While the [original plan](https://newsletter.gradle.org/2024/07#gradle-90---revised-configuration-cache-adoption-plan) was to deprecate builds that don’t use the Configuration Cache, we’ve opted for a more gradual approach—encouraging adoption while allowing time for projects that need adjustments. Our long-term goal remains the same: making the Configuration Cache the only execution mode in a future major release after Gradle 9.0.

We will deprecate and remove APIs incompatible with the Configuration Cache to support this transition over upcoming releases. This makes writing non-compliant code more difficult, even if your build isn’t ready to adopt it yet. Some issues can only be detected when using the Configuration Cache itself, so consider any errors it reports as a deprecation warning, even if they don’t appear when the Configuration Cache is disabled.

Gradle will continue improving Configuration Cache support across the ecosystem, and we encourage all projects to enable it now to take advantage of its performance and maintainability benefits.

### Currently Incompatible Features

Several Gradle features and built-in plugins are not yet fully compatible with the Configuration Cache.

- [**Source dependencies**](https://blog.gradle.org/introducing-source-dependencies)
	- Source dependencies is a less-known feature of Gradle that allows including builds from Git repositories. We plan to support this feature with the Configuration Cache over the 9.x timeline. In the meantime, you can evaluate a [community-provided substitution](https://melix.github.io/includegit-gradle-plugin/latest/index.html).
- [**Ant integration**](https://docs.gradle.org/current/userguide/migrating_from_ant.html)
	- [Consuming Ant projects](https://docs.gradle.org/current/userguide/migrating_from_ant.html#migant:imported_builds) and running Ant tasks through Gradle will not be supported with the Configuration Cache as is. We plan to replace the current integration with a compatible solution with a reduced feature set. In the meantime, we encourage developers to finish their migration off Ant builds.
- **IDE plugins**
	- IDE plugins like [IDEA](https://docs.gradle.org/current/userguide/idea_plugin.html#idea_plugin) and [Eclipse](https://docs.gradle.org/current/userguide/eclipse_plugin.html#eclipse_plugin) support configuring the imported projects and generating project files. These plugins are not yet fully supported with the Configuration Cache. Modern IDE versions can already import Gradle projects without Gradle generating project files so this feature will be deprecated and removed. However, the support for configuring the projects and underlying tooling models, like marking some source directories as tests, which is also provided by these plugins, will be kept and made compatible.

We’re also aware of some rough edges and corner cases in other features, which we want to address eventually, even though they aren’t preventing most builds from using the Configuration Cache. You can check the [Gradle Roadmap entry](https://github.com/gradle/gradle/issues/28341) to see what is being worked on.

### Improving Performance and Cache Hit Rate

So far, the Configuration Cache behaves similarly to a task’s `UP-TO-DATE` check—it only caches the latest invocation of a given task. This means that changing the environment back and forth invalidates the cache each time.

For example, you might run tests, bump a dependency version, rerun the tests, and decide the new version doesn’t work. If you roll back the change and re-run the tests, you won’t get a Configuration Cache hit, even though you previously ran the same configuration. This happens because running the tests with the updated dependency overwrites the previous cache entry.

We plan to address this soon by allowing multiple cache entries to be stored for the same Gradle invocation. This will also be useful when working across multiple branches based on the same revision of the `main` branch, enabling smoother switching between them.

### Configuration Cache on CI

The current priority for the Configuration Cache is to speed up local incremental builds. Early design decisions prioritized local performance, sometimes making CI adoption more challenging.

Before Gradle 8.11, sequential dependency resolution slowed Configuration Cache builds down, especially in ephemeral CI environments. With parallel configuration caching, cached builds perform on par with non-cached builds. The overhead of storing the state is often offset by intra-project parallel task execution.

However, achieving cache reuse between builds on CI is a different challenge. It may be feasible for stateful CI environments that retain build state, but it is significantly more challenging for ephemeral CI and clean environments. Several hurdles must be overcome:

- Configuration Cache entries are not relocatable because they contain many absolute file paths. This limitation can be mitigated by ensuring that all CI machines have an identical checkout directory and [`GRADLE_USER_HOME`](https://docs.gradle.org/current/userguide/gradle_directories.html#gradle_user_home) location. However, non-relocatability also makes it difficult to share cached data between developers.
- When included builds or `buildSrc` contribute to build logic, Gradle expects all their outputs to be present for a cache hit.

We recognize that cache reuse on CI is important and have many ideas for improving the situation. However, this work is still in its early stages and has many technical challenges, so the timeline is not yet defined.

Nevertheless, we recommend enabling the Configuration Cache on CI builds if you’re using it locally. This way, you can catch newly introduced breakages earlier and benefit from intra-project parallel execution but do not focus too much on achieving cache reuse.

## Prepare Your Projects for the Configuration Cache

The Configuration Cache is an essential feature for all Gradle users, and a major update across the Gradle ecosystem is required to enable its full potential. We encourage the community, including plugin maintainers and end users, to invest some time supporting the Configuration Cache in their plugins and build scripts.

If you have not done it already, it is also a good time to [try enabling the Configuration Cache in your builds](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:adoption). If you experience any plugin compatibility issues, please report them in the respective repositories and reference them in the [compatibility tracker](https://github.com/gradle/gradle/issues/13490).

You can also contact us on [our forums](https://discuss.gradle.org/) or in the `#configuration-cache` channel of the [Gradle Community Slack](https://slack.gradle.org/). We are happy to provide advice and reviews to developers working on Configuration Cache compatibility!

### How Can You Help?

The plugin ecosystem still requires significant work, and plugin maintainers need your help!  
If you’re interested in contributing, here’s how you can get involved:

- Try out the Configuration Cache in your projects
	- [Follow the adoption guide](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:adoption) to get started.
- Report any issues you encounter
	- **If it’s a Gradle feature or Core Plugin issue** → [Report it on gradle/gradle](https://github.com/gradle/gradle/issues).
		- **If it’s a third-party plugin issue** → Check the [known compatibility list](https://github.com/gradle/gradle/issues/13490) and report it to the plugin’s issue tracker.
- Contribute to the documentation
	- Help improve the [Configuration Cache Cookbook](https://cookbook.gradle.org/plugin-development/configuration-cache/) or submit updates to the FAQs.