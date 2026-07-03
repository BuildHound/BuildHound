package dev.buildhound.commons.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden-file contract test (roadmap phase 0): every historical schema version must keep
 * deserializing. New schema versions add a new golden file; existing files are never edited.
 */
class GoldenPayloadTest {

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/golden/$name")) { "missing golden file $name" }
            .readBytes()
            .decodeToString()

    @Test
    fun `schema v1 golden file deserializes`() {
        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1.json"))

        assertEquals(1, payload.schemaVersion)
        assertEquals("3f9d3c1e-8f3a-4a5e-9b2d-0c8f4a7d6e21", payload.buildId)
        assertEquals(BuildOutcome.SUCCESS, payload.outcome)
        assertEquals(BuildMode.CI, payload.mode)
        assertEquals(2, payload.tasks.size)
        assertEquals(TaskOutcome.FROM_CACHE, payload.tasks[0].outcome)
        assertEquals(ConfigurationCacheState.HIT, payload.environment?.configurationCache)
        assertEquals("azure-devops", payload.ci?.provider)
        assertEquals(0.5, payload.derived?.cacheableHitRate)
    }

    @Test
    fun `schema v1 task-metadata golden file deserializes with populated fields`() {
        val payload =
            BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1-task-metadata.json"))

        assertEquals(1, payload.schemaVersion)
        assertEquals(3, payload.tasks.size)
        val compile = payload.tasks[0]
        assertEquals("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", compile.type)
        assertEquals(true, compile.cacheable)
        assertEquals(TaskOutcome.FROM_CACHE, compile.outcome)
        val nonCacheable = payload.tasks[2]
        assertEquals(false, nonCacheable.cacheable)
        assertEquals("Produces no cacheable output", nonCacheable.nonCacheableReason)
        assertEquals(0.5, payload.derived?.cacheableHitRate)
        assertEquals(1200, payload.derived?.configurationMs)
        // Pin the golden's hand-authored hit rate to what the live formula produces for
        // its tasks, so a future golden edit can't silently drift from the calculator.
        assertEquals(payload.derived?.cacheableHitRate, DerivedMetricsCalculator.cacheableHitRate(payload.tasks))
    }

    @Test
    fun `round trip is lossless`() {
        for (name in listOf("build-payload-v1.json", "build-payload-v1-task-metadata.json")) {
            val original = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), golden(name))
            val reEncoded = BuildHoundJson.payload.encodeToString(BuildPayload.serializer(), original)
            val decodedAgain = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), reEncoded)

            assertEquals(original, decodedAgain, name)
        }
    }

    @Test
    fun `unknown fields from newer plugins are tolerated`() {
        val withExtraField = golden("build-payload-v1.json")
            .replaceFirst("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"futureField\": {\"x\": 1},")

        val payload = BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), withExtraField)
        assertTrue(payload.tasks.isNotEmpty())
    }
}
