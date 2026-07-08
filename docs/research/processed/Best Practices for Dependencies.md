---
title: "Best Practices for Dependencies"
source: "https://docs.gradle.org/current/userguide/best_practices_dependencies.html"
author:
published:
created: 2026-07-07
description:
tags:
  - "clippings"
---
Version Catalogs provide a centralized, declarative way to manage dependency versions throughout a build.

When you define your dependency versions in a single, shared version catalog, you reduce duplication and make upgrades easier. Instead of changing dozens of `build.gradle(.kts)` files, you update the version in one place. This simplifies maintenance, improves consistency, and reduces the risk of accidental version drift between modules. Consistent version declarations across projects also make it easier to reason about behavior during testing—especially in modular builds where transitive upgrades can silently change runtime behavior in later stages of the build.

However, version catalogs only influence declared versions, not resolved versions. Use them in combination with [dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html#sec:dependency-locking) and [version alignment](https://docs.gradle.org/current/userguide/resolution_rules.html#using-resolution-rules) to enforce consistency across builds. To influence resolved versions, check out [platforms](https://docs.gradle.org/current/userguide/platforms.html#platforms).

Avoid declaring versions in `project.ext`, constants, or local variables:

```kotlin
KotlinGroovy
```
```groovy
plugins {
    id('java-library')
    id('com.github.ben-manes.versions').version('0.45.0')
}
def groovyVersion = '3.0.5'

dependencies {
    api("org.codehaus.groovy:groovy:$groovyVersion")
    api("org.codehaus.groovy:groovy-json:$groovyVersion")
    api("org.codehaus.groovy:groovy-nio:$groovyVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")

    implementation("org.apache.commons:commons-lang3") {
        version {
            strictly("[3.8, 4.0[")
            prefer("3.9")
        }
    }
}
```

Avoid misusing version catalogs for unrelated concerns:

- Don’t use them to store shared strings or non-library constants
- Don’t overload them with arbitrary logic or plugin-specific configuration

Use a centralized `libs.versions.toml` file in your `gradle/` directory:

```kotlin
KotlinGroovy
```
```groovy
plugins {
    id('java-library')
    alias(libs.plugins.versions)
}
dependencies {
    api(libs.bundles.groovy)
    testImplementation(libs.junit.jupiter)
    implementation(libs.commons.lang3)
}
```

`#version-catalog`

Consistent and descriptive names in your version catalog enhance readability and maintainability across your build scripts.

Version catalogs provide a centralized way to manage dependencies by mapping full dependency coordinates to concise, reusable aliases like `airlift-aircompressor`. Adopting clear naming conventions for those aliases ensures that developers can easily identify and use dependencies throughout the project.

Aliases are typically made up of 1 to 3 segments. For example `org.apache.commons:commons-lang3` could be represented as `commonsLang3`, `apache-commonsLang3`, or `commons-lang3`.

The following guidelines help in naming catalog entries effectively:

1. **Use dashes to separate segments**: Prefer hyphen/dashes (`-`) over underscores (`_`) to separate different parts of the entry name.
	Example: For `org.apache.logging.log4j:log4j-api`, use `log4j-api`
2. **Derive the first segment from the project group**: Use a unique identifier from the project’s group ID as the first segment. Do not include the top level domain in the segment (`com`, `org`, `net`, `dev`).
	Example: For `com.fasterxml.jackson.core:jackson-databind`, use `jackson-databind` or `jackson-core-databind`
3. **Derive the second segment from the artifact ID**: Use a unique identifier from the artifact ID as the second segment.
	Example: For `com.linecorp.armeria:armeria-grpc`, use `armeria-grpc`
4. **Avoid generic terms in the segments**: Exclude terms that are obvious or implied in the context of your project (`core`, `java`, `gradle`, `module`, `sdk`), especially if the term appears by itself.
	Example: For `com.google.googlejavaformat:google-java-format`, use `google-java-format`, not `google-java` or `java`
5. **Omit redundant segments**: If the group and artifact IDs are the same, avoid repeating them.
	Example: For `io.ktor:ktor-client-core`, use `ktor-client-core`, not `ktor-ktor-client-core`
6. **Convert internal dashes to camelCase**: If the artifact ID contains dashes, convert them to camelCase for better readability in code.
	Example: `spring-boot-starter-web` becomes `springBootStarterWeb`
7. **Suffix plugin libraries with `-plugin`**: When referencing a plugin as a library (not in the `[plugins]` section), append `-plugin` to the name.
	Example: For `org.owasp:dependency-check-gradle`, use `dependency-check-plugin`

```kotlin
KotlinGroovy
```
```groovy
plugins {
    id 'java-library'
    alias(libs.plugins.versions)
}

repositories {
    mavenCentral()
}

dependencies {
    // SLF4J
    implementation libs.slf4j.api

    // Jackson
    implementation libs.jackson.databind
    implementation libs.jackson.dataformatCsv

    // Groovy bundle
    api libs.bundles.groovy

    // Commons Lang
    implementation libs.commons.lang3
}
```

`#version-catalog`

Declare your repositories for your plugins and dependencies in `settings.gradle.kts`.

Using `settings.gradle.kts` file to declare repositories has several benefits:

- **Avoids repetition**: Centralizing repository declarations eliminates the need to repeat them in each project’s `build.gradle.kts`.
- **Improves debuggability**: Ensures all projects resolve dependencies during resolution from the same repositories, in a consistent order.
- **Matches the build model**: Repositories are not part of the project definition; they are part of global build logic, so settings is a more appropriate place for them.

|  | While [`dependencyResolutionManagement.repositories` is an incubating API](https://github.com/gradle/gradle/issues/32443), it is the preferred way of declaring repositories. |
| --- | --- |

You could set up repositories in individual `build.gradle.kts` files with:

```kotlin
KotlinGroovy
```
```groovy
buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("java")
}

repositories {
    mavenCentral()
}
```

Instead, you should set them up in `settings.gradle.kts` like this:

```kotlin
KotlinGroovy
```
```groovy
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}
```

The Kotlin Gradle Plugin automatically adds a dependency on the Kotlin standard library (`stdlib`) to each source set, so there is no need to declare it explicitly.

The version of the standard library added is the same as the version of the Kotlin Gradle Plugin applied to the project. If your build does not require a specific or different version of the standard library, you should avoid adding it manually.

|  | Setting the `kotlin.stdlib.default.dependency` property to `false` prevents the Kotlin plugin from automatically adding the Kotlin standard library dependency to your project. This can be useful in specific scenarios, such as when you want to manage the Kotlin standard library dependency version manually. |
| --- | --- |

```kotlin
KotlinGroovy
```
```groovy
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
}

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib:2.3.21") (1)
}
```

| **1** | **`stdlib` is explicitly depended upon**: This project contains an implicit dependency on the Kotlin standard library, which is required to compile its source code. |
| --- | --- |

```kotlin
KotlinGroovy
```
```groovy
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"  (1)
}
```

| **1** | **`stdlib` dependency is not included explicitly**: The standard library remains available for use, and source code requiring it can be compiled without any issues. |
| --- | --- |

`#dependencies`

Avoid declaring the same dependency multiple times, especially when it is already available transitively or through another configuration.

Duplicating dependencies in Gradle build scripts can lead to:

- **Increased maintenance**: Declaring a dependency in multiple places makes it harder to manage.
- **Unexpected behavior**: Declaring the same dependency in multiple configurations (e.g., `compileOnly` and `implementation`) can result in hard-to-diagnose classpath issues.

```kotlin
KotlinGroovy
```
```groovy
plugins {
    id 'java-library'
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.0") (1)
}
```

| **1** | Redundant dependency in `implementation` scope. |
| --- | --- |

```kotlin
KotlinGroovy
```
```groovy
plugins {
    id 'java-library'
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.0") (1)
}
```

| **1** | Declare dependency once |
| --- | --- |

`#dependencies`

When declaring dependencies without a [version catalog](https://docs.gradle.org/current/userguide/version_catalogs.html#version-catalog), prefer using the single GAV string notation `implementation("org.example:library:1.0")`. Avoid using the named argument notation. The named argument notation has been deprecated and will no longer be supported starting in Gradle 10.

All of these declarations will be treated equivalently when Gradle resolves dependencies. However, the single-string form is more concise, easier to read, and is widely adopted in the broader JVM ecosystem.

This format is also recommended by [Maven Central](https://central.sonatype.com/artifact/com.google.guava/guava) in its documentation and usage examples, making it the most familiar and consistent style for developers across tools.

```kotlin
KotlinGroovy
```
```groovy
dependencies {
    implementation(group: 'com.fasterxml.jackson.core', name: 'jackson-databind', version: '2.17.0') (1)
    api(group: 'com.google.guava', name: 'guava', version: '32.1.2-jre') {
        exclude(group: 'com.google.code.findbugs', module: 'jsr305')    (2)
    }
}
```

| **1** | Avoid the named argument notation when declaring dependencies |
| --- | --- |
| **2** | Other modifiers methods and constraints like `exclude` are not included in this recommendation and can use named argument notation as needed |

```kotlin
KotlinGroovy
```
```groovy
dependencies {
    implementation('com.fasterxml.jackson.core:jackson-databind:2.17.0') (1)
    api('com.google.guava:guava:32.1.2-jre') {
        exclude(group: 'com.google.code.findbugs', module: 'jsr305')    (2)
    }
}
```

| **1** | Use the string notation instead when declaring dependencies |
| --- | --- |
| **2** | Other modifiers methods and constraints like `exclude` are not included in this recommendation and can use named argument notation as needed |

`#dependencies`

When using multiple repositories in a build, use [repository content filtering](https://docs.gradle.org/current/userguide/filtering_repository_content.html#repository-content-filtering) to ensure that dependencies are resolved from an appropriate repository.

If your build declares more than one repository, you should declare content filters on these repositories to ensure you search for and obtain dependencies from the correct place.

Content filtering is necessary if you have a reason to restrict searching for a dependency to a particular repository, and can be a good idea even if acceptable dependency artifacts exist in multiple locations.

When possible, you should use the [exclusiveContent](https://docs.gradle.org/current/userguide/filtering_repository_content.html#sec:declaring-content-repositories) feature to restrict dependencies to a particular known repository.

Content filtering has three main benefits:

1. **Performance**, since you only query repositories for dependencies that should actually exist within them
2. **Security**, by avoiding asking potentially every repository for every dependency (even ones they shouldn’t contain), you improve resiliency to supply chain attacks by avoiding leaking information about your dependencies to other repositories, or even downloading potentially malicious artifacts
3. **Reliability**, by avoiding searching repositories that contain invalid or incorrect metadata for particular dependencies, which could result in obtaining incorrect transitive dependencies

Repositories will be searched for dependencies that pass their filters in the order they are declared. Often the last repository is declared without any filters in order to serve as a default *fallback repository* that is queried for any dependencies that don’t pass the filters present on the other repositories.

|  | Carefully consider using content filtering with a fallback repository. This can pose a security risk, so make sure you fully trust the fallback repository. This setup can result in inadvertently (and silently) resolving dependencies from the fallback repository that were intended to come from filtered repositories if the dependencies were not available in those repositories. |
| --- | --- |

Don’t add multiple repositories without content filtering:

```kotlin
KotlinGroovy
```
```groovy
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
```

Use content filtering to ensure that the proper repositories are searched first for the expected artifacts:

```kotlin
KotlinGroovy
```
```groovy
dependencyResolutionManagement {
    repositories {
        google {
            content {
                // Use this repository for androidx and GMS dependencies
                includeGroupByRegex("androidx.*")
                includeGroup("com.google.gms")
            }
        }
        // Specify the fallback repository last
        mavenCentral()
    }
}
```

In many cases, it is better to use exclusive content filtering, as it ensures that dependencies *can only be found in the expected repository*. If they are not present there, they will not be found at all.

```kotlin
KotlinGroovy
```
```groovy
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                google()
            }
            filter {
                // Only use this repository, and use this repository only, for androidx and GMS dependencies
                includeGroupByRegex("androidx.*")
                includeGroup("com.google.gms")
            }
        }
        // Specify the fallback repository last
        mavenCentral()
    }
}
```

When excluding transitive dependencies, apply exclusions as narrowly as possible.

Sometimes you may need to [exclude transitive dependencies](https://docs.gradle.org/current/userguide/how_to_exclude_transitive_dependencies.html#how_to_exclude_transitive_dependencies) that cause conflicts or issues in your project.

Exclusions can negatively affect dependency resolution performance. Applying exclusions as narrowly as possible minimizes this impact. It also reduces the risks of inadvertently and silently excluding dependencies that are required elsewhere in your build, and of accidental runtime dependency clashes.

Gradle offers several ways to exclude transitive dependencies. When excluding transitive dependencies, keep the scope as narrow as possible:

- Attach exclusions to **specific dependencies** rather than applying them to an entire configuration.
- Exclude a single `module` from a `group`, instead of excluding the entire `group`.
- **Avoid** global exclusions using `configurations.all { …​ }` or `configurations.configureEach { …​ }`.

```kotlin
KotlinGroovy
```
```groovy
dependencies {
    implementation("org.apache.commons:commons-pool2:2.12.1") (1)
    implementation("org.hibernate:hibernate-core:3.6.10.Final")
    // ... other dependencies ...
}

configurations {
    implementation {
        exclude(group: "cglib") (2)
    }

    implementation {
        exclude(group: "org.ow2.asm", module: "asm-util") (3)
    }
}

configurations.configureEach {
    exclude(group: "javassist", module: "javassist") (4)
}
```

| **1** | The `commons-pool2` dependency transitively includes `cglib:cglib` and `org.ow2.asm:asm-util` as optional dependencies - we want to exclude both. `hibernate-core` transitively optionally includes `cglib:cglib`, and also `javaassist:javassist` - we want to exclude both. |
| --- | --- |
| **2** | This excludes **every** module provided by the `cglib` group from **every** dependency in the `implementation` configuration. If other current or future dependencies in this project rely on different modules from `cglib`, those dependencies may fail to resolve, leading to compilation or runtime errors. |
| **3** | This excludes `org.ow2.asm:asm-util` from **every** dependency in the `implementation` configuration. If future dependencies rely on `org.ow2.asm:asm-util`, they may fail at compile or runtime because the module will be silently excluded. |
| **4** | This excludes `javaassist:javassist` from **all** dependencies in **all** configurations, including those added by plugins or in the future, which carries the same risks as above, but on a larger scale. |

Exclude transitive dependencies as narrowly as possible, ideally on individual dependencies:

```kotlin
KotlinGroovy
```
```groovy
dependencies {
    implementation("org.apache.commons:commons-pool2:2.12.1") { (1)
        exclude(group: "cglib", module: "cglib") (2)
        exclude(group: "org.ow2.asm", module: "asm-util")

    }
    implementation("org.hibernate:hibernate-core:3.6.10.Final") {
        exclude(group: "cglib", module: "cglib")
        exclude(group: "javassist", module: "javassist") (3)
    }
    // ... other dependencies ...
}
```

| **1** | Exclusions are applied only to the dependency that actually transitively includes them. |
| --- | --- |
| **2** | All exclusions apply to a particular `module` instead of every module from a particular `group`. |
| **3** | `javaassist:javassist` is only excluded from the `hibernate-core` dependency - the only dependency that transitively includes it. |

Though it may seem repetitive to exclude the same transitive dependencies from multiple dependencies, this approach is safer, more performant, less likely to cause accidental runtime crashes, and makes it clearer which dependencies are affected by each exclusion.

`#dependencies`