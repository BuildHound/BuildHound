plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-gradle-plugin`
}

description = "Opt-in internal-adapters plugin: cache origin, cache keys, tier-b fingerprints (plan 038)"

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
    implementation(projects.buildhoundCommons)

    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// TestKit functional tests, kept apart so a Gradle-version matrix can run them (plan 021).
val functionalTest: SourceSet = sourceSets.create("functionalTest")

dependencies {
    "functionalTestImplementation"(projects.buildhoundCommons)
    "functionalTestImplementation"(libs.kotlinx.serialization.json)
    "functionalTestImplementation"(libs.kotlin.test.junit5)
    "functionalTestImplementation"(libs.junit.jupiter)
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestRuntimeOnly"(libs.junit.platform.launcher)
}

gradlePlugin {
    testSourceSets(functionalTest)
    plugins {
        create("buildhoundInternalAdapters") {
            id = "dev.buildhound.internal-adapters"
            implementationClass = "dev.buildhound.internaladapters.InternalAdaptersSettingsPlugin"
            displayName = "BuildHound internal adapters"
            description = "Opt-in cache-origin, cache-key, and input-fingerprint capture via internal Gradle build operations"
            tags = listOf("telemetry", "build-cache", "build-performance")
        }
    }
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs TestKit functional tests for the internal-adapters module"
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
}

tasks.check {
    dependsOn(functionalTestTask)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.withType<Test>().configureEach {
    // TestKit runs the fixture Gradle on the consumer floor (JDK 21); compilation stays on 26 (plan 011).
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
