package dev.buildhound.sample.services.notifications.domain;

/** Generated module marker — :services:notifications:domain. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":services:notifications:domain";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.services.notifications.api.Module.name();
    }
}
