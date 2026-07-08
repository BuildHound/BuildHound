package dev.buildhound.server

import kotlinx.serialization.Serializable

/**
 * Fixed rerun-cause taxonomy (plan 061, research F11) [RerunCauseClassifier] buckets every Gradle
 * `executionReasons` string into. Not `@Serializable` on purpose — mirrors the existing server-local
 * enum convention ([FlakySignal], [dev.buildhound.server.CohortStatus]): the wire model
 * ([RerunCauseBucketRow.cause]) carries the plain `.name` string, not the enum type itself.
 * [UNCLASSIFIED] is a first-class, never-silently-dropped bucket: both an unrecognized (version-drifted)
 * reason string and a `PayloadCapper`-truncated (emptied) reason list land here.
 */
enum class RerunCause { SOURCE, IMPL_CLASSPATH, UPSTREAM_OUTPUT, OUTPUT_MISSING, CACHING_DISABLED, FORCED, UNCLASSIFIED }

/**
 * Version-tolerant substring classification of one Gradle task's `executionReasons` entry (plan 061,
 * research F11). Gradle's reason strings are human-readable diagnostic output, **not an API contract**
 * (F11's narrowing) — patterns are pinned against real Gradle 9.6.1 message templates rather than
 * guessed: the string constants were verified directly against the `gradle-execution`/`gradle-core`
 * jars from the project's own `9.6.1` wrapper distribution (`InputValueChanges`, `ImplementationChanges`,
 * `PropertyChanges`/`DefaultFileChange`, `NeverUpToDateStep`, `DefaultTaskExecutionMode` — see
 * `RerunCauseClassifierTest` for the exact strings and which are golden-fixture-confirmed vs.
 * template-reconstructed). Matching stays case-insensitive substrings with an explicit fallback so a
 * future Gradle rewording only inflates [RerunCause.UNCLASSIFIED] — it never throws and never
 * misattributes to a bucket it didn't earn.
 *
 * Match order matters: the more specific buckets (FORCED, CACHING_DISABLED, IMPL_CLASSPATH) are checked
 * before the broader OUTPUT_MISSING / UPSTREAM_OUTPUT / SOURCE catch-alls, so a message that happens to
 * mention more than one keyword never lands in the wrong (earlier-alphabetical or more-generic) bucket.
 */
object RerunCauseClassifier {

    fun classify(reason: String): RerunCause {
        val r = reason.lowercase()
        return when {
            r.isBlank() -> RerunCause.UNCLASSIFIED

            // "Executed with '--rerun-tasks'." / "Task.upToDateWhen is false." — both verified exact
            // strings from DefaultTaskExecutionMode (a task explicitly forced to always rerun).
            "rerun-tasks" in r || "rerun tasks" in r || "uptodatewhen is false" in r || "forced" in r ->
                RerunCause.FORCED

            // "Build cache is disabled" (verified exact, AbstractResolveCachingStateStep) / "Caching
            // disabled for … because:" (verified template, CachingDisabledReasonCategory) / "Caching has
            // been disabled to ensure correctness…" (verified exact). NOTE: these strings come from
            // Gradle's *cacheability* messaging, not confirmed observed inside `executionReasons` itself
            // (unlike the other six buckets, whose source classes directly feed `TaskExecutionResult`) —
            // implemented per plan, flagged here so the gap is on record rather than silently assumed.
            "build cache is disabled" in r || ("caching" in r && ("disabled" in r || "not been enabled" in r)) ->
                RerunCause.CACHING_DISABLED

            // "Class path of … has changed from … to …" / "The type of … has changed from '…' to '…'." /
            // "One or more additional actions for … have changed." — all three verified exact templates
            // from ImplementationChanges (buildSrc/build-logic classpath or task-class changes).
            "classpath" in r || "class path" in r || "has changed from" in r || "additional action" in r || "implementation" in r ->
                RerunCause.IMPL_CLASSPATH

            // "No history is available." — verified exact string, NeverUpToDateStep: never recorded at
            // all, distinct from a specific tracked output vanishing (UPSTREAM_OUTPUT below).
            "no history" in r || ("output" in r && "does not exist" in r) ->
                RerunCause.OUTPUT_MISSING

            // "Output property 'X' file … has changed."/"has been removed." — verified template
            // (PropertyChanges + DefaultFileChange/ChangeTypeInternal); the golden fixture's "Output
            // property 'binaryResultsDirectory' file has been removed." is this exact shape.
            "output" in r && ("changed" in r || "removed" in r || "no longer" in r) ->
                RerunCause.UPSTREAM_OUTPUT

            // "Value of input property 'X' has changed…" (golden-fixture-confirmed real string) /
            // "Input property 'X' file … has changed/removed." (verified template, same family).
            "input" in r && ("changed" in r || "removed" in r) ->
                RerunCause.SOURCE

            else -> RerunCause.UNCLASSIFIED
        }
    }
}

