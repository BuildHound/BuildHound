package dev.buildhound.sample.core.common

/** Trivial shared type so `:app` has a real compile-time dependency on `:core:common`. */
object Greeter {
    fun greeting(name: String): String = "Hello, $name!"
}
