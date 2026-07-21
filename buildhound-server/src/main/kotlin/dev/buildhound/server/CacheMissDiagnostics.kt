package dev.buildhound.server

import kotlinx.serialization.Serializable

/**
 * Server-local mirror of plan 038's `CacheOrigin` (never the real Gradle-API type —
 * [RelocatabilityDetector] never depends on `buildhound-internal-adapters`, mirroring the
 * [RerunCause]/[FlakySignal]/[WarningCategory] server-local-enum convention), parsed once at the
 * [relocatabilityRowsOf] boundary so every downstream comparison is exhaustive-`when`-checked instead of
 * a raw wire-string literal. [OTHER] absorbs any origin name the server doesn't recognize — a future
 * wire value, or a malformed one — degrading to "not a hit, not a countable miss" rather than a crash.
 */
enum class RelocatabilityOrigin { REMOTE_HIT, STORED, MISS, OTHER }

/**
 * `"Miss"` means non-`REMOTE_HIT` **execution** (`STORED`|`MISS`), never the literal `MISS` case alone
 * (see [RelocatabilityDetector.detect]'s KDoc). Exhaustive `when` (no `else`) so a future addition to
 * [RelocatabilityOrigin] fails to compile here instead of silently defaulting.
 */
private val RelocatabilityOrigin.isNonHitExecution: Boolean
    get() = when (this) {
        RelocatabilityOrigin.STORED, RelocatabilityOrigin.MISS -> true
        RelocatabilityOrigin.REMOTE_HIT, RelocatabilityOrigin.OTHER -> false
    }

/**
 * One fleet row for detector 1 (plan 068, research F18): a task's internal-adapters origin, parsed to
 * [RelocatabilityOrigin] at the [relocatabilityRowsOf] boundary, joined by path to the core task's
 * module/cacheable/duration. [hostnameHash] is the build-level identity hash (§3.7); null when uncaptured
 * (`strict` mode, or a pre-existing payload) — such a row can never contribute to the ≥2-distinct-host
 * count or [NonRelocatableCandidate.wastedMs] (evidence-set consistency, see its KDoc), but still counts
 * toward the task's REMOTE_HIT-anywhere check ([RelocatabilityDetector.remoteCacheObserved]/the per-task
 * relocated-fine skip).
 */
data class RelocatabilityRow(
    val taskPath: String,
    val module: String?,
    val hostnameHash: String?,
    val origin: RelocatabilityOrigin,
    val cacheable: Boolean?,
    val durationMs: Long,
)

/**
 * One ranked non-relocatable-task candidate (plan 068). **Never a confirmed fix** — `PathSensitivity`
 * normalization is observable via no public/internal API, so [note] always reads as an investigation
 * prompt, never "change annotation X" (the plan's load-bearing candidate-not-fix framing, mirroring
 * [WarningRow]/[BuildLogicStormCandidate]). [crossHostCount] is the number of distinct machines the
 * task executed (`STORED`|`MISS`) on with zero `REMOTE_HIT`s anywhere for that task; [wastedMs] is the
 * summed duration of those *same* hosted non-hit executions — a row with no captured `hostnameHash`
 * (uncaptured/`strict` mode) contributes to neither, so the two numbers describe one consistent evidence
 * set rather than [wastedMs] silently including executions [crossHostCount] never counted. Together the
 * ranking key (`crossHostCount * wastedMs`).
 */
@Serializable
data class NonRelocatableCandidate(
    val taskPath: String,
    val module: String? = null,
    val crossHostCount: Int,
    val wastedMs: Long,
    val note: String,
)

/**
 * Pure non-relocatability detection (plan 068, research F18), sibling of [FlakyDetector]/
 * `BottleneckCalculator`. **Self-gating on the origin enum**: the core-only path is impossible (core
 * `FROM_CACHE` is undifferentiated local/remote and per-machine salting makes cross-host fingerprint
 * matching unobservable), so [detect] stays silent — never a false "relocatable" verdict — whenever the
 * window carries zero `REMOTE_HIT` anywhere ([remoteCacheObserved]).
 */
object RelocatabilityDetector {

    /** A task needs to have executed (non-`REMOTE_HIT`) on at least this many distinct hosts to qualify. */
    const val MIN_HOSTS: Int = 2

    /**
     * Defensive ceiling on builds scanned for one `/rollups/cache-miss-diagnostics` query (plan 068 §6
     * "cap rows"), mirroring [FlakyDetector.MAX_OUTCOME_ROWS]. Both stores fetch the same
     * most-recent-first, `(startedAt, buildId)`-tie-broken set up to this bound, so below the cap the
     * two stores see an identical set (parity holds); only in the pathological over-cap regime does
     * truncation bound memory/IO.
     */
    const val MAX_DIAGNOSTIC_ROWS: Int = 20_000

