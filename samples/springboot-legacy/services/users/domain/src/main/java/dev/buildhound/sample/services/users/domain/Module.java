package dev.buildhound.sample.services.users.domain;

/** Generated module marker — :services:users:domain. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":services:users:domain";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.services.users.api.Module.name();
    }
}
