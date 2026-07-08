package dev.buildhound.gradle

import java.io.Serializable

/**
 * Where one `Test` task writes its JUnit XML, captured at configuration time (plan 024).
 * Serializable so it rides the [TaskEventCollector] service parameter map (plan 016) —
 * replayed verbatim on a configuration-cache hit, when the `whenReady` callback that built it
 * never runs. [TaskMetadata] no longer travels this channel: since plan 056 it rides the
 * finalizer's own Flow-action parameter instead (closes plan 045). Since plan 044 the durable
 * [TestLocationSidecar] file is the **primary** finalizer channel for this class (it survives the
 * composite-build param freeze and a CC hit); this service param is the classpath-path fallback.
 *
 * [junitXmlRequired] (plan 053, research F3) mirrors the public `Report.getRequired()` read on the
 * same `DirectoryReport`: `false` means an executed task's XML is flag-authoritative-absent, not
 * silently unparseable — [TestResultCollector] short-circuits before touching disk for such a task.
 * Defaults `true` so an older (pre-053) sidecar line that omits the field degrades safely.
 */
data class TestResultLocations(
    val junitXmlDir: String,
    val module: String?,
    val junitXmlRequired: Boolean = true,
) : Serializable
