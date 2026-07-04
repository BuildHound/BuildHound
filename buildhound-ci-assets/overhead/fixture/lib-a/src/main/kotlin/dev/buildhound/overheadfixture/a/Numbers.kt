package dev.buildhound.overheadfixture.a

/** Extra source so the module has more than one task-relevant file (task density, plan 034). */
object Numbers {
    fun sum(values: List<Int>): Int = values.sum()

    fun squares(n: Int): List<Int> = (1..n).map { it * it }
}
