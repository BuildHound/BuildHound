package dev.buildhound.server

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import dev.buildhound.commons.payload.CiInfo
import dev.buildhound.server.connector.CiRun

/**
 * Connector expected-build fallback (plan 033): when the CI Timeline shows a run *completed* but no
 * payload ever arrived (the build died on an ephemeral agent the plugin's start-marker cannot reach
 * from a successor), record a server-originated `INTERRUPTED` build so it stops vanishing.
 *
 * The id is deterministic — `interrupted:<provider>:<runId>` — so it is idempotent (a re-fired hook
 * saves nothing new) and cannot collide with a plugin-minted UUID. It is written only when no build
 * exists for `(project, provider, runId)`; a build that *did* ingest is left untouched. The narrow
 * race where a payload lands after this check is accepted (plan §6): the phantom is still honest
 * (empty tasks, `INTERRUPTED`) and can never be mistaken for a finalized build.
 */
object InterruptedBuild {

    fun deterministicId(provider: String, runId: String): String = "interrupted:$provider:$runId"

    /** Idempotently record an INTERRUPTED build for a completed-but-never-ingested run. */
    fun recordIfMissing(builds: BuildStore, projectId: String, provider: String, runId: String, run: CiRun) {
        // A payload already landed for this run → not interrupted; leave it.
        if (builds.resolveBuildId(projectId, provider, runId) != null) return
        val finishedAt = run.finishedAt ?: return // only a completed run is interrupted, not one still running
        val startedAt = run.startedAt ?: finishedAt
        builds.save(
            projectId,
            BuildPayload(
                buildId = deterministicId(provider, runId),
                startedAt = startedAt,
                finishedAt = finishedAt,
                outcome = BuildOutcome.INTERRUPTED,
                mode = BuildMode.CI,
                ci = CiInfo(provider = provider, runId = runId),
                tasks = emptyList(),
            ),
        )
    }
}
