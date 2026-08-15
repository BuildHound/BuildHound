plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.detekt)
}

description = "Multi-tenant ingestion service and dashboard backend (Ktor)"

// JDK 26 builds the code AND is the bytecode/API target for this module (plan 111) — the
// plugin, commons and report stay at Java 21. `buildhound.toolchain` remains the local escape
// hatch, but it has a floor of 26 here: this module's release pins are hardcoded to 26
// independently of buildToolchain, so ANY buildhound.toolchain value below 26 (21, 24, 25 …)
// makes it uncompilable rather than merely slower.
val buildToolchain = (findProperty("buildhound.toolchain") as? String)?.toIntOrNull() ?: 26

java {
    // Keeps the variant attribute at JVM 26 — the runtime image is a JRE 26 (plan 111).
    sourceCompatibility = JavaVersion.VERSION_26
    targetCompatibility = JavaVersion.VERSION_26
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(buildToolchain))
        if (buildToolchain == 26) vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    compilerOptions {
        // The OCI runtime image is a JRE 26 (plan 111).
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26)
        freeCompilerArgs.add("-Xjdk-release=26")
    }
}


tasks.withType<JavaCompile>().configureEach {
    // Kotlin is API-capped by -Xjdk-release above — that is the pin doing the real work in
    // this Kotlin-only module. This is the javac equivalent, inert until the first .java file
    // is added, at which point it stops that file silently linking against >26 APIs (review
    // finding). Modules that stay at 21 keep their own -Xjdk-release pin, so moving code here
    // from commons is a compile error, not a runtime NoSuchMethodError (plan 111).
    options.release.set(26)
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
