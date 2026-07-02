plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Standalone HTML build-report artifact: template + renderer, embedded into the plugin at build time"

kotlin {
    // This code rides the Gradle settings classpath and runs on Gradle's *embedded*
    // Kotlin stdlib (2.0 on Gradle 8.14, the support floor) — newer stdlib APIs throw
    // NoSuchMethodError at runtime (architecture §2 rule 10).
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }

    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
