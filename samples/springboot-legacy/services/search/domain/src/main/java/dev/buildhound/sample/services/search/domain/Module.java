package dev.buildhound.sample.services.search.domain;

/** Generated module marker — :services:search:domain. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":services:search:domain";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.services.search.api.Module.name();
    }
}
