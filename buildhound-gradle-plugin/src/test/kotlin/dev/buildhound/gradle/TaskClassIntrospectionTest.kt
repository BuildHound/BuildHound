package dev.buildhound.gradle

import org.gradle.api.tasks.CacheableTask
import org.gradle.work.DisableCachingByDefault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@CacheableTask
private open class CacheableFixture

/** Stands in for Gradle's runtime `<Task>_Decorated` subclass over a cacheable base. */
private class CacheableFixture_Decorated : CacheableFixture()

@DisableCachingByDefault(because = "Produces no cacheable output")
private open class DisabledWithReasonFixture

@DisableCachingByDefault
private open class DisabledNoReasonFixture

private open class PlainFixture

@CacheableTask
@DisableCachingByDefault(because = "ignored while cacheable")
private open class CacheableAndDisabledFixture

class TaskClassIntrospectionTest {

    @Test
    fun directly_annotated_class_is_cacheable() {
        val meta = TaskClassIntrospection.introspect(CacheableFixture::class.java)
        assertEquals(true, meta.cacheable)
        assertNull(meta.nonCacheableReason)
        assertEquals("dev.buildhound.gradle.CacheableFixture", meta.type)
    }

    @Test
    fun cacheable_on_superclass_survives_decoration_and_the_decorated_suffix_is_stripped() {
        val meta = TaskClassIntrospection.introspect(CacheableFixture_Decorated::class.java)
        assertEquals(true, meta.cacheable, "the annotation is on the superclass, found via the walk")
        assertEquals("dev.buildhound.gradle.CacheableFixture", meta.type, "_Decorated stripped from the FQN")
    }

    @Test
    fun disable_caching_reason_is_captured_when_not_cacheable() {
        val meta = TaskClassIntrospection.introspect(DisabledWithReasonFixture::class.java)
        assertEquals(false, meta.cacheable)
        assertEquals("Produces no cacheable output", meta.nonCacheableReason)
    }

    @Test
    fun blank_disable_caching_reason_becomes_null() {
        val meta = TaskClassIntrospection.introspect(DisabledNoReasonFixture::class.java)
        assertEquals(false, meta.cacheable)
        assertNull(meta.nonCacheableReason)
    }

    @Test
    fun plain_class_is_non_cacheable_with_no_reason() {
        val meta = TaskClassIntrospection.introspect(PlainFixture::class.java)
        assertEquals(false, meta.cacheable)
        assertNull(meta.nonCacheableReason)
        assertEquals("dev.buildhound.gradle.PlainFixture", meta.type)
    }

    @Test
    fun cacheable_wins_over_a_disable_caching_annotation() {
        val meta = TaskClassIntrospection.introspect(CacheableAndDisabledFixture::class.java)
        assertEquals(true, meta.cacheable)
        assertNull(meta.nonCacheableReason)
    }
}
