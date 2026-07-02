plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    // Placeholder coordinates — final name/group is deferred (spec decision #6).
    group = "io.example.btp"
    version = "0.1.0-SNAPSHOT"
}
