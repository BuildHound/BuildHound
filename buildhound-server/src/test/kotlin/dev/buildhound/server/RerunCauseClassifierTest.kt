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

    @Test
    fun `no bare 'forced' catch — the word alone does not classify as FORCED`() {
        // Regression pin for the removed unverified "forced" substring (plan-061 review finding): no
        // real Gradle 9.6.1 rebuild-reason template contains "forced" (checked against the decompiled
        // gradle-execution/gradle-core jars — only unrelated dependency-resolution "force"/"enforced"
        // strings exist elsewhere in the distribution). A message that merely says "forced" without one
        // of the two verified FORCED trigger phrases must fall through to UNCLASSIFIED, not FORCED.
        assertEquals(
            RerunCause.UNCLASSIFIED,
            RerunCauseClassifier.classify("Task was forced to rerun for an unspecified reason."),
        )
    }

    // --- Adversarial cross-bucket near-misses: prove the when-chain's fixed check order resolves
    // collisions deterministically rather than by accident (plan-061 review finding). ---

    @Test
    fun `an OUTPUT_MISSING-shaped string that also mentions 'changed' still lands in OUTPUT_MISSING`() {
        // Contains "output", "does not exist" (OUTPUT_MISSING's trigger) AND "changed" (an
        // UPSTREAM_OUTPUT trigger). OUTPUT_MISSING is checked first in the when-chain, so it must win.
        assertEquals(
            RerunCause.OUTPUT_MISSING,
            RerunCauseClassifier.classify(
                "Output file 'state.bin' does not exist; it may have changed since the last recorded run.",
            ),
        )
    }

    @Test
    fun `an UPSTREAM_OUTPUT-shaped string without 'does not exist' is never caught by the OUTPUT_MISSING branch`() {
        // Companion sanity check: "output" + "changed" but no "does not exist" and no "no history" —
        // must fall through OUTPUT_MISSING's guard and land in UPSTREAM_OUTPUT as its shape actually earns.
        assertEquals(
            RerunCause.UPSTREAM_OUTPUT,
            RerunCauseClassifier.classify("Output property 'reportDir' file report.html has changed."),
        )
    }

    @Test
    fun `a SOURCE-shaped 'input' string that also says 'has changed from' lands in IMPL_CLASSPATH`() {
        // Contains "input" + "changed" (SOURCE's trigger) AND "has changed from" (an IMPL_CLASSPATH
        // trigger), with none of IMPL_CLASSPATH's other keywords ("classpath"/"additional action"/
        // "implementation") present — isolates that "has changed from" alone is what tips this. Since
        // IMPL_CLASSPATH is checked before SOURCE in the when-chain, it must win even though the message
        // is otherwise input-property-shaped.
        assertEquals(
            RerunCause.IMPL_CLASSPATH,
            RerunCauseClassifier.classify("Input property 'threshold' has changed from '1' to '2'."),
        )
    }

    @Test
    fun `a genuine SOURCE string without the 'from' shape stays SOURCE, not IMPL_CLASSPATH`() {
        // Companion sanity check: "input" + "has changed." but no "has changed from" / "classpath" /
        // "additional action" / "implementation" — must fall through IMPL_CLASSPATH's guard and land in
        // SOURCE as its shape actually earns.
        assertEquals(
            RerunCause.SOURCE,
            RerunCauseClassifier.classify("Input property 'options' has changed."),
        )
    }
}
