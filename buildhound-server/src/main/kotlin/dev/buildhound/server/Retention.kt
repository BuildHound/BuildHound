package dev.buildhound.server

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

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

/** What a single [RetentionSweeper.sweep] deleted across all projects (plan 042). */
data class SweepSummary(val projects: Int, val builds: Long, val rawRows: Long)

/**
 * Retention enforcement (plan 042, spec §5). Per project: read its [RetentionConfig], compute the
 * build- and raw-row cutoffs from [nowMs], and purge past them. **Never throws** — a per-project
 * storage error is logged and the sweep moves on (the server-side analogue of the plugin's never-fail
 * rule), so one bad tenant can't stop retention for the rest or crash the server. Daily aggregates are
 * never purged here. The N-replica caveat (architecture §5: instance-local schedulers): run the sweep
 * on one instance, or guard it with an advisory lock — the single-instance pilot runs it unconditionally.
 */
class RetentionSweeper(
    private val builds: BuildStore,
    private val settings: SettingsStore,
) {
    private val logger = LoggerFactory.getLogger("dev.buildhound.server.Retention")

    fun sweep(nowMs: Long): SweepSummary {
        var projects = 0
        var totalBuilds = 0L
        var totalRaw = 0L
        val ids = runCatching { builds.allProjectIds() }.getOrElse {
            logger.warn("retention sweep: could not list projects: {}", it::class.java.simpleName)
            emptyList()
        }
        for (projectId in ids) {
            runCatching {
                val config = settings.retention(projectId)
                val buildCutoff = nowMs - config.buildDays.toLong() * DAY_MS
                val rawCutoff = nowMs - config.rawDays.toLong() * DAY_MS
                val purged = builds.purgeOlderThan(projectId, buildCutoff, rawCutoff)
                totalBuilds += purged.builds
                totalRaw += purged.rawRows
                projects++
            }.onFailure { logger.warn("retention sweep failed for a project: {}", it::class.java.simpleName) }
        }
        logger.info("retention sweep: {} projects, purged {} builds + {} raw rows", projects, totalBuilds, totalRaw)
        return SweepSummary(projects, totalBuilds, totalRaw)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * Starts the retention sweep on a daemon thread (plan 042), only from `main()` so `testApplication`
 * never spawns it. [sweepHours] `<= 0` disables it entirely (logged, distinguishable). The first run
 * is delayed one interval so boot isn't slowed. `nowMs` is read at each fire via `System.currentTimeMillis`.
 */
fun startRetentionSweeper(sweeper: RetentionSweeper, sweepHours: Long) {
    val logger = LoggerFactory.getLogger("dev.buildhound.server.Retention")
    if (sweepHours <= 0) {
        logger.info("retention sweep: disabled (BUILDHOUND_RETENTION_SWEEP_HOURS=0)")
        return
    }
    val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "buildhound-retention").apply { isDaemon = true }
    }
    executor.scheduleAtFixedRate(
        { runCatching { sweeper.sweep(System.currentTimeMillis()) } },
        sweepHours, sweepHours, TimeUnit.HOURS,
    )
    logger.info("retention sweep: every {}h", sweepHours)
}
