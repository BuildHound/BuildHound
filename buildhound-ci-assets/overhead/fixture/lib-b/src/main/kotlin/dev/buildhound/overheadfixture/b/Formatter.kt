package dev.buildhound.overheadfixture.b

import dev.buildhound.overheadfixture.a.Greeter
import dev.buildhound.overheadfixture.a.Numbers

class Formatter {
    private val greeter = Greeter()

    fun banner(name: String): String {
        val line = greeter.greet(name)
        val total = Numbers.sum(Numbers.squares(5))
        return "$line (lucky number $total)"
    }
}
