plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Development remains a SNAPSHOT. Portal releases inject an immutable version either as
// -Pbuildhound.version locally or BUILDHOUND_VERSION in the protected deploy workflow.
val buildhoundVersion =
    providers.gradleProperty("buildhound.version")
        .orElse(providers.environmentVariable("BUILDHOUND_VERSION"))
        .getOrElse("0.1.0-SNAPSHOT")

allprojects {
    // buildhound.dev (naming decision #6).
    group = "dev.buildhound"
    version = buildhoundVersion
}
