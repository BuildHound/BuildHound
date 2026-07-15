plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

description = "Internal-adapters capture bundled with the core plugin: cache origin/keys, critical path, " +
    "deprecation + WARN-log warnings (plan 038/044). Uses internal Gradle APIs; dormant until a " +
    "buildhound { internalAdapters { } } toggle is set (plan 074)."

// JDK 26 builds the code; bytecode/API stay Java 21 (same pins as the core plugin, plan 011).
val buildToolchain = (findProperty("buildhound.toolchain") as? String)?.toIntOrNull() ?: 26

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    // Rides the Gradle settings classpath on Gradle's embedded Kotlin stdlib (architecture §2 rule 10).
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
        // apiVersion 2.0 is a deliberate pin (Gradle's embedded Kotlin stdlib, architecture §2 rule 10);
        // silence the newer build compiler's "2.0 is deprecated" advisory rather than bumping it.
        freeCompilerArgs.add("-Xsuppress-version-warnings")
    }
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(buildToolchain))
        if (buildToolchain == 26) vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

dependencies {
    // This module no longer declares a Gradle plugin (plan 074). Keep Gradle's public + internal
    // types compile-only so its runtime variant can be bundled without exporting the host Gradle,
    // Groovy, and Kotlin distribution into the Plugin Portal artifact (plan 092).
    compileOnly(gradleApi())
    implementation(projects.buildhoundCommons)

    testImplementation(kotlin("test"))
    testImplementation(gradleApi())
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.withType<Test>().configureEach {
    // Compilation stays on 26; unit tests run on the consumer floor (JDK 21) for parity with core (plan 011).
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
