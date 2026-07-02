package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildMode

/**
 * The one place deciding whether a payload may leave the machine (spec §3.4, §3.7).
 * Pure and Gradle-free for plain unit tests. Payload/artifact writing is independent
 * of this decision.
 */
internal object UploadGate {

    sealed interface Decision {
        data class Upload(val url: String) : Decision
        data class Skip(val reason: String) : Decision
    }

    fun decide(
        enabled: Boolean,
        serverUrl: String?,
        mode: BuildMode,
        localBuildsEnabled: Boolean,
        requireOptInFile: Boolean,
        optInFileExists: Boolean,
    ): Decision {
        if (!enabled) return Decision.Skip("telemetry disabled")
        val url = serverUrl?.trim()?.trimEnd('/')
        if (url.isNullOrEmpty()) return Decision.Skip("no server configured")
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            return Decision.Skip("server url is not http(s)")
        }
        return when (mode) {
            BuildMode.CI, BuildMode.BENCHMARK -> Decision.Upload(url)
            BuildMode.LOCAL -> when {
                !localBuildsEnabled -> Decision.Skip("local uploads disabled")
                requireOptInFile && !optInFileExists ->
                    Decision.Skip("local opt-in marker missing (~/.buildhound/optin)")
                else -> Decision.Upload(url)
            }
        }
    }
}
