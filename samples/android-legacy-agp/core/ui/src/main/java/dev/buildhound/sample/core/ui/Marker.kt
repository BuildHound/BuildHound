package dev.buildhound.sample.core.ui

/** Generated module marker — :core:ui. Referencing a dependency's type below makes the
 *  `implementation(project(...))` edge a real compile-time dependency for the build graph. */
object Marker {
    const val PATH = ":core:ui"

    fun wired(): String {
        return dev.buildhound.sample.core.common.Greeter.greeting(PATH)
    }
}
