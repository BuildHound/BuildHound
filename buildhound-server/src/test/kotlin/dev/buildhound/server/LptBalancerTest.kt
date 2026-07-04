package dev.buildhound.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure LPT shard balancer (plan 040). */
class LptBalancerTest {

    @Test
    fun `descending assignment balances makespan on a known set`() {
        // Durations 8,5,4,3 over 2 shards → LPT: 8|5,4,3? no: 8→s0; 5→s1; 4→s1(5)vs s0(8)→s1=9; 3→s0(8). s0=11,s1=9.
        val p90 = mapOf("a" to 8L, "b" to 5L, "c" to 4L, "d" to 3L)
        val shards = LptBalancer.plan(listOf("a", "b", "c", "d"), total = 2, p90ByKey = p90)
        assertEquals(2, shards.size)
        // Every suite assigned exactly once; union == input.
        assertEquals(setOf("a", "b", "c", "d"), shards.flatten().toSet())
        assertEquals(4, shards.flatten().size)
        // Makespan (max shard load) is near-optimal — no shard carries everything.
        val loads = shards.map { it.sumOf { k -> p90.getValue(k) } }
        assertTrue(loads.max() <= 11, "makespan $loads")
        assertTrue(loads.min() >= 9, "balanced $loads")
    }

    @Test
    fun `an unknown suite uses the median of known durations`() {
        val p90 = mapOf("known1" to 100L, "known2" to 100L, "known3" to 100L)
        val shards = LptBalancer.plan(listOf("known1", "known2", "known3", "unknown"), total = 2, p90ByKey = p90)
        // "unknown" weighs 100 (median), so it lands like any 100ms suite — union covers all 4.
        assertEquals(setOf("known1", "known2", "known3", "unknown"), shards.flatten().toSet())
    }

    @Test
    fun `no history at all floors every suite at 5 seconds and round-robins`() {
        val shards = LptBalancer.plan(listOf("a", "b", "c", "d"), total = 2, p90ByKey = emptyMap())
        // All weigh 5000 → even split, 2 per shard.
        assertEquals(2, shards[0].size)
        assertEquals(2, shards[1].size)
        assertEquals(setOf("a", "b", "c", "d"), shards.flatten().toSet())
    }

    @Test
    fun `total of one puts everything in a single shard`() {
        val shards = LptBalancer.plan(listOf("a", "b"), total = 1, p90ByKey = mapOf("a" to 9L))
        assertEquals(listOf(listOf("a", "b")), shards)
    }

    @Test
    fun `total greater than suites leaves empty tail shards`() {
        val shards = LptBalancer.plan(listOf("a", "b"), total = 4, p90ByKey = mapOf("a" to 9L, "b" to 3L))
        assertEquals(4, shards.size)
        assertEquals(2, shards.count { it.isNotEmpty() })
        assertEquals(2, shards.count { it.isEmpty() })
    }

    @Test
    fun `duplicate suites are deduped`() {
        val shards = LptBalancer.plan(listOf("a", "a", "b"), total = 2, p90ByKey = mapOf("a" to 9L, "b" to 3L))
        assertEquals(2, shards.flatten().size, "no suite assigned twice")
        assertEquals(setOf("a", "b"), shards.flatten().toSet())
    }

    @Test
    fun `p90 is nearest-rank`() {
        assertEquals(0, LptBalancer.p90(emptyList()))
        assertEquals(10, LptBalancer.p90(listOf(10)))
        // 10 values 1..10 → ceil(0.9*10)=9 → 9th smallest = 9.
        assertEquals(9, LptBalancer.p90((1L..10L).toList()))
    }
}
