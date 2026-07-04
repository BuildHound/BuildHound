package dev.buildhound.commons.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnvironmentDetectionTest {

    @Test
    fun detects_android_studio_intellij_eclipse_and_vscode() {
        val studio = EnvironmentDetection.detectIde(
            mapOf("idea.vendor.name" to "Google", "android.studio.version" to "2024.1", "idea.sync.active" to "true"), emptyMap(),
        )
        assertEquals("Android Studio", studio.ide)
        assertEquals("2024.1", studio.version)
        assertEquals(true, studio.sync)

        val intellij = EnvironmentDetection.detectIde(mapOf("idea.vendor.name" to "JetBrains", "idea.version" to "2024.2"), emptyMap())
        assertEquals("IntelliJ IDEA", intellij.ide)
        assertEquals("2024.2", intellij.version)
        assertNull(intellij.sync, "no idea.sync.active → null, not false")

        assertEquals("Eclipse", EnvironmentDetection.detectIde(mapOf("eclipse.buildId" to "4.30"), emptyMap()).ide)
        assertEquals("VS Code", EnvironmentDetection.detectIde(emptyMap(), mapOf("VSCODE_PID" to "123")).ide)
    }

    @Test
    fun a_plain_command_line_build_has_no_ide() {
        val info = EnvironmentDetection.detectIde(emptyMap(), emptyMap())
        assertNull(info.ide)
        assertNull(info.version)
        assertNull(info.sync)
    }

    @Test
    fun detects_each_ai_agent_by_marker() {
        assertEquals("Claude Code", EnvironmentDetection.detectAgent(mapOf("CLAUDECODE" to "1"), emptyMap()))
        assertEquals("Codex", EnvironmentDetection.detectAgent(mapOf("CODEX_THREAD_ID" to "t"), emptyMap()))
        assertEquals("Cursor", EnvironmentDetection.detectAgent(mapOf("CURSOR_AGENT" to "1"), emptyMap()))
        assertEquals("OpenCode", EnvironmentDetection.detectAgent(mapOf("OPENCODE" to "1"), emptyMap()))
        assertEquals("Gemini CLI", EnvironmentDetection.detectAgent(mapOf("GEMINI_CLI" to "1"), emptyMap()))
        assertEquals("Gemini in Android Studio", EnvironmentDetection.detectAgent(emptyMap(), mapOf("android.studio.agent" to "1")))
        assertNull(EnvironmentDetection.detectAgent(emptyMap(), emptyMap()))
    }

    @Test
    fun an_ambient_empty_or_falsy_marker_is_not_an_active_agent() {
        assertNull(EnvironmentDetection.detectAgent(mapOf("CLAUDECODE" to ""), emptyMap()), "empty marker = ambient")
        assertNull(EnvironmentDetection.detectAgent(mapOf("CURSOR_AGENT" to "false"), emptyMap()))
        assertNull(EnvironmentDetection.detectAgent(mapOf("OPENCODE" to "0"), emptyMap()))
    }

    @Test
    fun agent_detection_is_ordered_first_match() {
        // Claude Code wins over a co-present Cursor marker (confirmed signal is first).
        assertEquals("Claude Code", EnvironmentDetection.detectAgent(mapOf("CLAUDECODE" to "1", "CURSOR_AGENT" to "1"), emptyMap()))
    }
}
