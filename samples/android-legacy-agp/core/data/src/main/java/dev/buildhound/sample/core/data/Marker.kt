package dev.buildhound.sample.core.data

/** Generated module marker — :core:data. Referencing a dependency's type below makes the
 *  `implementation(project(...))` edge a real compile-time dependency for the build graph. */
object Marker {
    const val PATH = ":core:data"

    fun wired(): String {
        return dev.buildhound.sample.core.common.Greeter.greeting(PATH)
    }
}
