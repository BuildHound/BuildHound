package dev.buildhound.sample.services.catalog.api;

/** Generated module marker — :services:catalog:api. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":services:catalog:api";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.libs.common.Module.name();
    }
}
