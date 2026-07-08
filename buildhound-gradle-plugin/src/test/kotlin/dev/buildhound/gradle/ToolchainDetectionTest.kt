package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the version-extraction mechanics of [ToolchainDetection] (plan 046). The
 * per-project probes themselves need a live Gradle project graph with real AGP/KGP/KSP applied, so
 * they are exercised end-to-end by the TestKit suite (via the internal seam) and against the
 * nowinandroid sample; here we pin the pure reflective helper the KGP probe relies on.
 */
class ToolchainDetectionTest {

    @Suppress("unused") // invoked reflectively
    private class FakeKotlinPlugin {
        fun getPluginVersion(): String = "2.4.0"
        fun getPreview(): Int = 7
    }

    private class NoVersionPlugin

    @Test
    fun `noArgStringGetter reads a declared no-arg String getter`() {
        assertEquals("2.4.0", ToolchainDetection.noArgStringGetter(FakeKotlinPlugin(), "getPluginVersion"))
    }

    @Test
    fun `noArgStringGetter returns null for an absent method`() {
        assertNull(ToolchainDetection.noArgStringGetter(NoVersionPlugin(), "getPluginVersion"))
    }

    @Test
    fun `noArgStringGetter returns null when the getter is not a String`() {
        // getPreview() returns Int → the `as? String` cast yields null rather than throwing.
        assertNull(ToolchainDetection.noArgStringGetter(FakeKotlinPlugin(), "getPreview"))
    }

    @Test
    fun `invokeNoArg returns the raw getter result or null`() {
        // The AGP (pluginVersion-getVersion) and KSP (getPluginArtifact-getVersion) chains lean on this
        // generic helper, then narrow the result themselves.
        assertEquals("2.4.0", ToolchainDetection.invokeNoArg(FakeKotlinPlugin(), "getPluginVersion"))
        assertEquals(7, ToolchainDetection.invokeNoArg(FakeKotlinPlugin(), "getPreview"))
        assertNull(ToolchainDetection.invokeNoArg(NoVersionPlugin(), "getPluginVersion"))
    }

    @Test
    fun `DetectedToolchain is empty only when every dimension is null`() {
        assertTrue(DetectedToolchain().isEmpty())
        assertFalse(DetectedToolchain(agp = "8.9.0").isEmpty())
        assertFalse(DetectedToolchain(kgp = "2.4.0").isEmpty())
        assertFalse(DetectedToolchain(ksp = "2.4.0-1.0.0").isEmpty())
        // plan 072: springBoot folds into isEmpty() so the internal seam recognizes a springBoot-only set.
        assertFalse(DetectedToolchain(springBoot = "3.3.2").isEmpty())
    }

    // Note (plan 072 test-strategy divergence): `springBootVersion` reads
    // `plugin.javaClass.getPackage().implementationVersion` — a jar-manifest read with no pure reflective
    // seam to inject (unlike the KGP `getPluginVersion` helper above), so a "version present vs
    // honest-null" unit case can't be written without a real manifested jar. It follows the exact same
    // untested-manifest-fallback precedent as KSP's `getPackage().implementationVersion` branch; presence
    // + the whenReady→finalizer channel are covered end-to-end by ToolchainFunctionalTest's springBoot seam.
}
