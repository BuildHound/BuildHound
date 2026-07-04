package dev.buildhound.sharding

import dev.buildhound.commons.payload.BuildHoundJson
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** Gradle-free logic of the sharding addon (plan 040): suite discovery + payload model. */
class ShardingUnitTest {

    @Test
    fun `discovery finds top-level test classes, sorted, skipping inner and info classes`() {
        val dir = createTempDirectory("bh-sharding").toFile()
        File(dir, "com/example").mkdirs()
        File(dir, "com/example/FooTest.class").writeText("x")
        File(dir, "com/example/FooTest\$Inner.class").writeText("x")
        File(dir, "com/example/BarTest.class").writeText("x")
        File(dir, "module-info.class").writeText("x")
        File(dir, "com/example/Helper.txt").writeText("x") // non-class ignored

        assertEquals(listOf("com.example.BarTest", "com.example.FooTest"), SuiteDiscovery.discover(listOf(dir)))
    }

    @Test
    fun `discovery of a missing or non-directory path is empty, never throws`() {
        assertEquals(emptyList(), SuiteDiscovery.discover(listOf(File("/does/not/exist"))))
        assertEquals(emptyList(), SuiteDiscovery.discover(emptyList()))
    }

    @Test
    fun `the extension block round-trips`() {
        val ext = TestShardingExtension(shardPlanId = "abc123", shardIndex = 2, shardTotal = 4, appliedFilter = true)
        val json = BuildHoundJson.payload.encodeToString(TestShardingExtension.serializer(), ext)
        assertEquals(ext, BuildHoundJson.payload.decodeFromString(TestShardingExtension.serializer(), json))
    }

    @Test
    fun `the plan request and response round-trip`() {
        val req = ShardPlanRequest(reference = "run-1", index = 1, total = 2, suites = listOf("a", "b"))
        assertEquals(req, BuildHoundJson.payload.decodeFromString(ShardPlanRequest.serializer(),
            BuildHoundJson.payload.encodeToString(ShardPlanRequest.serializer(), req)))
        val resp = ShardPlanResponse(shardPlanId = "p", index = 1, classes = listOf("a"), assigned = listOf("a", "b"))
        assertEquals(resp, BuildHoundJson.payload.decodeFromString(ShardPlanResponse.serializer(),
            BuildHoundJson.payload.encodeToString(ShardPlanResponse.serializer(), resp)))
    }
}
