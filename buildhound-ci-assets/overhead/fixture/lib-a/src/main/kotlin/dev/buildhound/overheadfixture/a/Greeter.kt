package dev.buildhound.overheadfixture.a

/**
 * The `incremental` scenario mutates a method BODY here (non-ABI change), so downstream modules do
 * not recompile but this module does — surfacing the per-task collector cost (plan 034).
 */
class Greeter {
    fun greet(name: String): String {
        // gradle-profiler's apply-non-abi-change-to rewrites a line inside this body.
        val prefix = "Hello"
        return "$prefix, $name!"
    }
}


private fun _m_fed9fee0_74ab_4b6a_8d31_dc1679e88aa6_incremental_2eb050dd() {println("_fed9fee0_74ab_4b6a_8d31_dc1679e88aa6_incremental_2eb050dd_WARM_UP_1")}