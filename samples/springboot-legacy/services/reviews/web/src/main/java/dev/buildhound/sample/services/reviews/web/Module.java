package dev.buildhound.sample.services.reviews.web;

/** Generated module marker — :services:reviews:web. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":services:reviews:web";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.services.reviews.domain.Module.name();
    }
}
