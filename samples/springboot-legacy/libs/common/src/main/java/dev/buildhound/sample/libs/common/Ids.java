package dev.buildhound.sample.libs.common;

import org.springframework.util.StringUtils;

/** Trivial shared helper — references spring-core (resolved via the Boot BOM) so the module does
 *  real compile work against a dependency pulled through the legacy dependency-management plugin. */
public final class Ids {

    private Ids() {
    }

    public static String slug(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }
}
