---
title: "Gradle & Build Optimization: 10 Techniques to Cut Your Android Build Time in Half"
source: "https://medium.com/@ramadan123sayed/gradle-build-optimization-10-techniques-to-cut-your-android-build-time-in-half-63db03dee88d"
author:
  - "[[Ramadan Sayed]]"
published: 2026-04-04
created: 2026-07-07
description: "This member-only story is on us. Upgrade to access all of Medium."
tags:
  - "clippings"
---
*Your clean build takes 5 minutes. Your incremental build takes 2 minutes even when you change one line. Your CI pipeline takes 15 minutes per PR. Your 25-module project configures all modules even when you’re building one feature. Here are 10 Gradle optimization techniques with the exact configuration to cut build time by 50% or more.*

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*yrJzfScb1LMlwlPAZeQ_ow.png)

## Introduction

Build time is developer time. A 3-minute incremental build means you lose 3 minutes every time you hit Run — that’s 30+ minutes a day, 2.5 hours a week, 130 hours a year waiting for Gradle. For a team of 5 developers, that’s 650 hours of wasted productivity per year.

The good news: most Android projects have massive build time improvements sitting in their `gradle.properties` file — unflipped switches that cut build time in half. Configuration cache, build cache, parallel execution, and convention plugins are all available today but barely used in practice.

This guide covers 10 techniques I’ve applied to real production projects, each with exact configuration and measured before/after times.

## Technique #1: gradle.properties — The Quick Wins

Most projects leave performance settings at defaults. These 6 lines make an immediate difference:

```c
# gradle.properties

# 1. Parallel module compilation (huge for multi-module projects)
org.gradle.parallel=true
# 2. Build cache - reuse task outputs from previous builds
org.gradle.caching=true
# 3. Configuration cache - skip configuration phase on subsequent builds
org.gradle.configuration-cache=true
# 4. Daemon - keep Gradle JVM warm between builds (default: true since Gradle 3.0)
org.gradle.daemon=true
# 5. JVM heap - increase for large projects (default is often too low)
org.gradle.jvmargs=-Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError
# 6. Non-transitive R classes - each module only sees its own resources
android.nonTransitiveRClass=true
```

**Impact on a 25-module project:**

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*godVquIQBmZ45WWd_fso5Q.png)

## Technique #2: Configuration Cache — Skip the Configuration Phase

The configuration phase runs **every build** — parsing all `build.gradle.kts` files, resolving plugins, evaluating dependency trees. On a 25-module project, this takes 5-10 seconds.

Configuration cache stores the result of this phase. On subsequent builds with the same configuration, Gradle skips it entirely.

```c
# gradle.properties
org.gradle.configuration-cache=true
```

## Fixing Configuration Cache Compatibility Issues

Some plugins aren’t compatible. Run your build and check for warnings:

```c
./gradlew assembleDebug --configuration-cache
# If it fails, check the report:
# build/reports/configuration-cache/...
```

Common fixes:

```c
// ❌ Accessing Project at execution time (not compatible)
tasks.register("myTask") {
    val projectName = project.name  // BAD — reads Project during execution
    doLast { println(projectName) }
}

// ✅ Capture values during configuration
tasks.register("myTask") {
    val name = project.name  // Captured during configuration - OK
    doLast { println(name) }
}
```

## Technique #3: Build Cache — Reuse Task Outputs

Build cache stores compiled outputs. When the same inputs produce the same outputs, Gradle reuses them instead of recompiling.

```c
# gradle.properties
org.gradle.caching=true
```

## Local + Remote Cache (for CI)

```c
// settings.gradle.kts
buildCache {
    local {
        isEnabled = true
        directory = File(rootDir, ".gradle/build-cache")
    }
    remote<HttpBuildCache> {
        url = uri("https://your-cache-server.com/cache/")
        isPush = System.getenv("CI") != null  // Only CI pushes to remote cache
        isEnabled = true
        credentials {
            username = System.getenv("CACHE_USER") ?: ""
            password = System.getenv("CACHE_PASS") ?: ""
        }
    }
}
```

