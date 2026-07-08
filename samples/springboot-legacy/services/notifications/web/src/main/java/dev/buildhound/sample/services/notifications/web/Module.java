package dev.buildhound.sample.services.notifications.web;

/** Generated module marker — :services:notifications:web. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":services:notifications:web";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.services.notifications.domain.Module.name();
    }
}
