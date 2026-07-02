package dev.buildhound.server

import dev.buildhound.commons.payload.BuildPayload
import java.util.concurrent.ConcurrentHashMap

/** The tenant a request acts as — resolved from its token, never from the payload. */
data class ProjectRef(val id: String, val key: String)

/**
 * Persistence boundary for ingested builds (architecture §5): every operation carries
 * the tenant. Real implementation is Postgres; in-memory serves tests and DB-less dev.
 */
interface BuildStore {
    /** Idempotent on (project, [BuildPayload.buildId]); false when already stored. */
    fun save(projectId: String, payload: BuildPayload): Boolean

    fun findById(projectId: String, buildId: String): BuildPayload?

    fun count(projectId: String): Long
}

/** Token lookups by hash — plaintext tokens never reach a store (spec §8). */
interface TokenStore {
    fun resolveProject(tokenHash: String): ProjectRef?

    /** Idempotent pilot bootstrap: create the project and attach the token hash. */
    fun ensureProjectWithToken(projectKey: String, tokenHash: String): ProjectRef
}

class InMemoryBuildStore : BuildStore {
    private val builds = ConcurrentHashMap<Pair<String, String>, BuildPayload>()

    override fun save(projectId: String, payload: BuildPayload): Boolean =
        builds.putIfAbsent(projectId to payload.buildId, payload) == null

    override fun findById(projectId: String, buildId: String): BuildPayload? =
        builds[projectId to buildId]

    override fun count(projectId: String): Long =
        builds.keys.count { it.first == projectId }.toLong()
}

class InMemoryTokenStore : TokenStore {
    private val projects = ConcurrentHashMap<String, ProjectRef>() // key -> project
    private val tokens = ConcurrentHashMap<String, String>() // tokenHash -> projectId

    override fun resolveProject(tokenHash: String): ProjectRef? {
        val projectId = tokens[tokenHash] ?: return null
        return projects.values.firstOrNull { it.id == projectId }
    }

    override fun ensureProjectWithToken(projectKey: String, tokenHash: String): ProjectRef {
        val project = projects.computeIfAbsent(projectKey) {
            ProjectRef(id = java.util.UUID.randomUUID().toString(), key = projectKey)
        }
        tokens.putIfAbsent(tokenHash, project.id)
        return project
    }
}
