package io.example.btp.commons.payload

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
        val payload = BtpJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1.json"))

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
    fun `round trip is lossless`() {
        val original = BtpJson.payload.decodeFromString(BuildPayload.serializer(), golden("build-payload-v1.json"))
        val reEncoded = BtpJson.payload.encodeToString(BuildPayload.serializer(), original)
        val decodedAgain = BtpJson.payload.decodeFromString(BuildPayload.serializer(), reEncoded)

        assertEquals(original, decodedAgain)
    }

    @Test
    fun `unknown fields from newer plugins are tolerated`() {
        val withExtraField = golden("build-payload-v1.json")
            .replaceFirst("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"futureField\": {\"x\": 1},")

        val payload = BtpJson.payload.decodeFromString(BuildPayload.serializer(), withExtraField)
        assertTrue(payload.tasks.isNotEmpty())
    }
}
