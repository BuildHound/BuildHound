package dev.buildhound.internaladapters

import org.gradle.api.provider.Property

/**
 * `internalAdapters { }` DSL (plan 038). [perFileHashes] gates the per-input-file hash capture, which
 * explodes on large builds and risks absolute paths — **off by default** (research §5b), matching
 * Develocity's own gating history.
 */
abstract class InternalAdaptersExtension {
    abstract val perFileHashes: Property<Boolean>
}
