// Overhead self-benchmark fixture (plan 034). gradle-profiler drives THIS build twice — plugin
// applied vs not — via -Pbuildhound.overhead.plugin=on|off, and the OverheadCalculator turns the two
// benchmark.csv outputs into a per-axis verdict. Synthetic 3-module Kotlin/JVM project: no Android,
// no AGP, so any Linux runner builds it.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // The BuildHound build supplies the `dev.buildhound` settings plugin as a composite include, so
    // the fixture measures the plugin exactly as built in this repo — no publish step, no version pin.
    includeBuild("../../..")
}

plugins {
    // On the settings classpath but not applied: the toggle below decides whether it runs, so the
    // plugin-off variant links nothing of BuildHound (a true zero-overhead baseline).
    id("dev.buildhound") apply false
}

// The toggle. All plugin configuration is via gradle.properties `buildhound.*` overrides (plan 027),
// not a DSL block here, so the settings script stays free of BuildHound types.
if (providers.gradleProperty("buildhound.overhead.plugin").getOrElse("off") == "on") {
    pluginManager.apply("dev.buildhound")
}

rootProject.name = "overhead-fixture"
include(":lib-a", ":lib-b")
