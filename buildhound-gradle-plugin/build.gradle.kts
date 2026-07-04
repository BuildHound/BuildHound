plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

description = "Settings plugin collecting build/task telemetry (configuration-cache safe)"

// JDK 26 builds the code; bytecode/API stay Java 21 (plan 011).
val buildToolchain = (findProperty("buildhound.toolchain") as? String)?.toIntOrNull() ?: 26

java {
    // Consumer floor (owner request, plan 011): Gradle daemons on JDK 21+.
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
        // Shrink foojay's resolution space on the default path; overrides stay
        // vendor-free so any locally installed JDK can serve them.
        if (buildToolchain == 26) vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Kotlin is API-capped by -Xjdk-release; this is the javac equivalent so the first
    // .java file added can't silently link against >21 APIs (review finding).
    options.release.set(21)
}


dependencies {
    implementation(projects.buildhoundCommons)
    // Report template + renderer; the publishing chunk decides shading vs. resource
    // embedding (plan 006) — a module dependency is correct for now.
    implementation(projects.buildhoundReport)

    // AGP Variant API for the Android artifact-size collector (plan 031). compileOnly: the plugin
    // must apply cleanly to non-Android builds, and no AGP jar ships with it — the collector is
    // loaded only after a runtime class-probe confirms AGP is present.
    compileOnly(libs.android.gradle.api)

    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// TestKit-based functional tests, kept apart from unit tests so a Gradle-version
// matrix (roadmap phase 0) can later run them against multiple distributions.
val functionalTest: SourceSet = sourceSets.create("functionalTest")

dependencies {
    // Custom source sets are outside KGP's automatic kotlin("test") version management,
    // so the test stack is declared explicitly here.
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
        create("buildhound") {
            id = "dev.buildhound"
            implementationClass = "dev.buildhound.gradle.BuildHoundSettingsPlugin"
            displayName = "BuildHound"
            description = "Collects Gradle build, task, and cache telemetry and ships it to a BuildHound server"
            tags = listOf("telemetry", "build-performance", "observability")
        }
    }
}

// Inner-build CC mode for the TestKit suite (plan 021): forwarded from the
// `-Pbuildhound.testkit.cc` Gradle property (default `on`) as a system property the tests
// read. Provider-based so it is a proper CC input of this build (which itself keeps CC on).
val testkitCcMode: String = providers.gradleProperty("buildhound.testkit.cc").getOrElse("on")

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs TestKit functional tests (excludes the watched isolated-projects suite)"
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    // The isolated-projects suite is watched (non-blocking), run only by isolatedProjectsTest.
    useJUnitPlatform { excludeTags("isolated-projects") }
    systemProperty("buildhound.testkit.cc", testkitCcMode)
}

// Watched (non-blocking) isolated-projects suite (plan 021): runs the same functionalTest
// classes but only the @Tag("isolated-projects") cases; deliberately NOT wired into `check`.
val isolatedProjectsTestTask = tasks.register<Test>("isolatedProjectsTest") {
    description = "Runs the watched isolated-projects TestKit suite"
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform { includeTags("isolated-projects") }
}

tasks.check {
    dependsOn(functionalTestTask)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.withType<Test>().configureEach {
    // TestKit spawns the fixture Gradle on the test JVM, and the floor Gradle (8.14)
    // cannot RUN on JDK 26 — consumers run on 21+, so tests execute on the real
    // consumer floor while compilation stays on the 26 toolchain (plan 011).
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
