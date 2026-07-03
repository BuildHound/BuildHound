package dev.buildhound.gradle

import dev.buildhound.commons.payload.TestCaseDetail
import dev.buildhound.commons.payload.TestClassResult
import dev.buildhound.commons.payload.TestTaskResult
import dev.buildhound.commons.payload.TaskOutcome
import java.io.File

/**
 * Reads the JUnit XML each executed `Test` task wrote and assembles `List<TestTaskResult>` at
 * build finish (plan 024). All file IO is here, at execution time — no configuration-phase reads
 * (architecture §2 rule 9, parity with the KGP-report reader). Never throws: any failure degrades
 * to `emptyList` (absent over wrong), honoring the never-fail rule.
 *
 * Only tasks with a **this-build execution outcome** (EXECUTED/FAILED) are ingested: a
 * `FROM_CACHE`/`UP_TO_DATE` test task leaves prior-build XML on disk that must not be
 * re-attributed to this build (absent-over-wrong, plan 023 principle / §6 stale-output race).
 */
internal object TestResultCollector {

    private const val MAX_FILES_PER_TASK = 5000
    private const val MAX_FILE_BYTES = 10L * 1024 * 1024
    private const val MAX_CLASSES_PER_TASK = 2000
    private const val MAX_DETAIL_PER_TASK = 500

    fun collect(
        locations: Map<String, TestResultLocations>,
        taskOutcomes: Map<String, TaskOutcome>,
        warn: (String) -> Unit = {},
        failInjection: Boolean = false,
    ): List<TestTaskResult> = runCatching {
        check(!failInjection) { "test-collection failpoint" }
        if (locations.isEmpty()) return emptyList()
        locations.entries.mapNotNull { (taskPath, location) ->
            if (taskOutcomes[taskPath] !in EXECUTED_OUTCOMES) return@mapNotNull null
            collectTask(taskPath, location)
        }
    }.getOrElse {
        warn("[buildhound] test result collection failed (build unaffected): ${it::class.java.simpleName}")
        emptyList()
    }

    private fun collectTask(taskPath: String, location: TestResultLocations): TestTaskResult? {
        val dir = File(location.junitXmlDir)
        if (!dir.isDirectory) return null // task ran but wrote nothing observable — absent
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?.take(MAX_FILES_PER_TASK)
            .orEmpty()
        if (files.isEmpty()) return null

        val rollups = ArrayList<TestClassResult>()
        val allDetail = ArrayList<TestCaseDetail>()
        for (file in files) {
            if (file.length() > MAX_FILE_BYTES) continue
            for (parsed in JUnitXmlParser.parse(file.readBytes())) {
                rollups += parsed.rollup
                allDetail += parsed.detail
            }
        }
        if (rollups.isEmpty()) return null

        val mergedClasses = mergeByClassName(rollups)
        val rankedClasses = mergedClasses.sortedByDescending { it.durationMs }
        val keptClasses = rankedClasses.take(MAX_CLASSES_PER_TASK)
        val rankedDetail = allDetail.sortedByDescending { it.durationMs }
        val keptDetail = rankedDetail.take(MAX_DETAIL_PER_TASK)
        return TestTaskResult(
            taskPath = taskPath,
            module = location.module,
            // Sum of per-class suite times — an approximation of the task's wall clock (it ignores
            // parallelism and fixture overhead); the accurate wall clock lives in the TaskExecution
            // record. Per-class timings (used by sharding, plan 040) come straight from the suites.
            durationMs = mergedClasses.sumOf { it.durationMs },
            classes = keptClasses,
            failedOrRetried = keptDetail,
            truncatedClasses = rankedClasses.size - keptClasses.size,
            truncatedDetail = rankedDetail.size - keptDetail.size,
        )
    }

    /** Aggregated/multi-suite output can repeat a class across `<testsuite>`s — merge to one row. */
    private fun mergeByClassName(rollups: List<TestClassResult>): List<TestClassResult> {
        if (rollups.size == rollups.distinctBy { it.className }.size) return rollups // common case: 1 file/class
        val byName = LinkedHashMap<String, TestClassResult>()
        for (r in rollups) {
            byName[r.className] = byName[r.className]?.let { existing ->
                existing.copy(
                    passed = existing.passed + r.passed,
                    failed = existing.failed + r.failed,
                    skipped = existing.skipped + r.skipped,
                    durationMs = existing.durationMs + r.durationMs,
                )
            } ?: r
        }
        return byName.values.toList()
    }

    private val EXECUTED_OUTCOMES = setOf(TaskOutcome.EXECUTED, TaskOutcome.FAILED)
}
