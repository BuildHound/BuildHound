package dev.buildhound.sample.feature.home

/** Generated module marker — :feature:home. Referencing a dependency's type below makes the
 *  `implementation(project(...))` edge a real compile-time dependency for the build graph. */
object Marker {
    const val PATH = ":feature:home"

    fun wired(): String {
        return "$PATH -> ${dev.buildhound.sample.core.ui.Marker.PATH}"
    }
}
