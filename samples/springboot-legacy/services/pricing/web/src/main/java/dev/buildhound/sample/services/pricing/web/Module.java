package dev.buildhound.sample.services.pricing.web;

/** Generated module marker — :services:pricing:web. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":services:pricing:web";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.services.pricing.domain.Module.name();
    }
}
