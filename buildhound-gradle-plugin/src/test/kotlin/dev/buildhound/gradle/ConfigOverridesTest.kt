package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure, fail-safe parsing + the token-exclusion invariant (plan 027). */
class ConfigOverridesTest {

    @Test
    fun boolean_parsing_is_lenient_and_fail_safe() {
        for (truthy in listOf("true", "TRUE", "1", "yes", "on")) assertEquals(true, ConfigOverrides.parseBool(truthy), truthy)
        for (falsy in listOf("false", "0", "no", "off")) assertEquals(false, ConfigOverrides.parseBool(falsy), falsy)
        for (bad in listOf("", "maybe", "2", "enabled")) assertNull(ConfigOverrides.parseBool(bad), "unparseable → ignored: $bad")
    }

    @Test
    fun enum_parsing_is_case_insensitive_and_fail_safe() {
        assertEquals(TelemetryMode.DISABLED, ConfigOverrides.parseEnum<TelemetryMode>("disabled"))
        assertEquals(TelemetryMode.CI, ConfigOverrides.parseEnum<TelemetryMode>("CI"))
        assertNull(ConfigOverrides.parseEnum<TelemetryMode>("nonsense"))
    }

    @Test
    fun the_server_token_is_never_overridable() {
        assertFalse(ConfigOverrides.isOverridable("server.token"))
        assertTrue(ConfigOverrides.isOverridable("mode"))
        assertTrue(ConfigOverrides.isOverridable("server.url"))
        assertEquals("server.token", ConfigOverrides.EXCLUDED_KEY)
    }
}
