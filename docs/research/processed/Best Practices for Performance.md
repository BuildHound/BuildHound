---
title: "Best Practices for Performance"
source: "https://docs.gradle.org/current/userguide/best_practices_performance.html"
author:
published:
created: 2026-07-07
description:
tags:
  - "clippings"
---
Gradle publishes two distribution variants for each release: `-bin` (binaries only) and `-all` (binaries, sources, and documentation). For most builds, you should prefer the smaller `-bin` distribution.

Using `-bin` reduces download size and verification effort, speeds up CI and developer builds, and limits the number of artifacts you need to trust.

Each Gradle release provides:

In modern setups:

- IDEs and build tools can download sources and documentation directly from repositories or online docs (even when using `-bin` releases).
- The `-all` distribution is rarely required outside of specific offline or air-gapped environments.

Preferring `-bin` helps because:

- It **reduces download and cache size** for CI and local builds.
- There is **less to verify and fewer artifacts to trust** (one smaller archive instead of a larger “everything included” zip).
- It **shortens the feedback loop** when upgrading Gradle.

In special cases (for example, fully offline environments), you can still use `-all`, but it should be a conscious exception rather than the default.

Make sure your Wrapper doesn’t point to the `-all` distribution:

`gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-<version>-all.zip
```

Configure the Wrapper to use the `-bin` distribution:

`gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-<version>-bin.zip
```

[`#upgrades`](https://docs.gradle.org/current/userguide/tags_reference.html#tag:upgrades), [`#wrapper`](https://docs.gradle.org/current/userguide/tags_reference.html#tag:wrapper)

Set `UTF-8` as the default file encoding to ensure consistent behavior across platforms.

Use `UTF-8` as the default file encoding to ensure consistent behavior across environments and avoid caching issues caused by platform-dependent default encodings.

This is especially important when working with build caching, since differences in file encoding between environments can cause unexpected cache misses.

To enforce `UTF-8` encoding, add the following to your `gradle.properties` file:

```properties
org.gradle.jvmargs=-Dfile.encoding=UTF-8
```

|  | Do not rely on the default encoding of the underlying JVM or operating system, as this may differ between environments and lead to inconsistent behavior. |
| --- | --- |

Use the Build Cache to save time by reusing outputs produced by previous builds.

The Build Cache avoids re-executing tasks when their inputs haven’t changed by reusing outputs from previous builds.

This prevents redundant work. If the inputs are the same, the outputs will be too, resulting in faster, more efficient builds.

Build caching is disabled by default:

To enable the Build Cache, add the following to your `gradle.properties` file:

When you build your project for the first time, Gradle populates the cache with the outputs of tasks like compilation.

Even if you run `./gradlew clean` to delete the build directory, Gradle can reuse cached outputs in subsequent builds.

```bash
$ ./gradlew clean
```

```text
:clean
BUILD SUCCESSFUL
```

On subsequent builds, instead of executing the `:compileJava` task again, the outputs of the task will be loaded from the Build Cache:

```bash
$ ./gradlew compileJava
```

```text
> Task :compileJava FROM-CACHE

BUILD SUCCESSFUL in 0s
1 actionable task: 1 from cache
```

Use the Configuration Cache to significantly improve build performance by caching the result of the configuration phase and reusing it in subsequent builds.

The Configuration Cache works by saving the result of the configuration phase. On the next build, if nothing relevant has changed, Gradle skips configuration entirely and loads the cached task graph from disk, jumping straight to task execution.

This can dramatically reduce build time for large builds, but it’s just as valuable for smaller builds where configuration overhead can dominate short iterations. Faster feedback helps developers stay focused, without waiting on redundant configuration work.

It’s important to understand how this differs from the Build Cache. The Build Cache stores the outputs of task execution, while the Configuration Cache stores the configured task graph before execution begins. These are independent mechanisms that solve different problems, but they are designed to work together for optimal performance.

|  | The Configuration Cache is the preferred way to execute Gradle builds, but it is not enabled by default. Many existing builds and plugins are not yet fully compatible, and adopting it may involve refactoring of build logic. Enabling it by default could lead to unexpected build failures, so Gradle uses an opt-in adoption model to allow teams to verify compatibility and adopt configuration caching incrementally and safely. |
| --- | --- |

Configuration Caching is not enabled by default:

To enable the Configuration Cache, add the following to your `gradle.properties` file:

When you build your project for the first time, Gradle stores the outcome of the configuration phase, including the task graph, in the Configuration Cache.

```bash
$ ./gradlew compileJava
```

```text
Configuration cache entry stored.
> Task :processResources NO-SOURCE
> Task :processTestResources NO-SOURCE
> Task :compileJava
> Task :classes
> Task :compileTestJava NO-SOURCE
> Task :testClasses UP-TO-DATE
> Task :test NO-SOURCE
> Task :check UP-TO-DATE
> Task :jar
> Task :assemble
> Task :build

BUILD SUCCESSFUL in 0s
2 actionable tasks: 2 executed
```

On subsequent builds, instead of reconfiguring tasks like `:compileJava`, Gradle loads the task graph from the Configuration Cache and proceeds directly to execution.

```bash
$ ./gradlew compileJava
```

```text
Configuration cache entry reused.
> Task :processResources NO-SOURCE
> Task :processTestResources NO-SOURCE
> Task :compileJava
> Task :classes
> Task :compileTestJava NO-SOURCE
> Task :testClasses UP-TO-DATE
> Task :test NO-SOURCE
> Task :check UP-TO-DATE
> Task :jar
> Task :assemble
> Task :build

BUILD SUCCESSFUL in 0s
2 actionable tasks: 2 executed
```

Avoid expensive computations in the [configuration phase](https://docs.gradle.org/current/userguide/build_lifecycle.html#build_phases), instead, move them to task actions.

In order for Gradle to execute tasks it first needs to build the project task graph. As part of discovering what tasks to include in the task graph, Gradle will configure all the tasks that are directly requested, any task dependencies of the requested tasks, and also any tasks that are not lazily registered. This work is done in the configuration phase.

Performing expensive or slow operations such as file or network I/O, or CPU-heavy calculations in the configuration phase forces these to run even when they might be unnecessary to complete the requested work of the invoked tasks. It is better to move these operations to task actions so that they run only when required.

```kotlin
KotlinGroovy
```
```groovy
abstract class MyTask extends DefaultTask {
    @Input
    String computationResult
    @TaskAction
    void run() {
        logger.lifecycle(computationResult)
    }
}

String heavyWork() {
    logger.lifecycle("Start heavy work")
    Thread.sleep(5000)
    logger.lifecycle("Finish heavy work")
    return "Heavy computation result"
}

tasks.register("myTask", MyTask) {
    computationResult = heavyWork() (1)
}
```

| **1** | Performing heavy computation during configuration phase. |
| --- | --- |

```kotlin
KotlinGroovy
```
```groovy
abstract class MyTask extends DefaultTask {
    @TaskAction
    void run() {
        logger.lifecycle(heavyWork()) (1)
    }
    String heavyWork() {
        logger.lifecycle("Start heavy work")
        Thread.sleep(5000)
        logger.lifecycle("Finish heavy work")
        return "Heavy computation result"
    }
}

tasks.register("myTask", MyTask)
```

| **1** | Performing heavy computation during execution phase in a task action. |
| --- | --- |

`#tasks`