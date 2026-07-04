plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-gradle-plugin`
}

description = "Opt-in test-sharding addon: server-balanced LPT shard plans over Test tasks (plan 040)"

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
        create("buildhoundTestSharding") {
            id = "dev.buildhound.test-sharding"
            implementationClass = "dev.buildhound.sharding.TestShardingSettingsPlugin"
            displayName = "BuildHound test sharding"
            description = "Opt-in: fetch a server-balanced shard plan and split Test tasks across CI shards"
            tags = listOf("telemetry", "test-distribution", "build-performance")
        }
    }
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs TestKit functional tests for the test-sharding module"
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
