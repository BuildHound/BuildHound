package dev.buildhound.commons.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GenericCiEnvironmentProviderTest {

    private val provider = GenericCiEnvironmentProvider()

    @Test
    fun does_not_detect_without_buildhound_markers() {
        assertNull(provider.detect(mapOf("CI" to "true", "HOME" to "/home/agent")))
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
