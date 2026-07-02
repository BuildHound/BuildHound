package dev.buildhound.server

import dev.buildhound.commons.payload.BuildPayload
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistence boundary for ingested builds. The scaffold ships an in-memory implementation;
 * the real one is Postgres + TimescaleDB (spec §5) and arrives with phase 1.
 */
interface BuildStore {
    /** Idempotent on [BuildPayload.buildId]; returns false when the build was already stored. */
    fun save(payload: BuildPayload): Boolean

    fun findById(buildId: String): BuildPayload?

    fun count(): Long
}

class InMemoryBuildStore : BuildStore {
    private val builds = ConcurrentHashMap<String, BuildPayload>()

    override fun save(payload: BuildPayload): Boolean = builds.putIfAbsent(payload.buildId, payload) == null

    override fun findById(buildId: String): BuildPayload? = builds[buildId]

    override fun count(): Long = builds.size.toLong()
}
