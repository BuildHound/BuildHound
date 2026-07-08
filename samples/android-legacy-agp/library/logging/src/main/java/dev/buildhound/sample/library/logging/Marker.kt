package dev.buildhound.sample.library.logging

/** Generated module marker — :library:logging. Referencing a dependency's type below makes the
 *  `implementation(project(...))` edge a real compile-time dependency for the build graph. */
object Marker {
    const val PATH = ":library:logging"

    fun wired(): String {
        return PATH
    }
}
