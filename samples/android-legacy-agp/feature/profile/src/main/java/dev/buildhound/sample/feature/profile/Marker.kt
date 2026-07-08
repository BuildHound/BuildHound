package dev.buildhound.sample.feature.profile

/** Generated module marker — :feature:profile. Referencing a dependency's type below makes the
 *  `implementation(project(...))` edge a real compile-time dependency for the build graph. */
object Marker {
    const val PATH = ":feature:profile"

    fun wired(): String {
        return "$PATH -> ${dev.buildhound.sample.core.ui.Marker.PATH}"
    }
}
