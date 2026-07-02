plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    // buildhound.dev (naming decision #6).
    group = "dev.buildhound"
    version = "0.1.0-SNAPSHOT"
}
