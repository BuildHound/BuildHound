plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Standalone HTML build-report artifact: template + renderer, embedded into the plugin at build time"

// JDK 26 builds the code; bytecode/API stay Java 21 (plan 011).
val buildToolchain = (findProperty("buildhound.toolchain") as? String)?.toIntOrNull() ?: 26

java {
    // Keeps the variant attribute at JVM 21: consumers on a 21 daemon must resolve us.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    // This code rides the Gradle settings classpath and runs on Gradle's *embedded*
    // Kotlin stdlib (2.0 on Gradle 8.14, the support floor) — newer stdlib APIs throw
    // NoSuchMethodError at runtime (architecture §2 rule 10).
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
    // Kotlin is API-capped by -Xjdk-release; this is the javac equivalent so the first
    // .java file added can't silently link against >21 APIs (review finding).
    options.release.set(21)
}


dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
