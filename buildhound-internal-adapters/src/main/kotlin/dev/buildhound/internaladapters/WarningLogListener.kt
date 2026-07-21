package dev.buildhound.internaladapters

import org.gradle.internal.logging.events.LogEvent
import org.gradle.internal.logging.events.OutputEvent
import org.gradle.internal.logging.events.OutputEventListener

/**
 * `WARN`-level log catcher (plan 044), opt-in via `internalAdapters.collectLogWarnings`. Registered once
 * per daemon on `LoggingOutputInternal`; each WARN [LogEvent] (`logger.warn`, and some compiler output)
 * is appended to the current build's accumulator — deduped + bounded there, scrubbed by the collector.
 *
 * Gated per build by the daemon-static toggle, so a build with the catcher off records nothing even
 * though the listener stays registered for the daemon's life. The whole [onOutput] body is
 * `runCatching`-guarded: a logging listener must never fail the build (spec §3.1).
 *
 * `LogEvent.getLevel()` returns the internal `LogEventLevel` enum (which lives in an oddly-scoped jar),
 * so the level is matched by `name()` via reflection — no compile dependency on that class, and a
 * rename degrades to no capture rather than a crash.
 */
class WarningLogListener : OutputEventListener {

    private val state = InternalAdaptersState

    override fun onOutput(event: OutputEvent) {
        if (state.collectLogWarnings()) {
            runCatching {
                val logEvent = event as? LogEvent
                val message = logEvent?.takeIf(::isWarn)?.message
                if (!message.isNullOrBlank()) {
                    state.accumulator().addLogWarning(message)
                }
            }
        }
    }

    private fun isWarn(event: LogEvent): Boolean = runCatching {
        val level = LogEvent::class.java.getMethod("getLevel").invoke(event) ?: return false
        level.javaClass.getMethod("name").invoke(level) == "WARN"
    }.getOrDefault(false)
}
