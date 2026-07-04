plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

description = "Opt-in read-only MCP server: a stdio query surface over the BuildHound /v1 API (plan 042)"

// JDK 26 builds the code; bytecode/API stay Java 21 (same pins as every module, plan 011).
val buildToolchain = (findProperty("buildhound.toolchain") as? String)?.toIntOrNull() ?: 26

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
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

application {
    mainClass = "dev.buildhound.mcp.MainKt"
}

dependencies {
    // Deliberately dependency-light: only kotlinx-serialization for JSON-RPC framing + the JDK
    // HttpClient for the outbound read GET. No MCP SDK (0.x, API-churning) is pulled in — the
    // read-only surface is small enough to speak JSON-RPC-over-stdio directly (see README).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