/**
 * One bucket's coverage of the window's executed task-hours (plan 061). **Shares overlap and do not
 * sum to 100%** — `executionReasons` is a list per task, and a task's reasons are deduped to a bucket
 * *set* (order-invariant), so one task can contribute its full duration to more than one row here. This
 * is "task-hours touched by [cause]," never a partition — label it that way at every call site.
 */
@Serializable
data class RerunCauseBucketRow(val cause: String, val taskCount: Int, val durationMs: Long, val sharePct: Double)

/**
 * The build-logic-invalidation-storm candidate (plan 061, research F11, detector 1): surfaced only
 * when [RerunCause.IMPL_CLASSPATH]'s fleet-wide coverage share crosses
 * [RerunCauseRollupCalculator.BUILD_LOGIC_STORM_SHARE_THRESHOLD] — a **ranked candidate to
 * investigate**, never a confirmed fix (the same honesty framing [CohortComparator] uses for its own
 * distinguishable-vs-causal-claim distinction).
 */
@Serializable
data class BuildLogicStormCandidate(val sharePct: Double, val message: String)

/**
 * `GET /v1/rollups/rerun-causes` response (plan 061, research F11): per-bucket coverage of executed
 * task-hours (overlapping, see [RerunCauseBucketRow]) + a build-level cascade rate (detector 2) + an
 * optional build-logic-storm candidate (detector 1). [unclassifiedSharePct] duplicates the
 * `UNCLASSIFIED` row already present in [buckets] — deliberately: it is called out as its own top-level
 * field (not just "the last row in a list") so a UI/consumer can surface the "data opaque here" honesty
 * signal without hunting through the bucket array (plan §"Risks" — PayloadCapper truncation). [cascadeRate]
 * is null when the window has no classifiable build (a build needs at least one EXECUTED task with
 * nonzero duration to be scored CASCADE/CONTAINED).
 */
@Serializable
data class RerunCauseRollup(
    val buckets: List<RerunCauseBucketRow>,
    val unclassifiedSharePct: Double,
    val executedTaskCount: Int,
    val cascadeRate: Double? = null,
    val cascadeBuildCount: Int = 0,
    val containedBuildCount: Int = 0,
    val buildLogicStormCandidate: BuildLogicStormCandidate? = null,
)

/**
 * Pure rerun-cause math (plan 061), the single source both stores defer to (the plan-026/032 parity
 * discipline: in-memory flattens payloads via `taskRowsOf`, Postgres reads the `execution_reasons`
 * column via `taskRowsBetween`, both fold the resulting [TaskRow]s through this). Bucket membership is
 * per-task and order-invariant (a `Set<RerunCause>` per task), so in-memory and Postgres agree
 * byte-for-byte regardless of row-arrival order.
 *
 * Benchmark builds are excluded by both stores **before** rows reach [compute] (the `bottlenecks`/
 * `toolchainAdoption`/`tags` fleet-view convention — not the `projectCost`/`taskDuration`/
 * `negativeAvoidance` convention, which does *not* exclude them): a rerun-cause signal is about
 * real-build rework, and repeated same-scenario benchmark reruns would otherwise skew the fleet share.
 */
object RerunCauseRollupCalculator {

    /**
     * Detector 1 threshold (research F11): IMPL_CLASSPATH's fleet-wide coverage share above this
     * surfaces the build-logic-invalidation-storm candidate. A material-but-not-majority fraction is
     * enough to flag for investigation — this is a ranked candidate, not a confirmed verdict.
     */
    const val BUILD_LOGIC_STORM_SHARE_THRESHOLD: Double = 0.30

