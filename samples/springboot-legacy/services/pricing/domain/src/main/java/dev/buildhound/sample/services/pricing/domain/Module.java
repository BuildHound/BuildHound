package dev.buildhound.sample.services.pricing.domain;

/** Generated module marker — :services:pricing:domain. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":services:pricing:domain";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.services.pricing.api.Module.name();
    }
}
