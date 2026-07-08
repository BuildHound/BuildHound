---
title: "AGP 9.0 Migration Guide — Fixing DSL, Deprecated Flags & Build Changes"
source: "https://mrkivan820.medium.com/agp-9-0-migration-guide-fixing-dsl-deprecated-flags-build-changes-01589a57f75d"
author:
  - "[[Riazul Karim Ivan]]"
published: 2026-02-26
created: 2026-07-07
description: "As of Android Gradle Plugin 9.0 (AGP 9.0), several legacy DSL behaviors and gradle.properties flags are deprecated or removed."
tags:
  - "clippings"
---
As of **Android Gradle Plugin 9.0 (AGP 9.0)**, several legacy DSL behaviors and `gradle.properties` flags are deprecated or removed.

If you recently upgraded and started seeing:

- DSL access errors
- Deprecated flag warnings
- Kotlin plugin conflicts
- `buildFeatures` issues
- R8 / R class related warnings

This guide explains **what changed**, **why it broke**, and **how to fix it properly**.

## 1️⃣ The Big DSL Change — Use extensions.configure

AGP 9 enforces stricter DSL access rules.

## Get Riazul Karim Ivan’s stories in your inbox

Join Medium for free to get updates from this writer.

Old style configurations like this may stop working or produce warnings:

```c
android {
    buildFeatures {
        buildConfig = true
        resValues = true
    }
}
```

## ✅ The Fix: Use Explicit Extension Configuration

For **Library modules**:

```c
import com.android.build.api.dsl.LibraryExtension

extensions.configure<LibraryExtension> {
    buildFeatures {
        resValues = true
        buildConfig = true
    }
}
```

For **Application modules**:

```c
import com.android.build.api.dsl.ApplicationExtension

extensions.configure<ApplicationExtension> {
    buildFeatures {
        resValues = true
        buildConfig = true
    }
}
```

## Why?

AGP 9 moves toward a stricter and more explicit DSL model.  
Using `extensions.configure<T>()` ensures correct type-safe configuration and avoids ambiguity during Gradle sync.

## 2️⃣ Kotlin Configuration Must Be Outside android {}

In AGP 9, Kotlin configuration should not be nested incorrectly.

## ❌ Wrong

```c
android {
    kotlin {
        jvmToolchain(17)
    }
}
```

## ✅ Correct

Move it outside:

```c
kotlin {
    jvmToolchain(17)
}
```

This ensures the Kotlin plugin configures the toolchain correctly before Android compilation starts.

## 3️⃣ Remove the Kotlin Android Plugin (Important)

If you’re using modern AGP 9 + Compose setup, you may see plugin conflicts.

## ❌ Old Plugin Setup

```c
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)   // ❌ REMOVE THIS
    alias(libs.plugins.kotlin.compose)
}
```

## ✅ Fixed Setup

```c
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}
```

## Why Remove It?

AGP 9 has deeper integration with Kotlin.  
Using both `kotlin-android` and new Kotlin plugin setups can cause duplicate or conflicting configuration.

## 4️⃣ Deprecated gradle.properties Flags (Major Cleanup)

After upgrading, you may see warnings like:

```c
The option setting 'android.sdk.defaultTargetSdkToCompileSdkIfUnset=false' is deprecated.
The option setting 'android.enableAppCompileTimeRClass=false' is deprecated.
The option setting 'android.builtInKotlin=false' is deprecated.
The option setting 'android.newDsl=false' is deprecated.
The option setting 'android.r8.optimizedResourceShrinking=false' is deprecated.
The option setting 'android.defaults.buildfeatures.resvalues=true' is deprecated.
```

These flags controlled **legacy behavior** in AGP 7/8.  
In AGP 9, they are either:

- Removed
- Always enabled
- No longer needed

## 5️⃣ Before Migration (With Warnings)

```c
org.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
ksp.verbose=true
ksp.incremental=false
ksp.incremental.compilation=false
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.configureondemand=true
kotlin.incremental=true
gpr.user=<your-github-username>
gpr.key=<your-github-personal-access-token-with-read-packages-scope>
android.defaults.buildfeatures.resvalues=true
android.sdk.defaultTargetSdkToCompileSdkIfUnset=false
android.enableAppCompileTimeRClass=false
android.uniquePackageNames=false
android.dependency.useConstraints=true
android.r8.strictFullModeForKeepRules=false
android.r8.optimizedResourceShrinking=false
android.builtInKotlin=false
android.newDsl=false
```

## 6️⃣ Cleaned & Fixed Version (AGP 9 Compatible)

```c
org.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
# KSP settings
ksp.verbose=true
ksp.incremental=false
# Performance
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.configureondemand=true
kotlin.incremental=true
# Credentials
gpr.user=YOUR_USER
gpr.key=YOUR_KEY
# Only keep if required during migration
android.dependency.useConstraints=true
android.r8.strictFullModeForKeepRules=false
android.disallowKotlinSourceSets=false
android.generateSyncIssueWhenLibraryConstraintsAreEnabled=false
#Update these still needed!
android.builtInKotlin=false
android.newDsl=false
```

## 7️⃣ What Actually Changed in AGP 9?

AGP 9 focuses on:

- Stronger DSL typing
- Removal of legacy flags
- Better Kotlin integration
- Improved R8 defaults
- Removal of old R-class behaviors
- More consistent build feature configuration

Most deprecated flags now:

- Have default optimized behavior
- Cannot be disabled
- Or are fully removed

## 8️⃣ Quick Migration Checklist

✔ Update to AGP 9.0  
✔ Use `extensions.configure<ApplicationExtension>()` or `LibraryExtension`  
✔ Move `kotlin {}` outside `android {}`  
✔ Remove `kotlin-android` plugin if redundant  
✔ Delete deprecated gradle.properties flags  
✔ Clean & rebuild project

## 9️⃣ Final Thoughts

AGP 9 is stricter — but cleaner.

Once you:

- Remove legacy flags
- Use explicit extension configuration
- Clean up Kotlin plugin usage

Your build becomes:

- More predictable
- Faster
- Future-proof

If you’re working on a **multi-module super app** or enterprise Android architecture, cleaning up these flags early will save hours of debugging later.