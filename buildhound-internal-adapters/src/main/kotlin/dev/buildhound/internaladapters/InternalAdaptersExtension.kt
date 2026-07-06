package dev.buildhound.internaladapters

import org.gradle.api.provider.Property

/**
 * `internalAdapters { }` DSL (plan 038). [perFileHashes] gates the per-input-file hash capture, which
 * explodes on large builds and risks absolute paths — **off by default** (research §5b), matching
 * Develocity's own gating history.
 *
 * [collectDeprecations] and [collectLogWarnings] are the two warning catchers (plan 044): each is an
 * explicit, independent opt-in, **off by default**, because both read free text that can carry paths or
 * secrets (scrubbed before it ships) and add per-build listener overhead. Turning warning collection on
 * is a two-step opt-in: apply this module, then flip the specific catcher(s) here.
 */
abstract class InternalAdaptersExtension {
    abstract val perFileHashes: Property<Boolean>

    /** Capture Gradle deprecation warnings (summary + advice, never the stack trace). Off by default. */
    abstract val collectDeprecations: Property<Boolean>

    /** Capture `WARN`-level log lines (`logger.warn`, some compiler output). Off by default. */
    abstract val collectLogWarnings: Property<Boolean>
}
