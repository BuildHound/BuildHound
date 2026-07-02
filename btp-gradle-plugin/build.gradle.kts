import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

description = "Settings plugin collecting build/task telemetry (configuration-cache safe)"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Compatibility contract (spec §3.1): the plugin runs on Java 11+ inside Gradle 8.0+.
        jvmTarget = JvmTarget.JVM_11
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation(projects.btpCommons)

    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// TestKit-based functional tests, kept apart from unit tests so a Gradle-version
// matrix (roadmap phase 0) can later run them against multiple distributions.
val functionalTest: SourceSet by sourceSets.creating

dependencies {
    // Custom source sets are outside KGP's automatic kotlin("test") version management,
    // so the test stack is declared explicitly here.
    "functionalTestImplementation"(libs.kotlin.test.junit5)
    "functionalTestImplementation"(libs.junit.jupiter)
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestRuntimeOnly"(libs.junit.platform.launcher)
}

gradlePlugin {
    testSourceSets(functionalTest)
    plugins {
        create("buildTelemetry") {
            // Placeholder id — final coordinates are deferred (spec decision #6).
            id = "io.example.buildtelemetry"
            implementationClass = "io.example.btp.gradle.BuildTelemetrySettingsPlugin"
            displayName = "Build Telemetry"
            description = "Collects Gradle build, task, and cache telemetry and ships it to a BTP server"
            tags = listOf("telemetry", "build-performance", "observability")
        }
    }
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs TestKit functional tests"
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
}

tasks.check {
    dependsOn(functionalTestTask)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
