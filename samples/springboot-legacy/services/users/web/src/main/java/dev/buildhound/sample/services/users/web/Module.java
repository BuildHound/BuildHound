package dev.buildhound.sample.services.users.web;

/** Generated module marker — :services:users:web. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":services:users:web";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.services.users.domain.Module.name();
    }
}
