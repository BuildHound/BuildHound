plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

description = "Shared payload schema and CI-provider SPI, shared between the Gradle plugin and the server"

// JDK 26 builds the code; bytecode/API stay Java 21 (plan 011).
val buildToolchain = (findProperty("buildhound.toolchain") as? String)?.toIntOrNull() ?: 26

kotlin {
    // This code rides the Gradle settings classpath and runs on Gradle's *embedded*
    // Kotlin stdlib (2.0 on Gradle 8.14, the support floor) — newer stdlib APIs throw
    // NoSuchMethodError at runtime (architecture §2 rule 10).
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }

    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(buildToolchain))
        if (buildToolchain == 26) vendor.set(JvmVendorSpec.ADOPTIUM)
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjdk-release=21")
        }
        // Keeps the variant attribute at JVM 21: consumers on a 21 daemon must resolve us.
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
        }
    }
    // Future targets: js() for buildhound-report, native for the metric CLI (buildhound-ci-assets).

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// Plugin-overhead verdict tool (plan 034): a thin JavaExec over the pure OverheadCalculator, run by
// buildhound-ci-assets/overhead/bin/buildhound-overhead. Non-zero exit on a budget breach fails the
// task (and the overhead-budget CI job). Pass the two gradle-profiler CSVs via -Poverhead.on/.off.
// The `run {}` scope is LOAD-BEARING, not cosmetic: it makes `onCsv`/`offCsv` *locals* the
// CommandLineArgumentProvider lambda captures directly (serializable Provider<String>), instead of
// script-object fields — capturing the latter drags a non-serializable script reference into the
// configuration cache and fails the task. Do not hoist these to script scope (the repo keeps CC on).
run {
    val onCsv = providers.gradleProperty("overhead.on")
    val offCsv = providers.gradleProperty("overhead.off")
    tasks.register<JavaExec>("overheadVerdict") {
        group = "verification"
        description = "Evaluate a gradle-profiler plugin-on/off overhead run against the budget (plan 034)."
        val mainCompilation = kotlin.jvm().compilations.getByName("main")
        classpath = files(mainCompilation.output.allOutputs, mainCompilation.runtimeDependencyFiles)
        mainClass.set("dev.buildhound.commons.overhead.OverheadCliKt")
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    onCsv.orNull ?: error("set -Poverhead.on=<plugin-on benchmark.csv>"),
                    offCsv.orNull ?: error("set -Poverhead.off=<plugin-off benchmark.csv>"),
                )
            },
        )
    }
}
