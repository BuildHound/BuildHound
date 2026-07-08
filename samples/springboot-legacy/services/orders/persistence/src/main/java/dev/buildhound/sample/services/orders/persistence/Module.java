package dev.buildhound.sample.services.orders.persistence;

/** Generated module marker — :services:orders:persistence. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":services:orders:persistence";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.services.orders.domain.Module.name();
    }
}