    /**
     * Detector 2 threshold (research F11): a build is CASCADE when the *majority* of its executed
     * task-hours touch IMPL_CLASSPATH or UPSTREAM_OUTPUT — most of this build's rework was chasing an
     * upstream/build-logic invalidation, not first-party source changes.
     */
    const val CASCADE_SHARE_THRESHOLD: Double = 0.50

    private val CASCADE_CAUSES = setOf(RerunCause.IMPL_CLASSPATH, RerunCause.UPSTREAM_OUTPUT)

    fun compute(rows: List<TaskRow>): RerunCauseRollup {
        // Only EXECUTED work carries a meaningful rerun cause (mirrors RollupCalculator/
        // BottleneckCalculator's "EXECUTED work only" convention for regression/avoidance math).
        val executed = rows.filter { it.outcome == "EXECUTED" }
        if (executed.isEmpty()) {
            return RerunCauseRollup(buckets = emptyList(), unclassifiedSharePct = 0.0, executedTaskCount = 0)
        }

        val totalMs = executed.sumOf { it.durationMs }
        // Per-task bucket SET (deduped, order-invariant): an empty reason list — genuinely no reasons,
        // or every reason shed by PayloadCapper under byte pressure, or a pre-V12 NULL column read back
        // as empty — reads as UNCLASSIFIED, never a silently-dropped task (plan 061 honesty rule).
        val bucketsByTask: List<Set<RerunCause>> = executed.map { task ->
            if (task.executionReasons.isEmpty()) {
                setOf(RerunCause.UNCLASSIFIED)
            } else {
                task.executionReasons.map { RerunCauseClassifier.classify(it) }.toSet()
            }
        }

        val buckets = RerunCause.entries.mapNotNull { bucket ->
            var count = 0
            var durationSum = 0L
            for (i in executed.indices) {
                if (bucket in bucketsByTask[i]) {
                    count++
                    durationSum += executed[i].durationMs
                }
            }
            if (count == 0) return@mapNotNull null
            RerunCauseBucketRow(
                cause = bucket.name,
                taskCount = count,
                durationMs = durationSum,
                sharePct = roundTo6(durationSum.toDouble() / totalMs),
            )
        }.sortedWith(compareByDescending<RerunCauseBucketRow> { it.durationMs }.thenBy { it.cause })

        val unclassifiedSharePct = buckets.firstOrNull { it.cause == RerunCause.UNCLASSIFIED.name }?.sharePct ?: 0.0

        // Detector 2: per-build cascade-vs-contained, over each build's own executed tasks only.
        var cascadeBuilds = 0
        var containedBuilds = 0
        executed.indices.groupBy { executed[it].buildId }.forEach { (_, indices) ->
            val buildTotalMs = indices.sumOf { executed[it].durationMs }
            if (buildTotalMs <= 0L) return@forEach // nothing to score — never a divide-by-zero
            val cascadeMs = indices.filter { i -> bucketsByTask[i].any { it in CASCADE_CAUSES } }.sumOf { executed[it].durationMs }
            if (cascadeMs.toDouble() / buildTotalMs > CASCADE_SHARE_THRESHOLD) cascadeBuilds++ else containedBuilds++
        }
        val classifiableBuilds = cascadeBuilds + containedBuilds
        val cascadeRate = if (classifiableBuilds == 0) null else roundTo6(cascadeBuilds.toDouble() / classifiableBuilds)

        // Detector 1: fleet-wide IMPL_CLASSPATH share above the threshold is a ranked candidate.
        val implClasspathShare = buckets.firstOrNull { it.cause == RerunCause.IMPL_CLASSPATH.name }?.sharePct ?: 0.0
        val storm = if (implClasspathShare > BUILD_LOGIC_STORM_SHARE_THRESHOLD) {
            BuildLogicStormCandidate(
                sharePct = implClasspathShare,
                message = "${String.format(java.util.Locale.ROOT, "%.1f", implClasspathShare * 100)}% of executed " +
                    "task-hours were classpath/impl rebuilds — consider migrating buildSrc to an included " +
                    "`build-logic` build",
            )
        } else {
            null
        }

        return RerunCauseRollup(
            buckets = buckets,
            unclassifiedSharePct = unclassifiedSharePct,
            executedTaskCount = executed.size,
            cascadeRate = cascadeRate,
            cascadeBuildCount = cascadeBuilds,
            containedBuildCount = containedBuilds,
            buildLogicStormCandidate = storm,
        )
    }

    private fun roundTo6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0
}
