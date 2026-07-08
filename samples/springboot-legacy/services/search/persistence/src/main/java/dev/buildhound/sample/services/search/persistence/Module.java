package dev.buildhound.sample.services.search.persistence;

/** Generated module marker — :services:search:persistence. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":services:search:persistence";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.services.search.domain.Module.name();
    }
}
