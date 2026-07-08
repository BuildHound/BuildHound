package dev.buildhound.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Unit coverage for [dependencyEdgesOf]'s malformed-shape guard (review finding on plan 062): a payload
 * whose `extensions["internalAdapters"]` fails to decode as [InternalAdaptersView] must degrade to null,
 * never throw — the same "guarded skip, never fatal" contract [relocatabilityRowsOf] (plan 068) already
 * proves for the plan-068 reader, now proven directly for this plan-062 reader too. This is a different
 * layer than fix-068's `findById` guard (PostgresStores.kt), which only protects a corrupt whole-row jsonb
 * decode; here the outer [dev.buildhound.commons.payload.BuildPayload] decodes fine and the malformed
 * shape is confined to the opaque `internalAdapters` sub-element. Mirrors the
 * `malformedInternalAdaptersPayload` fixture pattern from `CacheMissDiagnosticsStoresIntegrationTest`
 * (plan 068), but exercises [dependencyEdgesOf] directly rather than through Postgres.
 */
class DependencyEdgesOfTest {

    @Test
    fun `a JsonPrimitive internalAdapters block degrades to null, never throws`() {
        val payload = TestPayloads.build(extensions = mapOf("internalAdapters" to JsonPrimitive("not-an-object")))
        assertNull(dependencyEdgesOf(payload))
    }

    @Test
    fun `a dependencyEdges entry with a non-array value degrades to null, never throws`() {
        val malformed = buildJsonObject {
            put("schemaVersion", 1)
            put("gradleVersion", "9.6.1")
            put("dependencyEdges", buildJsonObject { put(":app:a", "not-a-list") })
        }
        val payload = TestPayloads.build(extensions = mapOf("internalAdapters" to malformed))
        assertNull(dependencyEdgesOf(payload))
    }

    @Test
    fun `dependencyEdges itself shaped as an array instead of an object degrades to null, never throws`() {
        val malformed = buildJsonObject {
            put("schemaVersion", 1)
            put("gradleVersion", "9.6.1")
            put("dependencyEdges", JsonArray(listOf(JsonPrimitive(":a"), JsonPrimitive(":b"))))
        }
        val payload = TestPayloads.build(extensions = mapOf("internalAdapters" to malformed))
        assertNull(dependencyEdgesOf(payload))
    }
}
