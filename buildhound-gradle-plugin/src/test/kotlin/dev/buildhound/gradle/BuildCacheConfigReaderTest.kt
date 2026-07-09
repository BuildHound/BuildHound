package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit coverage for the load-bearing pure normalizer (plan 067): Gradle decorates a DSL-instantiated
 * backend through a generated subclass (`…_Decorated`) that may be proxied (`…$$…`), so the raw
 * `javaClass.simpleName` is not the stable wire value. This is Gradle-free (no build), so the exact
 * decoration shape is exercised here with adversarial inputs; the functionalTest asserts the final
 * string against a real Gradle daemon as ground truth.
 */
class BuildCacheConfigReaderTest {

    @Test
    fun `plain undecorated class name passes through`() {
        assertEquals("HttpBuildCache", BuildCacheConfigReader.normalizeRemoteType("HttpBuildCache"))
        assertEquals("DirectoryBuildCache", BuildCacheConfigReader.normalizeRemoteType("DirectoryBuildCache"))
    }

    @Test
    fun `Gradle decoration suffix is stripped`() {
        assertEquals("HttpBuildCache", BuildCacheConfigReader.normalizeRemoteType("HttpBuildCache_Decorated"))
    }

    @Test
    fun `proxy dollar-dollar subclass is stripped`() {
        assertEquals("HttpBuildCache", BuildCacheConfigReader.normalizeRemoteType("HttpBuildCache\$\$EnhancerByGradle"))
        // Decoration + proxy together still normalize to the backend name.
        assertEquals("HttpBuildCache", BuildCacheConfigReader.normalizeRemoteType("HttpBuildCache_Decorated\$\$Proxy"))
    }

    @Test
    fun `a custom user backend keeps its own simpleName`() {
        assertEquals("AcmeS3BuildCache", BuildCacheConfigReader.normalizeRemoteType("AcmeS3BuildCache_Decorated"))
    }

    @Test
    fun `a legitimate single-dollar simpleName is preserved — only the double-dollar proxy marker is stripped`() {
        // Not every '$' is Gradle's proxy marker (`…$$…`, per the class doc): a genuine simpleName that
        // happens to contain exactly one (an obfuscated/framework-generated backend class) must survive,
        // unlike the old substringBefore('$') which truncated it to "Foo".
        assertEquals("Foo\$Bar", BuildCacheConfigReader.normalizeRemoteType("Foo\$Bar"))
    }

    @Test
    fun `null blank and strip-to-empty degrade to null, never a fabricated name`() {
        assertNull(BuildCacheConfigReader.normalizeRemoteType(null))
        assertNull(BuildCacheConfigReader.normalizeRemoteType(""))
        assertNull(BuildCacheConfigReader.normalizeRemoteType("   "))
        // A pathological all-decoration name (nothing but the marker) strips to empty → honest null.
        assertNull(BuildCacheConfigReader.normalizeRemoteType("_Decorated"))
        assertNull(BuildCacheConfigReader.normalizeRemoteType("\$\$Proxy"))
    }
}
