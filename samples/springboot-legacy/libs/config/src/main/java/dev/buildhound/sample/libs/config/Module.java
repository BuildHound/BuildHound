package dev.buildhound.sample.libs.config;

/** Generated module marker — :libs:config. The reference below forces a real compile-time edge to a
 *  dependency so the module graph produces non-trivial task/critical-path telemetry. */
public final class Module {

    private Module() {
    }

    public static String name() {
        return ":libs:config";
    }

    public static String wiring() {
        return name() + " -> " + dev.buildhound.sample.libs.common.Module.name();
    }
}
