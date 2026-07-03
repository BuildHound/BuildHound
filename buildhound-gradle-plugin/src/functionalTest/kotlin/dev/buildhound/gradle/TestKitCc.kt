package dev.buildhound.gradle

/**
 * Inner-build configuration-cache mode for the TestKit suite (plan 021), chosen by the
 * `buildhound.testkit.cc` system property (`on` default / `off`) that the `functionalTest`
 * Gradle task forwards from the `-Pbuildhound.testkit.cc` property. This lets the whole
 * default suite run under both execution models in CI without per-test flags — CI adds one
 * blocking `-Pbuildhound.testkit.cc=off` job.
 *
 * Tests that pin configuration-cache semantics themselves (store/hit/disabled, CC-reuse
 * survival, isolated projects) do NOT use this — they pass explicit flags via the
 * `runnerExplicit` variant so they stay meaningful regardless of the harness mode.
 */
internal fun testkitCcFlag(): String =
    if (System.getProperty("buildhound.testkit.cc", "on").equals("off", ignoreCase = true)) {
        "--no-configuration-cache"
    } else {
        "--configuration-cache"
    }
