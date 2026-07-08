package dev.buildhound.gradle

import dev.buildhound.commons.payload.PropertyOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-function tests for the plan-051 provenance attributor (spec: "the provenance attributor
 * as a pure function over (allowlist, project-props map, GUH-props map, sysprops, env)"). No
 * Gradle type in sight — plain maps in, a plain DTO list out.
 */
class GradlePropertyProvenanceTest {

    private val key = "org.gradle.caching"

    private fun resolveOne(
        project: Map<String, String> = emptyMap(),
        guh: Map<String, String> = emptyMap(),
        sysProps: Map<String, String> = emptyMap(),
        env: Map<String, String> = emptyMap(),
    ) = GradlePropertyProvenance.resolve(listOf(key), project, guh, sysProps, env).find { it.key == key }

    @Test
    fun `GUH-declares-and-wins over a project value`() {
        val result = resolveOne(project = mapOf(key to "false"), guh = mapOf(key to "true"))
        assertEquals("true", result?.value)
        assertEquals(PropertyOrigin.GRADLE_USER_HOME, result?.origin)
    }

    @Test
    fun `project-only attributes PROJECT`() {
        val result = resolveOne(project = mapOf(key to "true"))
        assertEquals("true", result?.value)
        assertEquals(PropertyOrigin.PROJECT, result?.origin)
    }

    @Test
    fun `a -D system property outranks both files`() {
        val result = resolveOne(
            project = mapOf(key to "false"),
            guh = mapOf(key to "false"),
            sysProps = mapOf(key to "true"),
        )
        assertEquals("true", result?.value)
        assertEquals(PropertyOrigin.OVERRIDE, result?.origin)
    }

    @Test
    fun `absent from every layer omits the key entirely`() {
        assertNull(resolveOne())
        assertTrue(GradlePropertyProvenance.resolve(listOf(key), emptyMap(), emptyMap(), emptyMap(), emptyMap()).isEmpty())
    }

    @Test
    fun `value-equal-across-layers still attributes GUH — presence, not equality`() {
        // Both layers agree on the value; GUH still wins because it is the higher-precedence layer
        // that *declares* it, not because the values differ (the presence-not-equality guard).
        val result = resolveOne(project = mapOf(key to "true"), guh = mapOf(key to "true"))
        assertEquals(PropertyOrigin.GRADLE_USER_HOME, result?.origin)
    }

    @Test
    fun `a value resolvable only via env is emitted as UNKNOWN, never guessed`() {
        val result = resolveOne(env = mapOf("ORG_GRADLE_PROJECT_$key" to "true"))
        assertEquals("true", result?.value)
        assertEquals(PropertyOrigin.UNKNOWN, result?.origin)
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
}
