package dev.buildhound.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CiAssetsContractTest {
    @Test
    fun `GitHub action opts into the basic cache provider without inlining secrets`() {
        val action = File("../buildhound-ci-assets/github/action.yml").readText()

        assertTrue(action.contains("uses: gradle/actions/setup-gradle@3f131e8634966bd73d06cc69884922b02e6faf92 # v6"))
        assertTrue(action.contains("cache-provider: ${'$'}{{ inputs.cache-provider }}"))
        assertTrue(Regex("cache-provider:[\\s\\S]*?default: \\\"basic\\\"").containsMatchIn(action))
        assertFalse(Regex("(?i)(token|secret):\\s*['\\\"][A-Za-z0-9_-]{16,}").containsMatchIn(action))
    }
}
