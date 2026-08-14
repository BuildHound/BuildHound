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
        data class Skip(val reason: String, val cause: Cause) : Decision
    }

    /**
     * Why a payload may not leave the machine. Callers branch on this rather than on [Decision.Skip]'s
     * `reason`, which is user-facing log text and gets reworded — a caller matching on the wording
     * would silently change behaviour the next time someone improves a message (plan 110).
     *
     * The distinction that matters to callers is *configuration gap* versus *standing decision*:
     * [NO_SERVER] and [SERVER_URL_NOT_HTTP] describe a machine that could not publish and may be able
     * to later; the rest describe a choice not to.
     */
    enum class Cause {
        /** The master switch is off. */
        TELEMETRY_DISABLED,

        /** No server URL configured — the plugin's default, i.e. offline. */
        NO_SERVER,

        /** A URL is set but is not http(s). */
        SERVER_URL_NOT_HTTP,

        /** `localBuilds.enabled = false`: local builds deliberately do not publish. */
        LOCAL_UPLOADS_DISABLED,

        /** Consent gate (spec §3.7): no `~/.buildhound/optin` marker for a local build. */
        LOCAL_OPT_IN_MISSING,
        ;

        /**
         * True when publication failed for want of a usable server rather than by choice — the
         * caller may retry the same payload on a later build. Never true for a consent or
         * standing-choice skip: re-offering those would publish something the user declined.
         */
        val isServerConfigGap: Boolean
            get() = this == NO_SERVER || this == SERVER_URL_NOT_HTTP
    }

    fun decide(
        enabled: Boolean,
        serverUrl: String?,
        mode: BuildMode,
        localBuildsEnabled: Boolean,
        requireOptInFile: Boolean,
        optInFileExists: Boolean,
    ): Decision {
        if (!enabled) return Decision.Skip("telemetry disabled", Cause.TELEMETRY_DISABLED)
        val url = serverUrl?.trim()?.trimEnd('/')
        if (url.isNullOrEmpty()) return Decision.Skip("no server configured", Cause.NO_SERVER)
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            return Decision.Skip("server url is not http(s)", Cause.SERVER_URL_NOT_HTTP)
        }
        return when (mode) {
            BuildMode.CI, BuildMode.BENCHMARK -> Decision.Upload(url)
            BuildMode.LOCAL -> when {
                !localBuildsEnabled ->
                    Decision.Skip("local uploads disabled", Cause.LOCAL_UPLOADS_DISABLED)
                requireOptInFile && !optInFileExists ->
                    Decision.Skip(
                        "local opt-in marker missing (~/.buildhound/optin)",
                        Cause.LOCAL_OPT_IN_MISSING,
                    )
                else -> Decision.Upload(url)
            }
        }
    }
}
