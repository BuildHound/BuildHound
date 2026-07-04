package dev.buildhound.server

import kotlinx.serialization.Serializable

/**
 * Per-project data-retention windows (plan 042, spec §5). Raw per-build rows are kept [rawDays], the
 * build-level history [buildDays] (~13 months by default); daily aggregates are never purged by the
 * sweep. Server-owned (not a wire type) — the payload schema is untouched. Defaults apply when a
 * project has no row; the admin route rejects out-of-range windows rather than silently clamping.
 */
@Serializable
data class RetentionConfig(
    val rawDays: Int = DEFAULT_RAW_DAYS,
    val buildDays: Int = DEFAULT_BUILD_DAYS,
) {
    /** Null when valid; otherwise the reason (→ the admin route answers 400). */
    fun validationError(): String? = when {
        rawDays < MIN_DAYS || rawDays > MAX_DAYS -> "rawDays must be in [$MIN_DAYS, $MAX_DAYS]"
        buildDays < MIN_DAYS || buildDays > MAX_DAYS -> "buildDays must be in [$MIN_DAYS, $MAX_DAYS]"
        // Build-level history must outlive the raw rows it summarizes — a shorter window would purge
        // the build before its own task detail, leaving orphaned raw rows.
        buildDays < rawDays -> "buildDays must be >= rawDays"
        else -> null
    }

    companion object {
        const val DEFAULT_RAW_DAYS = 90     // spec §5 default
        const val DEFAULT_BUILD_DAYS = 395  // ~13 months
        const val MIN_DAYS = 1
        const val MAX_DAYS = 3650           // ~10 years — a sane upper bound, not "keep forever"

        val DEFAULT = RetentionConfig()
    }
}
