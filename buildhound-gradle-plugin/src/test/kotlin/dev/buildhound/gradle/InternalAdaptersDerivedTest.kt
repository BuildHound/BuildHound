package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Core reads the two `derived` inputs out of the opaque internal-adapters extension (plan 038) —
 * without depending on the module or any internal Gradle type. A missing/malformed block degrades to
 * `(null, null)`, never a throw.
 */
class InternalAdaptersDerivedTest {

    @Test
    fun `extracts avoidedMs and dependency edges from the internalAdapters extension`() {
        val ext: Map<String, JsonElement> = mapOf(
            "internalAdapters" to buildJsonObject {
                put("schemaVersion", 1)
                put("avoidedMs", 8000)
                putJsonObject("dependencyEdges") {
                    putJsonArray(":app:test") { add(":app:compileJava") }
                }
            },
        )
        val (avoided, edges) = internalAdaptersDerivedInputs(ext)
        assertEquals(8000L, avoided)
        assertEquals(mapOf(":app:test" to listOf(":app:compileJava")), edges)
    }

    @Test
    fun `an absent or malformed extension degrades to null, null`() {
        assertEquals(null to null, internalAdaptersDerivedInputs(emptyMap()))
        val notAnObject: Map<String, JsonElement> = mapOf("internalAdapters" to JsonPrimitive("oops"))
        assertEquals(null to null, internalAdaptersDerivedInputs(notAnObject))
    }
}
