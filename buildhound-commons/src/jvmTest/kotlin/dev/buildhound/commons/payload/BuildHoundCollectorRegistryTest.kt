package dev.buildhound.commons.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The never-throw, ordered, dedup-by-id collector façade (plan 039). */
class BuildHoundCollectorRegistryTest {

    private val context = ExtensionContributionContext(projectKey = "pilot", mode = BuildMode.CI, tasks = emptyList())

    private fun contributor(id: String, value: JsonElement?): BuildHoundExtensionContributor =
        object : BuildHoundExtensionContributor {
            override val addonId = id
            override fun contribute(context: ExtensionContributionContext): JsonElement? = value
        }

    @Test
    fun `contributors evaluate in order and keep their keys`() {
        val out = BuildHoundCollectorRegistry.collect(
            listOf(contributor("a", JsonPrimitive(1)), contributor("b", JsonPrimitive(2))),
            context,
        )
        assertEquals(listOf("a", "b"), out.keys.toList())
        assertEquals(JsonPrimitive(1), out["a"])
        assertEquals(JsonPrimitive(2), out["b"])
    }

    @Test
    fun `a null return contributes no key`() {
        val out = BuildHoundCollectorRegistry.collect(
            listOf(contributor("present", JsonPrimitive("x")), contributor("absent", null)),
            context,
        )
        assertEquals(setOf("present"), out.keys)
        assertFalse(out.containsKey("absent"))
    }

    @Test
    fun `a duplicate addon id keeps the last value and warns`() {
        val warnings = mutableListOf<String>()
        val out = BuildHoundCollectorRegistry.collect(
            listOf(contributor("dup", JsonPrimitive("first")), contributor("dup", JsonPrimitive("second"))),
            context,
        ) { warnings.add(it) }
        assertEquals(JsonPrimitive("second"), out["dup"], "last-wins on clash")
        assertEquals(1, out.size)
        assertTrue(warnings.any { it.contains("more than once") }, warnings.toString())
    }

    @Test
    fun `a throwing contributor is swallowed and does not suppress siblings`() {
        val warnings = mutableListOf<String>()
        val throwing = object : BuildHoundExtensionContributor {
            override val addonId = "boom"
            override fun contribute(context: ExtensionContributionContext): JsonElement? = throw RuntimeException("kaboom")
        }
        val out = BuildHoundCollectorRegistry.collect(
            listOf(throwing, contributor("survivor", JsonPrimitive("ok"))),
            context,
        ) { warnings.add(it) }
        assertEquals(setOf("survivor"), out.keys, "the throwing addon is skipped, the sibling still contributes")
        assertTrue(warnings.any { it.contains("boom") && it.contains("threw") }, warnings.toString())
    }
}
