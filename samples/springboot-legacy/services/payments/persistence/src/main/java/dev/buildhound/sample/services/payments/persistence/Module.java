package dev.buildhound.sample.services.payments.persistence;

/** Generated module marker — :services:payments:persistence. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":services:payments:persistence";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.services.payments.domain.Module.name();
    }
}
