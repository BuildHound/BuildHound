import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.plugin.DetektPlugin
import dev.detekt.gradle.report.ReportMergeTask

plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt)
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

subprojects {
    plugins.apply {
        matching { it is DetektPlugin }.whenPluginAdded {
            configure<DetektExtension> {
                toolVersion = libs.versions.detekt
                config.setFrom(rootProject.layout.projectDirectory.file("gradle/detekt/detekt.yml"))

            }
        }
    }
}

detekt {
    toolVersion = libs.versions.detekt
    config.setFrom(rootProject.layout.projectDirectory.file("gradle/detekt/detekt.yml"))
    buildUponDefaultConfig = true
}

tasks.withType<Detekt>().configureEach {
    reports {
        checkstyle.required.set(false)
        html.required.set(true)
        markdown.required.set(true)
        sarif.required.set(false)
    }
}
