package dev.buildhound.commons.ci

/**
 * IDE + AI-agent detection (plan 027, §4.1). Pure over the JVM system-property + environment maps
 * so the plugin passes snapshots and the tests inject controlled maps. All matches are
 * positive-only and never a guess — a miss leaves the field null (an honest gap, not a wrong value).
 */
object EnvironmentDetection {

    data class IdeInfo(val ide: String?, val version: String?, val sync: Boolean?)

    /**
     * Host IDE from system properties/env. A null [IdeInfo.ide] *is* command line — the field
     * stays honest. Skipped by the caller when a CI context is present (an IDE never runs CI).
     */
    fun detectIde(sysProps: Map<String, String>, env: Map<String, String>): IdeInfo = when {
        sysProps["idea.vendor.name"] == "Google" ->
            IdeInfo("Android Studio", sysProps["android.studio.version"], ideSync(sysProps))
        sysProps["idea.vendor.name"] == "JetBrains" ->
            IdeInfo("IntelliJ IDEA", sysProps["idea.version"], ideSync(sysProps))
        sysProps["eclipse.buildId"] != null ->
            IdeInfo("Eclipse", sysProps["eclipse.buildId"], null)
        env["VSCODE_PID"] != null || env["VSCODE_INJECTION"] != null ->
            IdeInfo("VS Code", null, null)
        else -> IdeInfo(null, null, null)
    }

    private fun ideSync(sysProps: Map<String, String>): Boolean? =
        sysProps["idea.sync.active"]?.let { it.equals("true", ignoreCase = true) }

    /**
     * AI-agent attribution by ordered first-match over marker names. Robustness (daemonitor): a
     * marker present but empty/falsy is treated as **ambient**, not an active agent — the in-build
     * analogue of daemonitor's pre-launch snapshot (the plugin can't take one). Only `CLAUDECODE`
     * is a confirmed signal; the rest are best-effort, so a miss is silent, never wrong.
     */
    fun detectAgent(env: Map<String, String>, sysProps: Map<String, String>): String? {
        fun active(name: String): Boolean {
            val value = env[name] ?: sysProps[name] ?: return false
            return value.isNotEmpty() && !value.equals("false", ignoreCase = true) && value != "0"
        }
        return when {
            active("CLAUDECODE") -> "Claude Code"
            active("CODEX_SANDBOX_NETWORK_DISABLED") || active("CODEX_THREAD_ID") -> "Codex"
            active("CURSOR_AGENT") -> "Cursor"
            active("OPENCODE") -> "OpenCode"
            active("GEMINI_CLI") -> "Gemini CLI"
            active("android.studio.agent") || active("ANDROID_STUDIO_AGENT") -> "Gemini in Android Studio"
            else -> null
        }
    }
}
