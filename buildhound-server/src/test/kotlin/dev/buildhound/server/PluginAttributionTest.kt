package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [PluginAttribution]'s FQCN-prefix → plugin-label catalog (plan 058, research F8 Layer 1) with a
 * golden list, so the seed catalog can't silently drift. AGP/KGP/KSP are the primary Android-fleet
 * consumers (research F8); the third-party seed (Hilt/Dagger, Detekt, ktlint, Spotless, protobuf) is a
 * starter set, not exhaustive — an unrecognized prefix degrades to [PluginAttribution.UNATTRIBUTED],
 * never a crash or a silently dropped row.
 */
class PluginAttributionTest {

    @Test
    fun `AGP task types attribute to the Android Gradle Plugin`() {
        assertEquals(
            "Android Gradle Plugin",
            PluginAttribution.owningPlugin("com.android.build.gradle.tasks.MergeResources"),
        )
    }

    @Test
    fun `KGP task types attribute to the Kotlin Gradle Plugin, incl KotlinCompile for free`() {
        assertEquals(
            "Kotlin Gradle Plugin",
            PluginAttribution.owningPlugin("org.jetbrains.kotlin.gradle.tasks.KotlinCompile"),
        )
    }

    @Test
    fun `KSP task types attribute to KSP`() {
        assertEquals("KSP", PluginAttribution.owningPlugin("com.google.devtools.ksp.gradle.KspTaskJvm"))
    }

    @Test
    fun `the third-party seed namespaces attribute correctly`() {
        assertEquals("Hilt/Dagger", PluginAttribution.owningPlugin("dagger.hilt.android.plugin.HiltAggregateDepsTask"))
        assertEquals("Detekt", PluginAttribution.owningPlugin("io.gitlab.arturbosch.detekt.Detekt"))
        assertEquals("ktlint", PluginAttribution.owningPlugin("org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask"))
        assertEquals("Spotless", PluginAttribution.owningPlugin("com.diffplug.gradle.spotless.SpotlessTask"))
        assertEquals("Protobuf Gradle Plugin", PluginAttribution.owningPlugin("com.google.protobuf.gradle.GenerateProtoTask"))
    }

    @Test
    fun `core org gradle types attribute to the distinct Gradle core bucket, not unattributed`() {
        for (type in listOf(
            "org.gradle.api.tasks.compile.JavaCompile",
            "org.gradle.api.tasks.testing.Test",
            "org.gradle.api.tasks.Copy",
        )) {
            assertEquals("Gradle core", PluginAttribution.owningPlugin(type), type)
        }
    }

    @Test
    fun `null, DefaultTask, and build-script-defined types fall into the honest unattributed bucket`() {
        for (type in listOf(null, "org.gradle.api.DefaultTask", "com.example.WriteVersionTask", "MyBuildScriptTask")) {
            assertEquals("(unattributed)", PluginAttribution.owningPlugin(type), "$type")
        }
    }

    @Test
    fun `unattributed and gradle-core are exposed as named constants matching the string literals`() {
        assertEquals(PluginAttribution.GRADLE_CORE, PluginAttribution.owningPlugin("org.gradle.api.tasks.compile.JavaCompile"))
        assertEquals(PluginAttribution.UNATTRIBUTED, PluginAttribution.owningPlugin(null))
    }
}