**CI builds push cached outputs → developer builds pull from cache.** When a CI build compiles `:core:network`, every developer's next build reuses that output instead of recompiling.

## Android Cache Fix Plugin

Android Gradle Plugin has known caching bugs. This plugin fixes them:

```c
// build-logic/convention/build.gradle.kts
dependencies {
    implementation("org.gradle.android.cache-fix:org.gradle.android.cache-fix.gradle.plugin:3.0.2")
}

// Apply in your convention plugin
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.gradle.android.cache-fix")
        }
    }
}
```

## Technique #4: Convention Plugins — DRY Build Logic

Without convention plugins, every module repeats the same 30+ lines of Gradle config. With 25 modules, that’s 750 lines of duplicated build logic.

## Project Structure

```c
build-logic/
├── convention/
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── AndroidApplicationConventionPlugin.kt
│       ├── AndroidLibraryConventionPlugin.kt
│       ├── AndroidFeaturePresentationPlugin.kt
│       ├── AndroidFeatureDataPlugin.kt
│       ├── KotlinFeatureDomainPlugin.kt
│       └── ComposeConventionPlugin.kt

// build-logic/convention/build.gradle.kts
plugins { \`kotlin-dsl\` }

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}
gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "myapp.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "myapp.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("featurePresentation") {
            id = "myapp.android.feature.presentation"
            implementationClass = "AndroidFeaturePresentationPlugin"
        }
        register("featureData") {
            id = "myapp.android.feature.data"
            implementationClass = "AndroidFeatureDataPlugin"
        }
        register("featureDomain") {
            id = "myapp.kotlin.feature.domain"
            implementationClass = "KotlinFeatureDomainPlugin"
        }
    }
}
```
```c
// AndroidLibraryConventionPlugin.kt
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")
            pluginManager.apply("org.gradle.android.cache-fix")

extensions.configure<LibraryExtension> {
                compileSdk = 35
                defaultConfig { minSdk = 26 }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
            extensions.configure<KotlinAndroidProjectExtension> {
                jvmToolchain(17)
            }
        }
    }
}
```

Now each module’s build file is **3–5 lines**:

```c
// feature/product/data/build.gradle.kts
plugins { id("myapp.android.feature.data") }
dependencies {
    implementation(project(":feature:product:domain"))
    implementation(project(":core:network"))
}
```

## Technique #5: Version Catalog — Centralized Dependencies

All dependency versions in one file — `gradle/libs.versions.toml`:

```c
[versions]
kotlin = "2.1.10"
agp = "9.0.1"
compose-bom = "2026.02.00"
hilt = "2.54"
room = "2.7.1"
ksp = "2.1.10-1.0.31"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

**Benefit:** Bumping a dependency = changing one line in one file instead of 25 `build.gradle.kts` files. Gradle resolves all versions once during configuration.

## Technique #6: KSP Over KAPT

KAPT (Kotlin Annotation Processing Tool) generates Java stubs from Kotlin code, then runs annotation processors on those stubs. This is slow — often 30–50% of total build time.

KSP (Kotlin Symbol Processing) processes Kotlin directly. No stub generation.

```c
// ❌ KAPT — slow
plugins {
    id("kotlin-kapt")
}
dependencies {
    kapt(libs.hilt.compiler)
    kapt(libs.room.compiler)
}

// ✅ KSP - 2× faster annotation processing
plugins {
    id("com.google.devtools.ksp")
}
dependencies {
    ksp(libs.hilt.compiler)
    ksp(libs.room.compiler)
}
```

**Measured impact:** On a 25-module project, switching from KAPT to KSP reduced incremental build time by **35%**. Room, Hilt, and Moshi all support KSP.

## Technique #7: Modularization for Parallel Builds

Gradle compiles independent modules in parallel. More modules = more parallelism = faster builds.

```c
Monolith (1 module): All 480 files compile sequentially
                     [============================] 4m 12s

