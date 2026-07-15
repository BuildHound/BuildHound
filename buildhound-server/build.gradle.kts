plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

description = "Multi-tenant ingestion service and dashboard backend (Ktor)"

// JDK 26 builds the code; bytecode/API stay Java 21 (plan 011).
val buildToolchain = (findProperty("buildhound.toolchain") as? String)?.toIntOrNull() ?: 26

java {
    // Keeps the variant attribute at JVM 21 — the runtime image is a JRE 21.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(buildToolchain))
        if (buildToolchain == 26) vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    compilerOptions {
        // The OCI runtime image stays JRE 21.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}


tasks.withType<JavaCompile>().configureEach {
    // Kotlin is API-capped by -Xjdk-release; this is the javac equivalent so the first
    // .java file added can't silently link against >21 APIs (review finding).
    options.release.set(21)
}

application {
    mainClass = "dev.buildhound.server.ApplicationKt"
}

dependencies {
    implementation(projects.buildhoundCommons)
    // Shared payload-rendering channel (plan 017): the dashboard serves the same timeline
    // renderer the HTML artifact inlines. buildhound-report is dependency-free by rule
    // (architecture §1), so nothing transitive arrives — resources plus one object.
    implementation(projects.buildhoundReport)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.serialization.kotlinx.json)
    // Outbound HTTP for CI connectors (plan 028): the CIO engine is pure-JVM (no native deps).
    // Timeline JSON is parsed defensively via JsonElement (schema-drift tolerant), so no client
    // content-negotiation is needed.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.logback.classic)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.hikaricp)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The OpenAPI spec (plan 042) has a single source of truth in docs/api/openapi.yaml; copy it onto the
// classpath at build time (served at GET /openapi.yaml) so a committed resource twin can never drift.
tasks.named<Copy>("processResources") {
    from(rootProject.file("docs/api/openapi.yaml")) { into("api") }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
