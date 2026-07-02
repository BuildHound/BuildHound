plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Standalone HTML build-report artifact: template + renderer, embedded into the plugin at build time"

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
