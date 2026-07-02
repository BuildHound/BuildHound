plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

description = "Multi-tenant ingestion service and dashboard backend (Ktor)"

// JDK 26 builds the code; bytecode/API stay Java 21 (plan 011).
val buildToolchain = (findProperty("buildhound.toolchain") as? String)?.toIntOrNull() ?: 26

kotlin {
    jvmToolchain(buildToolchain)
    compilerOptions {
        // The OCI runtime image stays JRE 21.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

application {
    mainClass = "dev.buildhound.server.ApplicationKt"
}

dependencies {
    implementation(projects.buildhoundCommons)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.hikaricp)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
