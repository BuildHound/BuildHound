package dev.buildhound.sample.feature.settings

/** Generated module marker — :feature:settings. Referencing a dependency's type below makes the
 *  `implementation(project(...))` edge a real compile-time dependency for the build graph. */
object Marker {
    const val PATH = ":feature:settings"

    fun wired(): String {
        return "$PATH -> ${dev.buildhound.sample.core.ui.Marker.PATH}"
    }
}