25 modules: Independent modules compile in parallel
            [:core:model]     [===]
            [:core:common]    [===]
            [:core:network]   [=====]
            [:core:database]  [=====]
            [:feature:auth:domain]  [==]
            [:feature:auth:data]       [====]
            [:feature:auth:presentation]  [====]
            ...
            Total: [===============] 2m 45s
```

**Critical rule:** Feature modules must not depend on each other — only on core modules. Cross-feature dependencies create serialization bottlenecks that kill parallelism.

Pure Kotlin JVM modules (`:feature:*:domain`) compile faster than Android library modules because they skip AAPT, resource merging, and manifest merging.

## Technique #8: Avoid Dynamic Dependency Versions

```c
// ❌ Dynamic version — Gradle checks Maven Central EVERY build
implementation("com.squareup.okhttp3:okhttp:4.+")

// ✅ Static version - resolved once, cached
implementation(libs.okhttp)  // libs.versions.toml: okhttp = "4.12.0"
```

Dynamic versions (`+`) force Gradle to check remote repositories on every build, adding 2-5 seconds per resolution. With 30+ dependencies, that's 60-150 seconds of unnecessary network calls.

## Technique #9: Optimize Repository Order

```c
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()            // 1st — most Android deps are here
        mavenCentral()      // 2nd — most Kotlin/Java deps
        gradlePluginPortal() // LAST — only for Gradle plugins, searched less
    }
}
```

Gradle searches repositories **in order**. If `google()` is first and contains 80% of your dependencies, most lookups resolve on the first check. Putting `gradlePluginPortal()` first adds unnecessary network requests for every dependency.

## Technique #10: Build Scans for Diagnosis

Before optimizing, **measure**. Build scans show exactly where time is spent.

## Free Build Scans

```c
// settings.gradle.kts
plugins {
    id("com.gradle.develocity") version "4.2.2"
}

develocity {
    buildScan {
        termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
        termsOfUseAgree.set("yes")
        publishing.onlyIf { true }  // Publish scan for every build
    }
}
```

Run `./gradlew assembleDebug` and get a URL like `https://gradle.com/s/abcdef123`. The scan shows:

- **Timeline:** Which tasks ran, how long each took, which ran in parallel
- **Performance:** Configuration time, dependency resolution time, task execution time
- **Build Cache:** Cache hit rate, which tasks were cached vs recompiled
- **Dependencies:** Full dependency tree with resolution times

## Profiling Locally

```c
# Generate a build profile report
./gradlew assembleDebug --profile
# Report: build/reports/profile/profile-*.html

---

# Scan for configuration cache issues
./gradlew assembleDebug --configuration-cache --info

---
```

## Complete gradle.properties

```c
# === Performance ===
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.daemon=true

# === JVM ===
org.gradle.jvmargs=-Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError
# === Android Specific ===
android.nonTransitiveRClass=true
android.useAndroidX=true
# === Kotlin ===
kotlin.code.style=official
kotlin.daemon.jvmargs=-Xmx2g
```

## Build Performance Results

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*PoVfja2Jo3t_PBkeMQeuJw.png)

## Conclusion

Build optimization is a multiplier — every second saved compounds across every build, every developer, every day. Start with the zero-effort wins: `gradle.properties` settings, build cache, and configuration cache. Then invest in KSP over KAPT, convention plugins for DRY build logic, and version catalogs for centralized dependency management. Measure everything with build scans. The target: clean builds under 3 minutes, incremental builds under 30 seconds.

**Tags:** `#Android` `#Gradle` `#BuildOptimization` `#ConventionPlugins` `#BuildCache` `#ConfigurationCache` `#KSP` `#DeveloperProductivity`