package dev.buildhound.sample.library.logging

/** Generated module marker — :library:logging. A leaf module (no project dependencies); other
 *  modules reference this type to make their `implementation(project(...))` edge a real
 *  compile-time dependency for the build graph. */
object Marker {
    const val PATH = ":library:logging"

    fun wired(): String {
        return PATH
    }
}