    /** True when the window observed at least one `REMOTE_HIT` anywhere — the fleet gate for [detect]. */
    fun remoteCacheObserved(rows: List<RelocatabilityRow>): Boolean =
        rows.any { it.origin == RelocatabilityOrigin.REMOTE_HIT }

    /**
     * `"Miss"` means non-`REMOTE_HIT` **execution** (`STORED`|`MISS`), not the literal `MISS` enum: a
     * cold cacheable task normally emits `STORED` (executed-and-stored) per plan 038's
     * `OriginClassifier`, so keying on literal `MISS` alone would find almost nothing. A candidate is a
     * task that executed non-`REMOTE_HIT` on ≥[MIN_HOSTS] distinct hosts while never once `REMOTE_HIT`ing
     * anywhere in the window. `cacheable != false` (true **or** null/unknown — the plan-016/isolated-
     * projects gap) admits the row; an *explicitly* non-cacheable task (`cacheable == false`) is excluded
     * — investigating "relocatability" makes no sense for a task that was never eligible for the cache at
     * all, and `OriginClassifier` can legitimately emit `MISS` for a plain executed-and-uncached task too.
     */
    fun detect(rows: List<RelocatabilityRow>): List<NonRelocatableCandidate> {
        if (!remoteCacheObserved(rows)) return emptyList()

        return rows.groupBy { it.taskPath }
            .mapNotNull { (path, group) ->
                if (group.any { it.origin == RelocatabilityOrigin.REMOTE_HIT })
                    return@mapNotNull null // relocated fine somewhere
                val executed = group.filter { it.origin.isNonHitExecution && it.cacheable != false }
                // wastedMs sums the same hosted subset crossHostCount counts (evidence-set consistency,
                // never a row whose host is uncaptured) — see NonRelocatableCandidate's KDoc.
                val hostedExecuted = executed.filter { it.hostnameHash != null }
                val hosts = hostedExecuted.mapNotNull { it.hostnameHash }.distinct()
                if (hosts.size < MIN_HOSTS) return@mapNotNull null
                NonRelocatableCandidate(
                    taskPath = path,
                    module = group.mapNotNull { it.module }.distinct().singleOrNull(),
                    crossHostCount = hosts.size,
                    wastedMs = hostedExecuted.sumOf { it.durationMs },
                    note = "Executed (never REMOTE_HIT) on ${hosts.size} distinct machines — investigate " +
                        "cache-key relocatability (e.g. absolute-path or output-location sensitivity) for this task.",
                )
            }
            .sortedWith(
                compareByDescending<NonRelocatableCandidate> {
                        it.crossHostCount.toLong() * it.wastedMs
                    }
                    .thenBy { it.taskPath }
            )
    }
}

/**
 * One build's fingerprint snapshot within a single-host salt stream (plan 068, plan 022). [hostnameHash]
 * IS the stream key — `IdentityHashing.hostnameHash` HMACs the hostname with the same per-project salt
 * the fingerprints use (plan 022 divergence note), so under `pseudonymize=true` the salt within one
 * group is provably constant; a salt regeneration mints a brand-new `hostnameHash`, never mixing into an
 * existing stream.
 */
data class FingerprintStreamRow(
    val hostnameHash: String,
    val startedAt: Long,
    val buildId: String,
    val fingerprints: Map<String, String>,
)

/**
 * One fingerprint key's volatility over the window (plan 068). [volatility] is the **max** across every
 * contributing salt stream (never pooled — see [FingerprintVolatilityDetector]); [contributingStreams]
 * counts how many single-host streams had ≥2 builds and observed this key at all. [note] is a label,
 * never the matched value (spec §3.7 — only the allowlisted key *name* is pattern-matched).
 */
@Serializable
data class VolatileInput(
    val key: String,
    val volatility: Double,
    val contributingStreams: Int,
    val note: String,
)

/**
 * Pure fingerprint-volatility detection (plan 068, research F18), sibling of [RelocatabilityDetector].
 * Groups rows by [FingerprintStreamRow.hostnameHash] — an **exact** partition, not a proxy (see the
 * class KDoc) — orders each group by `startedAt`, and scores each key by the fraction of
 * consecutive-build transitions whose salted hash changed. A stream needs ≥[MIN_STREAM] builds to
 * contribute at all (a [MIN_STREAM] gate mirroring `BottleneckCalculator.MIN_SAMPLES`); a key's reported
 * [VolatileInput.volatility] is the max over every stream that observed it, and streams are **never**
 * pooled (differing per-stream salts would inflate volatility into a false-cross-host-change signal).
 */
object FingerprintVolatilityDetector {

    /** A salt stream (one `hostnameHash` group) needs at least this many builds to have a transition. */
    const val MIN_STREAM: Int = 2

