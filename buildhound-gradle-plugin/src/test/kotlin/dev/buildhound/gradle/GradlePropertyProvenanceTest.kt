package dev.buildhound.gradle

import dev.buildhound.commons.payload.PropertyOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-function tests for the plan-051 provenance attributor (spec: "the provenance attributor
 * as a pure function over (allowlist, project-props map, GUH-props map, sysprops, env, cli
 * project-properties map)"). No Gradle type in sight — plain maps in, a plain DTO list out.
 *
 * Precedence is **per key family** (plan 051 review fix): `org.gradle.*` keys are Gradle
 * properties whose override channel is a `-D` system property; `android.*` keys are AGP-read
 * project properties whose override channel is `-P`/`ORG_GRADLE_PROJECT_*`, never `-D`. The two
 * families are tested separately below.
 */
class GradlePropertyProvenanceTest {

    private val key = "org.gradle.caching"
    private val androidKey = "android.nonTransitiveRClass"

    private fun resolveOne(
        key: String,
        project: Map<String, String> = emptyMap(),
        guh: Map<String, String> = emptyMap(),
        sysProps: Map<String, String> = emptyMap(),
        env: Map<String, String> = emptyMap(),
        cliProjectProperties: Map<String, String> = emptyMap(),
    ) = GradlePropertyProvenance.resolve(listOf(key), project, guh, sysProps, env, cliProjectProperties)
        .find { it.key == key }

    // --- org.gradle.* family: -D is the override channel, env is never attributable. ---

    @Test
    fun `GUH-declares-and-wins over a project value`() {
        val result = resolveOne(key, project = mapOf(key to "false"), guh = mapOf(key to "true"))
        assertEquals("true", result?.value)
        assertEquals(PropertyOrigin.GRADLE_USER_HOME, result?.origin)
    }

    @Test
    fun `project-only attributes PROJECT`() {
        val result = resolveOne(key, project = mapOf(key to "true"))
        assertEquals("true", result?.value)
        assertEquals(PropertyOrigin.PROJECT, result?.origin)
    }

    @Test
    fun `a -D system property outranks both files for an org-gradle key`() {
        val result = resolveOne(
            key,
            project = mapOf(key to "false"),
            guh = mapOf(key to "false"),
            sysProps = mapOf(key to "true"),
        )
        assertEquals("true", result?.value)
        assertEquals(PropertyOrigin.OVERRIDE, result?.origin)
    }

    @Test
    fun `absent from every layer omits the key entirely`() {
        assertNull(resolveOne(key))
        assertTrue(GradlePropertyProvenance.resolve(listOf(key), emptyMap(), emptyMap(), emptyMap(), emptyMap()).isEmpty())
    }

    @Test
    fun `value-equal-across-layers still attributes GUH — presence, not equality`() {
        // Both layers agree on the value; GUH still wins because it is the higher-precedence layer
        // that *declares* it, not because the values differ (the presence-not-equality guard).
        val result = resolveOne(key, project = mapOf(key to "true"), guh = mapOf(key to "true"))
        assertEquals(PropertyOrigin.GRADLE_USER_HOME, result?.origin)
    }

    @Test
    fun `an org-gradle key resolvable only via env is emitted as UNKNOWN, never guessed`() {
        val result = resolveOne(key, env = mapOf("ORG_GRADLE_PROJECT_$key" to "true"))
        assertEquals("true", result?.value)
        assertEquals(PropertyOrigin.UNKNOWN, result?.origin)
    }

    @Test
    fun `a -P value for an org-gradle key is not a valid channel — ignored, not OVERRIDE`() {
        // org.gradle.* is a Gradle property, not a project property: -P is not its command-line
        // channel, so a value that only appears via -P must not be guessed as an override.
        val result = resolveOne(key, cliProjectProperties = mapOf(key to "true"))
        assertNull(result)
    }

    @Test
    fun `only allowlisted keys are ever considered`() {
        val result = GradlePropertyProvenance.resolve(
            allowlist = listOf(key),
            projectProps = mapOf(key to "true", "some.other.key" to "true"),
            guhProps = emptyMap(),
            sysProps = emptyMap(),
        )
        assertEquals(1, result.size)
        assertEquals(key, result.single().key)
    }

    // --- android.* family: -P / ORG_GRADLE_PROJECT_* is the override channel, -D is never valid. ---

    @Test
    fun `-P outranks both files for an android key`() {
        val result = resolveOne(
            androidKey,
            project = mapOf(androidKey to "false"),
            guh = mapOf(androidKey to "false"),
            cliProjectProperties = mapOf(androidKey to "true"),
        )
        assertEquals("true", result?.value)
        assertEquals(PropertyOrigin.OVERRIDE, result?.origin, "-P is the android.* family's real command-line channel")
    }

    @Test
    fun `ORG_GRADLE_PROJECT_ env attributes OVERRIDE for an android key, unlike the org-gradle family`() {
        val result = resolveOne(androidKey, env = mapOf("ORG_GRADLE_PROJECT_$androidKey" to "true"))
        assertEquals("true", result?.value)
        assertEquals(PropertyOrigin.OVERRIDE, result?.origin)
    }

    @Test
    fun `-P wins over ORG_GRADLE_PROJECT_ env when both are present`() {
        val result = resolveOne(
            androidKey,
            cliProjectProperties = mapOf(androidKey to "true"),
            env = mapOf("ORG_GRADLE_PROJECT_$androidKey" to "false"),
        )
        assertEquals("true", result?.value, "-P (command line) outranks env, same as Gradle's own layering")
    }

    @Test
    fun `a -D system property is not a valid channel for an android key — ignored, not OVERRIDE`() {
        // This is the HIGH-severity bug this fix addresses: -D never reaches AGP's project-property
        // lookup, so it must never be reported as an override for android.* keys.
        val result = resolveOne(androidKey, sysProps = mapOf(androidKey to "true"))
        assertNull(result, "a bare -D on an android.* key has no confirmed effective value")
    }

    @Test
    fun `GUH file still wins over the project file for an android key when no override is present`() {
        val result = resolveOne(androidKey, project = mapOf(androidKey to "false"), guh = mapOf(androidKey to "true"))
        assertEquals("true", result?.value)
        assertEquals(PropertyOrigin.GRADLE_USER_HOME, result?.origin)
    }

    @Test
    fun `project-file-only attributes PROJECT for an android key`() {
        val result = resolveOne(androidKey, project = mapOf(androidKey to "true"))
        assertEquals("true", result?.value)
        assertEquals(PropertyOrigin.PROJECT, result?.origin)
    }
}
