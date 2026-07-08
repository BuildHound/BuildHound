---
title: "Improve the Performance of Gradle Builds"
source: "https://docs.gradle.org/current/userguide/performance.html"
author:
published:
created: 2026-07-07
description:
tags:
  - "clippings"
---
Build performance is essential to productivity. The longer a build takes, the more it disrupts your development flow. Since builds run many times a day, even small delays add up. The same applies to Continuous Integration (CI).

Investing in build speed pays off. This section explores ways to optimize performance, highlights common pitfalls, and explains how to avoid them.

| # | Recommendation |
| --- | --- |
| 1 | [Update Versions](#sec:update-versions) |
| 2 | [Enable Parallel Execution](#sec:enable_parallel_execution) |
| 3 | [Enable the Daemon](#enable_daemon) |
| 4 | [Enable the Build Cache](#enable_build_cache) |
| 5 | [Enable the Configuration Cache](#enable_configuration_cache) |
| 6 | [Enable Incremental Build for Custom Tasks](#enable_inc_builds_custom_tasks) |
| 7 | [Create Builds for specific Developer Workflows](#enable_specific_dev_workflows) |
| 8 | [Increase Heap Size](#increase_heap_size) |
| 9 | [Optimize Configuration](#optimize_configuration) |
| 10 | [Optimize Dependency Resolution](#optimize_dependency_resolution) |
| 11 | [Optimize Java Projects](#optimize_java_projects) |
| 12 | [Optimize Android Projects](#optimize_android_projects) |
| 13 | [Improve Older Gradle Releases](#improve_older_projects) |

Before making any changes, [inspect your build](https://docs.gradle.org/current/userguide/inspect.html#inspecting_build_scans) with a [**Build Scan**](https://gradle.com/develocity/product/build-scan/) or **profile report**. A thorough inspection helps you understand:

- **Total build time**
- **Which parts of the build are slow**

This provides a baseline to measure the impact of optimizations.

To get the most value from this page:

- Inspect your build.
- Apply a change.
- Inspect your build again.

If the change improves build times, keep it. If it doesn’t, revert the change and try another approach.

For reference, the following Build Scan snapshot is a build of a project created using `gradle init`. It is a Java (JDK 21) `Application and library project` using Kotlin build files:

![performance 1](https://docs.gradle.org/current/userguide/img/performance/performance-1.png)

It builds in **21 seconds** using Gradle 8.10.

Each Gradle release brings performance improvements. Using an outdated version means missing out on these gains. Upgrading is low-risk since Gradle maintains backward compatibility between minor versions. Staying up to date also makes major version upgrades smoother by providing early deprecation warnings.

You can use the [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html#gradle_wrapper) to update the version of Gradle by running `gradle :wrapper --gradle-version X.X` where `X.X` is the desired version.

When our reference project is updated to use Gradle 8.13, the build (`./gradlew clean build`) takes **8 seconds**:

![performance 2](https://docs.gradle.org/current/userguide/img/performance/performance-2.png)

Gradle runs on the Java Virtual Machine (JVM), and Java updates often enhance performance. To get the best Gradle performance, use the latest Java version.

|  | Don’t forget to check out [compatibility guide](https://docs.gradle.org/current/userguide/compatibility.html#compatibility) to make sure your version of Java is compatible with your version of Gradle. |
| --- | --- |

Plugins play a key role in build performance. Outdated plugins can slow down your build, while newer versions often include optimizations. This is especially true for the Android, Java, and Kotlin plugins. Keep them up to date for the best performance.

Simply look at all the declared plugins in your project and check if a newer version is available:

```kotlin
plugins {
    id("org.jlleitschuh.gradle.ktlint") version "12.0.0" // A newer version is available on the Gradle Plugin Portal
}
```

Most projects consist of multiple subprojects, some of which are independent. However, by default, Gradle runs only one task at a time.

To execute tasks from different subprojects in parallel, use the `--parallel` flag:

```bash
$ gradle <task> --parallel
```

To enable parallel execution by default, add this setting to `gradle.properties` in the [project root or your Gradle home directory](https://docs.gradle.org/current/userguide/directory_layout.html#directory_layout):

```properties
org.gradle.parallel=true
```

Parallel builds can significantly improve build times, but the impact depends on your project’s structure and inter-subproject dependencies. If a single subproject dominates execution time or there are many dependencies between subprojects, the benefits will be minimal. However, most multi-project builds see a noticeable reduction in build time.

When the parallel flag is used on our reference project, the build (`./gradlew clean build --parallel`) time is **7 seconds**:

![performance 3](https://docs.gradle.org/current/userguide/img/performance/performance-3.png)

A Build Scan provides a visual timeline of task execution in the **"Timeline"** tab.

In the example below, the build initially has long-running tasks at the beginning and end, creating a bottleneck:

![parallel task slow](https://docs.gradle.org/current/userguide/img/performance/parallel-task-slow.png)

Figure 1. Bottleneck in parallel execution

By adjusting the build configuration to run these two slow tasks earlier and in parallel, the overall build time is reduced from **8 seconds** to **5 seconds**:

![parallel task fast](https://docs.gradle.org/current/userguide/img/performance/parallel-task-fast.png)

Figure 2. Optimized parallel execution

By enabling Parallel Execution, you also allow parallelism for Tooling API actions. This means that Tooling API clients, such as IDEs, can do some of their work faster. In practice, this improves the speed of scenarios like IDE sync.

However, enabling Parallel Execution may not work for all builds. In some cases, the additional parallelism may lead to instability if tasks access shared mutable state across project boundaries. In other cases, the parallel Tooling API actions might misbehave for similar reasons.

If you observe such problems after enabling Parallel Execution, you should consider disabling it to preserve the reliability of the build results. At the same time, it is rare for builds to have problems with tasks *and* Tooling API actions at the same time.

Since Gradle 9.4.0, there is an additional option `org.gradle.tooling.parallel` that allows controlling parallelism of Tooling API actions independently of task execution parallelism.

For instance, you can enable Tooling parallelism without task parallelism:

```properties
org.gradle.tooling.parallel=true
```

Alternatively, you can keep task parallelism, but disable Tooling parallelism:

```properties
org.gradle.parallel=true
org.gradle.tooling.parallel=false
```

The Gradle Daemon significantly reduces build times by:

- Caching project information across builds
- Running in the background to avoid JVM startup delays
- Benefiting from continuous JVM runtime optimizations
- Watching the file system to determine what needs to be rebuilt

Gradle enables the Daemon by default, but some builds override this setting. If your build disables it, enabling the Daemon can lead to substantial performance improvements.

To enable the Daemon at build time, use:

```bash
$ gradle <task> --daemon
```

For older Gradle versions, enable it permanently by adding this to `gradle.properties`:

```properties
org.gradle.daemon=true
```

On developer machines, enabling the Daemon improves performance. On CI machines, long-lived agents benefit, but short-lived ones may not. Since Gradle 3.0, Daemons automatically shut down under memory pressure, making it safe to keep the Daemon enabled.

When the daemon is used on our reference project, the build (`./gradlew clean build --daemon`) time is **3 seconds**:

![performance 4](https://docs.gradle.org/current/userguide/img/performance/performance-4.png)

The Gradle Build Cache optimizes performance by storing task outputs for specific inputs. If a task runs again with the same inputs, Gradle retrieves the cached output instead of re-executing the task.

By default, Gradle does not use the Build Cache. To enable it at build time, use:

```bash
$ gradle <task> --build-cache
```

To enable it permanently, add this to `gradle.properties`:

```properties
org.gradle.caching=true
```

You can use:

- A local Build Cache to speed up repeated builds on the same machine.
- A shared Build Cache to accelerate builds across multiple machines.
	- [Develocity provides](https://gradle.com/build-cache/) a shared Cache solution for CI and developer builds.

When the build cache flag is used on our reference project, the build (`./gradlew clean build --build-cache`) time is **5 seconds**:

![performance 5](https://docs.gradle.org/current/userguide/img/performance/performance-5.png)

For more information about the Build Cache, check out the [Build Cache documentation](https://docs.gradle.org/current/userguide/build_cache_use_cases.html#use_cases_cache).

A Build Scan helps you analyze **Build Cache effectiveness** through the **"Build Cache"** tab in the **"Performance"** page. This tab provides key statistics, including:

- The number of tasks that interacted with a cache
- Which cache was used
- Transfer and pack/unpack rates for cached entries

![cache performance](https://docs.gradle.org/current/userguide/img/performance/cache-performance.png)

Figure 3. Inspecting the performance of the build cache for a build

The **"Task Execution"** tab offers insights into task cacheability. Clicking on a category reveals a timeline highlighting tasks in that category:

![task execution cacheable](https://docs.gradle.org/current/userguide/img/performance/task-execution-cacheable.png)

Figure 4. A task-oriented view of performance

![timeline not cacheable](https://docs.gradle.org/current/userguide/img/performance/timeline-not-cacheable.png)

Figure 5. Timeline screen with 'not cacheable' tasks only

To identify optimization opportunities, sort tasks by duration in the timeline view. The Build Scan above reveals that `:task1` and `:task3` could be improved and made cacheable, while also explaining why Gradle didn’t cache them.

|  | This feature has the following limitations:  - Not all [core Gradle plugins](https://docs.gradle.org/current/userguide/configuration_cache_status.html#config_cache:plugins:core) and [features](https://docs.gradle.org/current/userguide/configuration_cache_status.html#config_cache:not_yet_implemented) are supported. Full support is still in progress. - Your build and its plugins may need adjustments to meet the [requirements](https://docs.gradle.org/current/userguide/configuration_cache_requirements.html#config_cache:requirements). - IDE imports and syncs do not use the configuration cache. |
| --- | --- |

The configuration cache speeds up builds by caching the results of the configuration phase. When build configuration inputs remain unchanged, Gradle can skip this phase entirely.

Enabling the configuration cache provides further performance benefits. When enabled, Gradle:

- Executes all tasks in parallel, even within the same subproject.
- Caches dependency resolution results to avoid redundant computations.

Build configuration inputs include:

- Init scripts
- Settings scripts
- Build scripts
- System and Gradle properties used during configuration
- Environment variables used during configuration
- Configuration files accessed via value suppliers (`providers`)
- `buildSrc` inputs, including configuration files and source files

By default, Gradle does not use the configuration cache. To enable it at build time, use:

```bash
$ gradle <task> --configuration-cache
```

To enable it permanently, add this setting to the `gradle.properties` file:

```properties
org.gradle.configuration-cache=true
```

When the configuration cache flag is used on our reference project, the build (`./gradlew clean build --build-cache`) time is **4 seconds**:

![performance 6](https://docs.gradle.org/current/userguide/img/performance/performance-6.png)

For more details, see the [Configuration Cache documentation](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache).

Incremental build is a Gradle optimization that skips tasks that have already executed with the same inputs. If a task’s inputs and outputs have not changed since the last execution, Gradle will skip that task.

Most built-in Gradle tasks support incremental builds. To make a custom task compatible, you must specify its inputs and outputs:

```kotlin
KotlinGroovy
```
```groovy
tasks.register('processTemplatesAdHoc') {
    inputs.property('engine', TemplateEngineType.FREEMARKER)
    inputs.files(fileTree('src/templates'))
        .withPropertyName('sourceFiles')
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property('templateData.name', 'docs')
    inputs.property('templateData.variables', [year: '2013'])
    outputs.dir(layout.buildDirectory.dir('genOutput2'))
        .withPropertyName('outputDir')

    doLast {
        // Process the templates here
    }
}
```

For more details, see the [incremental build documentation](https://docs.gradle.org/current/userguide/incremental_build.html#incremental_build) and the [writing tasks tutorial](https://docs.gradle.org/current/userguide/writing_tasks_intermediate.html#task_inputs_and_outputs).

When leveraging incremental builds on our reference project, the build (`./gradlew clean build build`) time is **5 seconds**:

![performance 7](https://docs.gradle.org/current/userguide/img/performance/performance-7.png)

Look at the Build Scan **"Timeline"** view to identify tasks that could benefit from incremental builds. This helps you understand why tasks execute when you expect Gradle to skip them.

![timeline](https://docs.gradle.org/current/userguide/img/performance/timeline.png)

Figure 6. The timeline view can help with incremental build inspection

In the example above, the task was **not up-to-date** because one of its inputs (**"timestamp"**) changed, forcing it to re-run.

To optimize your build, **sort tasks by duration** to identify the slowest tasks in your project.

The fastest task is one that doesn’t run. By skipping unnecessary tasks, you can significantly improve build performance.

If your build includes multiple subprojects, define tasks that build them independently. This maximizes caching efficiency and prevents changes in one subproject from triggering unnecessary rebuilds in others. It also helps teams working on different subprojects avoid redundant builds—for example:

- **Front-end developers** don’t need to build back-end subprojects every time they modify the front-end.
- **Documentation writers** don’t need to build front-end or back-end code, even if the documentation is in the same project.

Instead, create **developer-specific tasks** while maintaining a single task graph for the entire project. Each group of users requires a subset of tasks—convert that subset into a Gradle workflow that excludes unnecessary tasks.

Gradle provides several features to create efficient workflows:

- **Assign tasks to appropriate** [groups](https://docs.gradle.org/current/dsl/org.gradle.api.Project.html#org.gradle.api.Project:group).
- **Create [lifecycle tasks](https://docs.gradle.org/current/userguide/more_about_tasks.html#sec:task_categories)** —tasks with no action that depend on other tasks (e.g., `assemble`).
- **Defer configuration** using `gradle.taskGraph.whenReady()` to execute verification only when necessary.

By default, Gradle reserves **512MB** of heap space for your build, which is sufficient for most projects.

However, very large builds may require more memory to store Gradle’s model and caches. If needed, you can increase the heap size by specifying the following property in the `gradle.properties` file in your [project root or your Gradle home directory](https://docs.gradle.org/current/userguide/directory_layout.html#directory_layout):

```properties
org.gradle.jvmargs=-Xmx2048M
```

For more details, see the [JVM Memory Configuration](https://docs.gradle.org/current/userguide/config_gradle.html#sec:configuring_jvm_memory) documentation.

As described in [the build lifecycle chapter](https://docs.gradle.org/current/userguide/build_lifecycle_intermediate.html#build_lifecycle), a Gradle build goes through three phases: **initialization, configuration, and execution**. The **configuration phase** always executes, regardless of which tasks run. Any expensive operations during this phase slow down every build, including simple commands like `gradle help` and `gradle tasks`.

The following sections introduce techniques to reduce time spent in the configuration phase.

|  | You can also [enable the configuration cache](#enable_configuration_cache) to minimize the impact of a slow configuration phase. However, even with caching, the configuration phase still runs occasionally. Optimizing it remains crucial. |
| --- | --- |

Time-consuming work should be avoided in the configuration phase. However, it can sometimes sneak in unexpectedly.

While encrypting data or making remote service calls is obvious when done in a build script, such logic is often hidden inside plugins or custom task classes. Expensive operations in a plugin’s `apply()` method or a task’s constructor are a red flag:

```kotlin
KotlinGroovy
```
```groovy
class ExpensivePlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        // BAD: Makes an expensive network call at configuration time
        def response = new URL("https://example.com/dependencies.json").text
        def dependencies = new groovy.json.JsonSlurper().parseText(response)

        dependencies.each { dep ->
            project.dependencies.add("implementation", dep)
        }
    }
}
```

Instead:

```kotlin
KotlinGroovy
```
```groovy
class OptimizedPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.tasks.register("fetchDependencies") {
            doLast {
                // GOOD: Runs only when the task is executed
                def response = new URL("https://example.com/dependencies.json").text
                def dependencies = new groovy.json.JsonSlurper().parseText(response)

                dependencies.each { dep ->
                    project.dependencies.add("implementation", dep)
                }
            }
        }
    }
}
```

Each applied plugin or script adds to configuration time, with some plugins having a larger impact than others. Rather than avoiding plugins altogether, ensure they are applied only where necessary. For example, using `allprojects {}` or `subprojects {}` can apply plugins to all subprojects, even if not all need them.

In the example below, the root build script applies `script-a.gradle` to three subprojects:

```groovy
subprojects {
    apply from: "$rootDir/script-a.gradle"  // Applied to all subprojects unnecessarily
}
```

![script a application](https://docs.gradle.org/current/userguide/img/performance/script-a-application.png)

Figure 7. Showing the application of script-a.gradle to the build

This script takes **1 second** to run per subproject, delaying the configuration phase by **3 seconds** in total. To optimize this:

- If only one subproject requires the script, remove it from the others, reducing the configuration delay by **2 seconds**.
	```kotlin
	KotlinGroovy
	```
	```groovy
	project(":subproject1") {
	    apply from: "$rootDir/script-a.gradle"  // Applied only where needed
	}
	project(":subproject2") {
	    apply from: "$rootDir/script-a.gradle"
	}
	```
- If multiple—but not all—subprojects use the script, refactor it into a custom plugin inside [`buildSrc`](https://docs.gradle.org/current/userguide/sharing_build_logic_between_subprojects.html#sec:using_buildsrc) and apply it only to the relevant subprojects. This reduces configuration time and avoids code duplication.
	```kotlin
	KotlinGroovy
	```
	```groovy
	project(":subproject1") {
	    apply plugin: 'com.example.my-custom-plugin'  // Apply only where needed
	}
	project(":subproject2") {
	    apply plugin: 'com.example.my-custom-plugin'
	}
	```

Many Gradle plugins and tasks are written in Groovy due to its concise syntax, functional APIs, and powerful extensions. However, Groovy’s **dynamic interpretation** makes method calls slower than in Java or Kotlin.

You can reduce this cost by using **static Groovy compilation**. Add the `@CompileStatic` annotation to Groovy classes where dynamic features are unnecessary. If a method requires dynamic behavior, use `@CompileDynamic` on that method.

Alternatively, consider writing plugins and tasks in **Java or Kotlin**, which are statically compiled by default.

|  | Gradle’s Groovy DSL relies on Groovy’s dynamic features. To use static compilation in plugins, adopt a more Java-like syntax. |
| --- | --- |

The example below defines a task that copies files without dynamic features:

```groovy
project.tasks.register('copyFiles', Copy) { Task t ->
    t.into(project.layout.buildDirectory.dir('output'))
    t.from(project.configurations.getByName('compile'))
}
```

This example uses `register()` and `getByName()`, available on all Gradle **domain object containers**, such as tasks, configurations, dependencies, and extensions. Some containers, like `TaskContainer`, have specialized methods such as [create](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.TaskContainer.html#org.gradle.api.tasks.TaskContainer:create\(java.lang.String,%20java.lang.Class\)), which accepts a task type.

Using static compilation improves IDE support by enabling:

- Faster detection of unrecognized types, properties, and methods
- More reliable auto-completion for method names

Dependency resolution simplifies integrating third-party libraries into your projects. Gradle contacts remote servers to discover and download dependencies. You can optimize how dependencies are referenced to minimize these remote calls.

Managing third-party libraries and their transitive dependencies adds significant maintenance and build time costs. Unused dependencies often remain after refactors.

If you only use a small portion of a library, consider: - Implementing the required functionality yourself. - Copying the necessary code (with attribution) if the library is open source.

Gradle searches repositories in the order they are declared. To speed up resolution, list the repository hosting most dependencies first, reducing unnecessary network requests.

```groovy
repositories {
    mavenCentral()  // Declared first, but most dependencies are in example.com/repo
    maven { url "https://example.com/repo" }
}
```

Limit the number of repositories to the minimum required.

If using a custom repository, create a **virtual repository** that aggregates multiple repositories, then add only that repository to your build.

```groovy
repositories {
    maven { url "https://example.com/virtual-repo" } // Uses an aggregated repository
}
```

Dynamic (`"2.+“) and snapshot versions (”-SNAPSHOT"`) cause Gradle to check remote repositories frequently. By default, Gradle caches dynamic versions for 24 hours, but this can be configured with the `cacheDynamicVersionsFor` and `cacheChangingModulesFor` properties:

```kotlin
KotlinGroovy
```
```groovy
configurations.all {
    resolutionStrategy {
        cacheDynamicVersionsFor 4, 'hours'
        cacheChangingModulesFor 10, 'minutes'
    }
}
```

If a build file or initialization script lowers these values, Gradle queries repositories more often. When you don’t need the absolute latest release of a dependency every time you build, consider removing the custom values for these settings.

Note that when cached dynamic versions expire, this will invalidate Configuration Cache entries including these confgurations, as Gradle will need to re-run the configuration phase and re-resolve the dependencies.

When this happens it is indicated with a message in the build output:

```text
Calculating task graph as configuration cache cannot be reused because cached version information for org.test:projectA:1.+ has expired.
```

To locate dynamic dependencies, use a Build Scan:

![dependency dynamic versions](https://docs.gradle.org/current/userguide/img/performance/dependency-dynamic-versions.png)

Figure 8. Find dependencies with dynamic versions

Where possible, replace dynamic versions with fixed versions like `"1.2"` or `"3.0.3.GA"` for better caching.

Dependency resolution is an I/O-intensive process. Gradle caches results, but triggering resolution in the **configuration phase** adds unnecessary overhead to every build.

This code forces dependency resolution during configuration, slowing down every build:

```kotlin
KotlinGroovy
```
```groovy
task printDeps {
    doFirst {
        configurations.compileClasspath.files.each { println it } // Deferring Dependency Resolution
    }
    doLast {
        configurations.compileClasspath.files.each { println it } // Resolving Dependencies During Configuration
    }
}
```

Evaluating a configuration file during the **configuration phase** forces Gradle to resolve dependencies too early, increasing build times. Normally, tasks should resolve dependencies only when they need them during execution.

Consider a debugging scenario where you want to print all files in a configuration. A common mistake is to print them directly in the build script:

```kotlin
KotlinGroovy
```
```groovy
tasks.register('copyFiles', Copy) {
    println ">> Compilation deps: ${configurations.compileClasspath.files.name}"
    into(layout.buildDirectory.dir('output'))
    from(configurations.compileClasspath)
}
```

The `files` property triggers dependency resolution immediately, even if `printDeps` is never executed. Since the configuration phase runs on every build, this slows down **all** builds.

By using `doFirst()`, Gradle defers dependency resolution until the task actually runs, preventing unnecessary work in the configuration phase:

```kotlin
KotlinGroovy
```
```groovy
tasks.register('copyFiles', Copy) {
    into(layout.buildDirectory.dir('output'))
    // Store the configuration into a variable because referencing the project from the task action
    // is not compatible with the configuration cache.
    FileCollection compileClasspath = configurations.compileClasspath
    from(compileClasspath)
    doFirst {
        println ">> Compilation deps: ${compileClasspath.files.name}"
    }
}
```

The `from()` method in Gradle’s `Copy` task does **not** trigger immediate [dependency resolution](https://docs.gradle.org/current/userguide/dependency_configurations.html#sub:what-are-dependency-configurations) because it references the dependency **configuration**, not the resolved files. This ensures that dependencies are resolved only when the `Copy` task executes.

The "Dependency resolution" tab on the performance page of a Build Scan shows dependency resolution time during the configuration and execution phases:

![bad dependency resolution](https://docs.gradle.org/current/userguide/img/performance/bad-dependency-resolution.png)

Figure 9. Dependency resolution at configuration time

A Build Scan provides another means of identifying this issue. Your build should spend 0 seconds resolving dependencies during *"project configuration"*. This example shows the build resolves dependencies too early in the lifecycle. You can also find a "Settings and suggestions" tab on the "Performance" page. This shows dependencies resolved during the configuration phase.

Gradle allows users to model dependency resolution in a flexible way. Simple customizations, such as forcing specific versions or substituting dependencies, have minimal impact on resolution times. However, **complex custom logic** —such as downloading and parsing POM files manually—can significantly slow down dependency resolution.

Use **Build Scan** or **profile reports** to ensure custom dependency resolution logic is not causing performance issues. This logic may exist in your build scripts or as part of a third-party plugin.

This example forces a custom dependency version but also introduces expensive logic that slows down resolution:

```kotlin
KotlinGroovy
```
```groovy
configurations.all {
    resolutionStrategy.eachDependency { details ->
        if (details.requested.group == "com.example" && details.requested.name == "library") {
            def versionInfo = new URL("https://example.com/version-check").text  // Remote call during resolution
            details.useVersion(versionInfo.trim())  // Dynamically setting a version based on an HTTP response
        }
    }
}
```

Instead of fetching dependency versions dynamically, define them in a version catalog:

```groovy
dependencies {
    implementation "com.example:library:${versions.libraryVersion}"
}
```

Slow dependency downloads can significantly impact build performance. Common causes include:

- Slow internet connections
- Overloaded or distant repository servers
- Unexpected downloads caused by **dynamic versions** (`2.+`) or **snapshot versions** (`-SNAPSHOT`)

The **Performance** tab in a Build Scan includes a **Network Activity** section with: - **Total time spent downloading dependencies** - **Download transfer rates** - **A list of dependencies sorted by download time**

In the example below, two slow downloads took **20 seconds** and **40 seconds**, impacting the overall build time:

![slow dependency downloads](https://docs.gradle.org/current/userguide/img/performance/slow-dependency-downloads.png)

Figure 10. Identify slow dependency downloads

Examine the list of downloaded dependencies for unexpected ones. For example, a dynamic version (`1.+`) may be triggering frequent remote lookups.

To eliminate unnecessary downloads:

- **Use a closer or faster repository** If downloads are slow from **Maven Central**, consider a geographically closer mirror or an internal repository proxy.
- **Switch from dynamic versions to fixed versions**

```groovy
dependencies {
    implementation "com.example:library:1.+" // Bad
    implementation "com.example:library:1.2.3" // Good
}
```

The following sections apply to projects that use the `java` plugin or other JVM languages.

Tests often account for a significant portion of build time. These may include both unit and integration tests, with integration tests typically taking longer to run.

A [Build Scan](https://docs.gradle.org/current/userguide/build_scans.html#build_scans) can help you identify the slowest tests and prioritize performance improvements accordingly.

![tests longest](https://docs.gradle.org/current/userguide/img/performance/tests-longest.png)

Figure 11. Tests screen, with tests by project, sorted by duration

The image above shows the interactive test report from a Build Scan, sorted by test duration.

Gradle offers several strategies to speed up test execution:

- A. Run tests in parallel
- B. Fork tests into multiple processes
- C. Disable test reports when not needed

Let’s take a closer look at each option.

Gradle can run multiple test classes or methods in parallel. To enable parallel execution, set the `maxParallelForks` property on your `Test` tasks.

A good default is the number of available CPU cores or slightly fewer:

```kotlin
KotlinGroovy
```
```groovy
tasks.withType(Test).configureEach {
    maxParallelForks = Runtime.runtime.availableProcessors().intdiv(2) ?: 1
}
```

Parallel test execution assumes that tests are isolated. Avoid shared resources such as file systems, databases, or external services. Tests that share state or resources may fail intermittently due to race conditions or resource conflicts.

By default, Gradle runs all tests in a single forked JVM process. This is efficient for small test suites, but large or memory-intensive test suites can suffer from long execution times and GC pauses.

You can reduce memory pressure and isolate problematic tests by forking a new JVM after a specified number of tests using the `forkEvery` setting:

```kotlin
KotlinGroovy
```
```groovy
tasks.withType(Test).configureEach {
    forkEvery = 100
}
```

|  | Forking a JVM is an expensive operation. Setting `forkEvery` too low can increase test time due to excessive process startup overhead. |
| --- | --- |

Gradle generates HTML and JUnit XML test reports by default, even if you don’t intend to view them. Report generation adds overhead, particularly in large test suites.

You can disable report generation entirely if:

- You only need to know whether the tests passed.
- You use a Build Scan, which provide richer test insights.

To disable reports, set `reports.html.required` and `reports.junitXml.required` to `false`:

```kotlin
KotlinGroovy
```
```groovy
tasks.withType(Test).configureEach {
    reports.html.required = false
    reports.junitXml.required = false
}
```

If you occasionally need reports without modifying the build file, you can make report generation conditional on a project property.

This example disables reports unless the `createReports` property is present:

```kotlin
KotlinGroovy
```
```groovy
tasks.withType(Test).configureEach {
    if (!project.hasProperty("createReports")) {
        reports.html.required = false
        reports.junitXml.required = false
    }
}
```

To generate reports, pass the property via the command line:

```bash
$ gradle <task> -PcreateReports
```

Or define the property in the `gradle.properties` file located in the [project root](https://docs.gradle.org/current/userguide/directory_layout.html#directory_layout) or your [Gradle User Home](https://docs.gradle.org/current/userguide/directory_layout.html#dir:gradle_user_home):

```properties
createReports=true
```

The Java compiler is fast, but in large projects with hundreds or thousands of classes, compilation time can still become significant.

Gradle offers several ways to optimize Java compilation:

- A. Run the compiler in a separate process
- B. Use `implementation` visibility for internal dependencies

By default, Gradle runs compilation in the same process as the build logic. You can offload Java compilation to a separate process using the `fork` option:

```kotlin
KotlinGroovy
```
```groovy
<task>.options.fork = true
```

To apply this setting to all `JavaCompile` tasks, use `configureEach`:

```kotlin
KotlinGroovy
```
```groovy
tasks.withType(JavaCompile).configureEach {
    options.fork = true
}
```

Gradle reuses the forked process for the duration of the build, so the startup cost is low. Running compilation in its own JVM helps reduce garbage collection in the main Gradle process, which can speed up the rest of your build — especially when used alongside [parallel execution](#sec:enable_parallel_execution).

Forking compilation has little effect on small builds but can help significantly when a single task compiles more than a thousand source files.

In Gradle 3.4 and later, you can use `api` for dependencies that should be exposed to downstream projects and `implementation` for internal dependencies. This distinction reduces unnecessary recompilation in large multi-project builds.

|  | Only projects that apply the [`java-library`](https://docs.gradle.org/current/userguide/java_library_plugin.html#java_library_plugin) plugin can use the `api` and `implementation` configurations. Projects using only the `java` plugin cannot declare `api` dependencies. |
| --- | --- |

When an `implementation` dependency changes, Gradle does **not** recompile downstream consumers — only when `api` dependencies change. This helps reduce cascading recompilations:

```kotlin
KotlinGroovy
```
```groovy
dependencies {
   api project('my-utils')
   implementation 'com.google.guava:guava:21.0'
}
```

Switching to `implementation` for internal-only dependencies is one of the most impactful changes you can make to improve build performance in large, modular codebases.

All the performance strategies described in this guide also apply to Android builds, since Android projects use Gradle under the hood.

However, Android introduces its own unique challenges and opportunities for optimization — especially around resource processing, APK creation, and build variants.

For additional tips specific to Android, check out the official resources from the Android team:

We recommend using the latest Gradle version to benefit from the latest performance improvements, bug fixes, and features. However, we understand that some projects — especially long-lived or legacy codebases — may not be able to upgrade easily.

If you’re using an older version of Gradle, consider the following optimizations to improve build performance.

The Gradle Daemon significantly improves build performance by avoiding JVM startup costs between builds. The Daemon has been enabled by default since Gradle 3.0.

If you’re using an older version, consider [upgrading Gradle](#update_gradle). If upgrading isn’t an option, you can [enable the Daemon manually](#enable_daemon).

Gradle can analyze class dependencies and recompile only the parts of your code affected by a change.

Incremental compilation is enabled by default in Gradle 4.10 and later. To enable it manually in older versions, add the following configuration to your `build.gradle` file:

```kotlin
KotlinGroovy
```
```groovy
tasks.withType(JavaCompile).configureEach {
    options.incremental = true
}
```

Many code changes, such as edits to method bodies, are *ABI-compatible* — they do not affect a class’s public API. Gradle 3.4 and newer can detect these changes and avoid recompiling downstream projects, significantly reducing build times in large multi-project builds.

To benefit from compile avoidance, upgrade to Gradle 3.4 or later.

If your project uses annotation processors, you must explicitly declare them to take full advantage of compile avoidance. See the [compile avoidance documentation](https://docs.gradle.org/current/userguide/java_plugin.html#sec:java_compile_avoidance) for more details.