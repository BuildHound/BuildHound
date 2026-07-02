plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

description = "Settings plugin collecting build/task telemetry (configuration-cache safe)"

kotlin {
    // Java 21 floor for the whole platform (decision log 2026-07-02): the plugin requires
    // Gradle running on JDK 21+.
    jvmToolchain(21)
}

dependencies {
    implementation(projects.buildhoundCommons)

    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// TestKit-based functional tests, kept apart from unit tests so a Gradle-version
// matrix (roadmap phase 0) can later run them against multiple distributions.
val functionalTest: SourceSet = sourceSets.create("functionalTest")

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
        create("buildhound") {
            id = "dev.buildhound"
            implementationClass = "dev.buildhound.gradle.BuildHoundSettingsPlugin"
            displayName = "BuildHound"
            description = "Collects Gradle build, task, and cache telemetry and ships it to a BuildHound server"
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
