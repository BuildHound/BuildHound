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
        /**
         * @property retryWhenServerConfigured true only when a server-configuration gap was the
         *   **sole** blocker, so the same payload would upload unchanged once a server exists. False
         *   whenever any consent or standing-choice rule would also have refused it — see
         *   [decide]'s note on reporting the first blocker.
         */
        data class Skip(
            val reason: String,
            val cause: Cause,
            val retryWhenServerConfigured: Boolean = false,
        ) : Decision
    }

    /**
     * Why a payload may not leave the machine. Callers branch on this rather than on [Decision.Skip]'s
     * `reason`, which is user-facing log text and gets reworded — a caller matching on the wording
     * would silently change behaviour the next time someone improves a message (plan 110).
     *
     * [NO_SERVER] and [SERVER_URL_NOT_HTTP] describe a machine that could not publish; the rest
     * describe a choice not to. Note this is the *reported* cause and not on its own a licence to
     * retry — see [Decision.Skip.retryWhenServerConfigured], which is what plan 110's marker
     * retention keys on.
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
        // The mode rules are evaluated even when the server check is about to fail. `decide` reports
        // the FIRST blocker, and the server check comes first because a missing server is the more
        // useful thing to tell an offline user — but "which blocker do we name" must not decide
        // "may this payload be retried later" (plan 110). Offline is the plugin's DEFAULT, so a
        // LOCAL build with no opt-in is blocked by the missing server *and* by consent; treating
        // that as a mere configuration gap kept its marker, and a developer who later added a server
        // and opted in published builds recorded before they ever consented.
        val modeBlock = modeSkip(mode, localBuildsEnabled, requireOptInFile, optInFileExists)
        val url = serverUrl?.trim()?.trimEnd('/')
        if (url.isNullOrEmpty()) {
            return Decision.Skip(
                "no server configured",
                Cause.NO_SERVER,
                retryWhenServerConfigured = modeBlock == null,
            )
        }
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            return Decision.Skip(
                "server url is not http(s)",
                Cause.SERVER_URL_NOT_HTTP,
                retryWhenServerConfigured = modeBlock == null,
            )
        }
        return modeBlock ?: Decision.Upload(url)
    }

    /**
     * The mode/consent half of the decision, independent of whether a server is configured, so it can
     * be consulted both as the answer and as "would this have been blocked anyway?". Null means this
     * build's own configuration permits publication.
     */
    private fun modeSkip(
        mode: BuildMode,
        localBuildsEnabled: Boolean,
        requireOptInFile: Boolean,
        optInFileExists: Boolean,
    ): Decision.Skip? = when (mode) {
        BuildMode.CI, BuildMode.BENCHMARK -> null
        BuildMode.LOCAL -> when {
            !localBuildsEnabled ->
                Decision.Skip("local uploads disabled", Cause.LOCAL_UPLOADS_DISABLED)
            requireOptInFile && !optInFileExists ->
                Decision.Skip(
                    "local opt-in marker missing (~/.buildhound/optin)",
                    Cause.LOCAL_OPT_IN_MISSING,
                )
            else -> null
        }
    }
}
