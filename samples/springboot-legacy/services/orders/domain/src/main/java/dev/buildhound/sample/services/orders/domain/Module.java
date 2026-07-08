package dev.buildhound.sample.services.orders.domain;

/** Generated module marker — :services:orders:domain. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":services:orders:domain";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.services.orders.api.Module.name();
    }
}
