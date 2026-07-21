package dev.buildhound.gradle

import java.io.Serializable
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/** Serializable benchmark context for CC-safe transport into the FlowAction (plan 030). */
data class CollectedBenchmark(
    val scenario: String,
    val iteration: Int? = null,
    val isolationMode: String? = null,
    val seedRef: String? = null,
) : Serializable

/**
 * Env → [CollectedBenchmark] parsing (plan 030), Gradle-free so the allowlist/validation rules are
 * unit-testable off the Gradle classpath (injected log sinks, the
 * [ProcessProbeCollector]/`ConfigOverrides` pattern). `scenario`/`isolationMode` are validated
 * against fixed allowlists so a typo cannot mint a new series. No scenario ⇒ null (an ordinary
 * build). A *present* but malformed benchmark env fails closed (null + a `warn`) so the build falls
 * back to its normal mode rather than emitting a half-labeled benchmark.
 */
internal object BenchmarkActivation {

    /** The gradle-profiler scenarios (spec §7); the series-grouping + mode-deciding key. */
    val SCENARIOS = setOf("clean", "no_op", "incremental_non_abi", "cc_hit")

    /** Telltale cache-isolation labels our pipeline exports (docs recipe table). */
    val ISOLATION_MODES =
        setOf(
            "full_cache",
            "no_build_cache",
            "no_configuration_cache",
            "no_local_build_cache",
            "no_remote_build_cache",
            "no_gradle_home_cache",
            "no_kotlin_cache",
            "no_transforms_cache",
            "no_project_cache",
            "cold_daemon",
            "no_daemon",
            "no_wrapper_dists",
        )

    fun fromEnv(
        env: Map<String, String>,
        warn: (String) -> Unit = {},
        info: (String) -> Unit = {},
    ): CollectedBenchmark? {
        // No scenario ⇒ not a benchmark build (silent — the normal case).
        val scenario =
            env["BUILDHOUND_BENCHMARK_SCENARIO"]?.takeIf { it.isNotBlank() } ?: return null
        if (scenario !in SCENARIOS) {
            warn(
                "[buildhound] BUILDHOUND_BENCHMARK_SCENARIO='$scenario' is not a known scenario; " +
                    "benchmark mode not activated"
            )
            return null
        }
        val iterationRaw = env["BUILDHOUND_BENCHMARK_ITERATION"]?.takeIf { it.isNotBlank() }
        val iteration =
            if (iterationRaw == null) {
                null
            } else {
                iterationRaw.toIntOrNull()
                    ?: run {
                        warn(
                            "[buildhound] BUILDHOUND_BENCHMARK_ITERATION='$iterationRaw' is not an integer; " +
                                "benchmark mode not activated"
                        )
                        return null
                    }
            }
        val isolationRaw = env["BUILDHOUND_BENCHMARK_ISOLATION"]?.takeIf { it.isNotBlank() }
        val isolation =
            if (isolationRaw == null) {
                null
            } else if (isolationRaw in ISOLATION_MODES) {
                isolationRaw
            } else {
                warn(
                    "[buildhound] BUILDHOUND_BENCHMARK_ISOLATION='$isolationRaw' is not a known isolation mode; " +
                        "benchmark mode not activated"
                )
                return null
            }
        val seedRef = env["BUILDHOUND_BENCHMARK_SEED_REF"]?.takeIf { it.isNotBlank() }
        info(
            "[buildhound] benchmark mode active (scenario=$scenario, iteration=$iteration, isolation=$isolation)"
        )
        return CollectedBenchmark(
            scenario = scenario,
            iteration = iteration,
            isolationMode = isolation,
            seedRef = seedRef,
        )
    }
}

/**
 * Benchmark activation (plan 030, spec §7). A gradle-profiler pipeline runs the pilot's *real*
 * build once per scenario×iteration and exports
 * `BUILDHOUND_BENCHMARK_{SCENARIO,ITERATION,ISOLATION,SEED_REF}` instead of editing the pilot's
 * `buildhound {}` DSL per invocation. Env is read here at execution time — never in the
 * configuration model — so nothing becomes a CC fingerprint input (architecture §2 rule 9), same
 * discipline as [CiValueSource]. Parsing/validation lives in [BenchmarkActivation].
 */
abstract class BenchmarkValueSource :
    ValueSource<CollectedBenchmark, BenchmarkValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val enabled: Property<Boolean>
    }

    override fun obtain(): CollectedBenchmark? {
        if (!parameters.enabled.getOrElse(true)) return null
        return runCatching {
            BenchmarkActivation.fromEnv(
                System.getenv(),
                warn = { logger.warn(it) },
                info = { logger.info(it) },
            )
        }
            .getOrElse {
                logger.info(
                    "[buildhound] benchmark activation unavailable: {}",
                    it::class.java.simpleName,
                )
                null
            }
    }

    private companion object {
        val logger = Logging.getLogger(BenchmarkValueSource::class.java)
    }
}