    fun detect(rows: List<FingerprintStreamRow>): List<VolatileInput> {
        val perKeyStreamVolatilities = LinkedHashMap<String, MutableList<Double>>()

        rows.groupBy { it.hostnameHash }
            .values
            .filter { it.size >= MIN_STREAM }
            .forEach { unordered ->
                // (startedAt, buildId) tie-break: both stores must agree even when two builds on
                // the
                // same host share a millisecond timestamp (the plan-026/032 determinism
                // discipline).
                val stream =
                    unordered.sortedWith(
                        compareBy<FingerprintStreamRow> { it.startedAt }.thenBy { it.buildId }
                    )
                val keys = stream.flatMap { it.fingerprints.keys }.toSet()
                if (keys.isEmpty()) return@forEach
                val transitions = stream.size - 1
                for (key in keys) {
                    var changed = 0
                    for (i in 0 until transitions) {
                        if (stream[i].fingerprints[key] != stream[i + 1].fingerprints[key]) changed++
                    }
                    perKeyStreamVolatilities
                        .getOrPut(key) { mutableListOf() }
                        .add(roundTo6(changed.toDouble() / transitions))
                }
            }

        return perKeyStreamVolatilities.map { (key, volatilities) ->
            VolatileInput(
                key = key,
                volatility = volatilities.max(),
                contributingStreams = volatilities.size,
                note = CredentialPatterns.noteFor(key),
            )
        }.sortedWith(compareByDescending<VolatileInput> { it.volatility }.thenBy { it.key })
    }

    private fun roundTo6(value: Double): Double = Math.round(value * SIX_DECIMAL_FACTOR) / SIX_DECIMAL_FACTOR
}

/**
 * Small name-pattern table for a volatile fingerprint key's explanatory note (plan 068). Matches only
 * the allowlisted **key name** — never a value, which stays a salted hash (spec §3.7's name-pattern
 * privacy bound). Strips the plan-022 `env-`/`sysProps-`/`gradleProp-` prefix before matching, so
 * `env-GITHUB_TOKEN` is judged on `GITHUB_TOKEN`. Falls back to cross-referencing
 * [BuildComparator.explanatoryNote]'s exact-name catalog (a built-in key like `jdk.home` carries no
 * prefix) before a generic catch-all note.
 */
private object CredentialPatterns {
    private val PREFIXES = listOf("env-", "sysProps-", "gradleProp-")

    /** e.g. `GITHUB_TOKEN`, `NPM_AUTH_TOKEN`, `API_KEY`, `DB_SECRET`. */
    private val CREDENTIAL = Regex(".*_(KEY|TOKEN|SECRET)$", RegexOption.IGNORE_CASE)

    /** e.g. `GITHUB_RUN_ID`, `BUILD_NUMBER`, `CI_JOB_ID`. */
    private val CI_RUN_ID = Regex(".*_(RUN_ID|BUILD_ID|BUILD_NUMBER|JOB_ID)$", RegexOption.IGNORE_CASE)

    /** e.g. `BUILD_TIMESTAMP`, `START_TS`, `DEPLOY_EPOCH`. */
    private val TIMESTAMP = Regex(".*(TIMESTAMP|_TS|_EPOCH)$", RegexOption.IGNORE_CASE)

    fun noteFor(key: String): String {
        val bareName = PREFIXES.firstOrNull { key.startsWith(it) }?.let { key.removePrefix(it) } ?: key
        return when {
            CREDENTIAL.matches(bareName) ->
                "Key name looks credential-shaped (…KEY/…TOKEN/…SECRET) — a secret that rotates every " +
                    "build changes by design; this is expected volatility, not a caching bug."
            CI_RUN_ID.matches(bareName) ->
                "Key name looks like a CI run/build identifier — expected to change on every build, not a caching bug."
            TIMESTAMP.matches(bareName) ->
                "Key name looks like a timestamp — expected to change on every build, not a caching bug."
            else -> BuildComparator.explanatoryNote(key)
                ?: "Key changes on nearly every build on this machine — investigate whether this input " +
                    "belongs in the cache key or should be excluded from it."
        }
    }
}

/**
 * `GET /v1/rollups/cache-miss-diagnostics` response (plan 068, research F18): both signals in one
 * envelope since they're two facets of the same "why did this build miss cache" question, and both
 * stores compute both from the same windowed builds in one query. [remoteCacheObserved] is `false`
 * exactly when [RelocatabilityDetector.remoteCacheObserved] found no `REMOTE_HIT` anywhere — an honest
 * empty state (not "no non-relocatable tasks found"), since detector 1 cannot run at all without it.
 */
@Serializable
data class CacheMissDiagnostics(
    val remoteCacheObserved: Boolean,
    val nonRelocatable: List<NonRelocatableCandidate>,
    val volatileInputs: List<VolatileInput>,
)
