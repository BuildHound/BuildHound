plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

description = "Shared payload schema and CI-provider SPI, shared between the Gradle plugin and the server"

kotlin {
    jvmToolchain(21)

    jvm()
    // Future targets: js() for btp-report, native for the metric CLI (btp-ci-assets).

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
