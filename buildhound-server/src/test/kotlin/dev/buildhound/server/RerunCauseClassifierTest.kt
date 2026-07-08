package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [RerunCauseClassifier] against real Gradle 9.6.1 reason strings (plan 061, research F11).
 * "Golden-fixture" cases below are strings literally captured from a real Gradle run in this repo's
 * own commons golden fixtures (`build-payload-v1*.json` / `build-payload-v1-task-metadata.json`) or the
 * plugin's functional tests. "Template" cases substitute concrete values into a message template whose
 * literal fixed segments were verified against the string constants baked into the project's own
 * `gradle-9.6.1` wrapper distribution's `gradle-execution`/`gradle-core` jars (decompiled for this
 * plan — see `RerunCauses.kt`'s classifier doc comment for the source classes). CACHING_DISABLED is
 * flagged as template-derived-but-not-confirmed-observed-in-executionReasons — implemented per plan,
 * honesty noted rather than chased.
 */
class RerunCauseClassifierTest {

    @Test
    fun `golden-fixture SOURCE string classifies as SOURCE`() {
        // Verbatim from buildhound-commons/src/jvmTest/resources/golden/build-payload-v1.json.
        assertEquals(RerunCause.SOURCE, RerunCauseClassifier.classify("Value of input property 'options' has changed."))
    }

    @Test
    fun `template SOURCE variants classify as SOURCE`() {
        assertEquals(RerunCause.SOURCE, RerunCauseClassifier.classify("Input property 'source' file input.txt has changed."))
        assertEquals(RerunCause.SOURCE, RerunCauseClassifier.classify("Input property 'source' has been removed for task ':sum'."))
    }

    @Test
    fun `template IMPL_CLASSPATH variants classify as IMPL_CLASSPATH`() {
        // Templates verified against ImplementationChanges' string constants.
        assertEquals(
            RerunCause.IMPL_CLASSPATH,
            RerunCauseClassifier.classify("Class path of task ':app:compileJava' has changed from '1a2b3c' to '4d5e6f'."),
        )
        assertEquals(
            RerunCause.IMPL_CLASSPATH,
            RerunCauseClassifier.classify("The type of task ':app:compileJava' has changed from 'com.example.OldTask' to 'com.example.NewTask'."),
        )
        assertEquals(
            RerunCause.IMPL_CLASSPATH,
            RerunCauseClassifier.classify("One or more additional actions for task ':app:compileJava' have changed."),
        )
    }

    @Test
    fun `golden-fixture UPSTREAM_OUTPUT string classifies as UPSTREAM_OUTPUT`() {
        // Verbatim from build-payload-v1-task-metadata.json / build-payload-v1.json.
        assertEquals(
            RerunCause.UPSTREAM_OUTPUT,
            RerunCauseClassifier.classify("Output property 'binaryResultsDirectory' file has been removed."),
        )
    }

    @Test
    fun `template UPSTREAM_OUTPUT variant (changed, not removed) classifies as UPSTREAM_OUTPUT`() {
        assertEquals(
            RerunCause.UPSTREAM_OUTPUT,
            RerunCauseClassifier.classify("Output property 'outputDir' file build/out.txt has changed."),
        )
    }

    @Test
    fun `verified-exact OUTPUT_MISSING string classifies as OUTPUT_MISSING`() {
        // Verbatim string constant from NeverUpToDateStep (gradle-execution-9.6.1.jar).
        assertEquals(RerunCause.OUTPUT_MISSING, RerunCauseClassifier.classify("No history is available."))
    }

    @Test
    fun `caching-disabled strings classify as CACHING_DISABLED (template-derived, not confirmed observed in executionReasons)`() {
        // Verbatim string constants from AbstractResolveCachingStateStep / CachingDisabledReasonCategory
        // (gradle-execution-9.6.1.jar) — these are Gradle's *cacheability* messages, not confirmed to be
        // the exact shape TaskExecutionResult.getExecutionReasons() emits; implemented per plan 061.
        assertEquals(RerunCause.CACHING_DISABLED, RerunCauseClassifier.classify("Build cache is disabled"))
        assertEquals(
            RerunCause.CACHING_DISABLED,
            RerunCauseClassifier.classify("Caching disabled for task ':app:compileJava' because: Not worth caching."),
        )
        assertEquals(
            RerunCause.CACHING_DISABLED,
            RerunCauseClassifier.classify(
                "Caching has been disabled to ensure correctness. Please consult deprecation warnings for more details.",
            ),
        )
    }

    @Test
    fun `verified-exact FORCED strings classify as FORCED`() {
        // Verbatim string constants from DefaultTaskExecutionMode (gradle-core-9.6.1.jar).
        assertEquals(RerunCause.FORCED, RerunCauseClassifier.classify("Executed with '--rerun-tasks'."))
        assertEquals(RerunCause.FORCED, RerunCauseClassifier.classify("Task.upToDateWhen is false."))
    }

    @Test
    fun `unrecognized, blank, and empty reasons all classify as UNCLASSIFIED`() {
        assertEquals(RerunCause.UNCLASSIFIED, RerunCauseClassifier.classify(""))
        assertEquals(RerunCause.UNCLASSIFIED, RerunCauseClassifier.classify("   "))
        // Simulated future Gradle version drift: a wording this classifier has never seen.
        assertEquals(
            RerunCause.UNCLASSIFIED,
            RerunCauseClassifier.classify("Task was executed because reasons are unknown in Gradle 42.0."),
        )
    }

    @Test
    fun `classification is case-insensitive`() {
        assertEquals(RerunCause.FORCED, RerunCauseClassifier.classify("EXECUTED WITH '--RERUN-TASKS'."))
    }
}
