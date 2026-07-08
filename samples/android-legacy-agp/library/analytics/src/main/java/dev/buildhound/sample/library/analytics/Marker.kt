package dev.buildhound.sample.library.analytics

/** Generated module marker — :library:analytics. Referencing a dependency's type below makes the
 *  `implementation(project(...))` edge a real compile-time dependency for the build graph. */
object Marker {
    const val PATH = ":library:analytics"

    fun wired(): String {
        return dev.buildhound.sample.core.common.Greeter.greeting(PATH)
    }
}
