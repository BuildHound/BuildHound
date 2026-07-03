package dev.buildhound.commons.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GenericCiEnvironmentProviderTest {

    private val provider = GenericCiEnvironmentProvider()

    @Test
    fun does_not_detect_without_any_ci_marker() {
        assertNull(provider.detect(emptyMap()))
        assertNull(provider.detect(mapOf("HOME" to "/home/agent")))
    }

    @Test
    fun detects_bare_ci_variable_with_no_mapped_fields() {
        // Presence semantics: empty and whitespace values still count as set.
        for (value in listOf("true", "True", "1", "", " ")) {
            val context = provider.detect(mapOf("CI" to value, "HOME" to "/home/agent"))

            assertEquals("generic", context?.provider, "CI=$value")
            assertNull(context?.runId, "CI=$value")
            assertNull(context?.branch, "CI=$value")
            assertNull(context?.buildUrl, "CI=$value")
        }
    }

    @Test
    fun explicit_falsy_ci_value_is_not_ci() {
        assertNull(provider.detect(mapOf("CI" to "false")))
        assertNull(provider.detect(mapOf("CI" to "FALSE")))
        assertNull(provider.detect(mapOf("CI" to "0")))
    }

    @Test
    fun truthy_buildhound_ci_variants_activate_the_mapping() {
        for (value in listOf("true", "TRUE", "1")) {
            assertEquals("generic", provider.detect(mapOf("BUILDHOUND_CI" to value))?.provider, "BUILDHOUND_CI=$value")
        }
    }

    @Test
    fun falsy_buildhound_ci_suppresses_the_bare_ci_fallback() {
        assertNull(provider.detect(mapOf("BUILDHOUND_CI" to "false", "CI" to "true")))
        assertNull(provider.detect(mapOf("BUILDHOUND_CI" to "0", "CI" to "true")))
    }

    @Test
    fun falsy_buildhound_ci_is_the_kill_switch_even_with_a_provider_set() {
        assertNull(
            provider.detect(
                mapOf("BUILDHOUND_CI" to "false", "BUILDHOUND_CI_PROVIDER" to "my-inhouse-ci", "CI" to "true"),
            ),
        )
    }

    @Test
    fun buildhound_variables_win_over_bare_ci() {
        val context = provider.detect(
            mapOf("CI" to "true", "BUILDHOUND_CI_PROVIDER" to "my-inhouse-ci", "BUILDHOUND_CI_RUN_ID" to "42"),
        )

        assertEquals("my-inhouse-ci", context?.provider)
        assertEquals("42", context?.runId)
    }

    @Test
    fun detects_from_generic_env_vars() {
        val context = provider.detect(
            mapOf(
                "BUILDHOUND_CI" to "true",
                "BUILDHOUND_CI_PROVIDER" to "my-inhouse-ci",
                "BUILDHOUND_CI_RUN_ID" to "42",
                "BUILDHOUND_CI_BRANCH" to "main",
                "BUILDHOUND_CI_BUILD_URL" to "https://ci.example.com/run/42",
            ),
        )

        assertEquals("my-inhouse-ci", context?.provider)
        assertEquals("42", context?.runId)
        assertEquals("main", context?.branch)
        assertEquals("https://ci.example.com/run/42", context?.buildUrl)
    }
}
