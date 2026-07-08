package dev.buildhound.gradle

import java.io.Serializable

/**
 * Static, per-build-invariant facts about a task class (spec §3.2 / §4): the grouping
 * `type`, whether it is statically `cacheable`, and the free-text `nonCacheableReason`.
 * Serializable so it rides in the finalizer `FlowAction`'s `taskMetadata` parameter
 * (`TelemetryFinalizerAction.Parameters`, plan 056) like [CollectedCi] rides into the
 * FlowAction (plan 016).
 */
data class TaskMetadata(
    val type: String?,
    val cacheable: Boolean?,
    val nonCacheableReason: String?,
) : Serializable

/**
 * Reflection over a task's runtime [Class] — kept free of Gradle types (takes `Class<*>`,
 * matches annotations by fully-qualified name) so it unit-tests as plain logic, the same
 * split [GitExec]/[VcsParsing] use (plan 015 precedent).
 *
 * The walk is name-based rather than `isAnnotationPresent`: it stays robust to Gradle's
 * generated `_Decorated` subclasses and to annotations that are not `@Inherited`, and it
 * never links a Gradle symbol into this module.
 */
internal object TaskClassIntrospection {

    private const val DECORATED_SUFFIX = "_Decorated"
    private const val CACHEABLE_TASK = "org.gradle.api.tasks.CacheableTask"
    private const val DISABLE_CACHING = "org.gradle.work.DisableCachingByDefault"

    /** Never throws — a defective annotation walk degrades to `type` only, flags null. */
    fun introspect(taskClass: Class<*>): TaskMetadata {
        // strippedName reads only Class.name, which cannot throw, so type is always known;
        // only the annotation walk is defensive.
        val type = strippedName(taskClass)
        return runCatching {
            val cacheable = hasAnnotation(taskClass, CACHEABLE_TASK)
            val nonCacheableReason =
                if (cacheable) null
                else findAnnotation(taskClass, DISABLE_CACHING)?.let(::becauseText)
            TaskMetadata(type = type, cacheable = cacheable, nonCacheableReason = nonCacheableReason)
        }.getOrElse { TaskMetadata(type = type, cacheable = null, nonCacheableReason = null) }
    }

    /** FQN with Gradle's runtime `_Decorated` suffix removed — the stable grouping key. */
    private fun strippedName(taskClass: Class<*>): String = taskClass.name.removeSuffix(DECORATED_SUFFIX)

    private fun hasAnnotation(taskClass: Class<*>, fqn: String): Boolean = findAnnotation(taskClass, fqn) != null

    private fun findAnnotation(taskClass: Class<*>, fqn: String): Annotation? {
        var current: Class<*>? = taskClass
        while (current != null && current != Any::class.java) {
            current.declaredAnnotations.firstOrNull { it.typeName() == fqn }?.let { return it }
            current = current.superclass
        }
        return null
    }

    /**
     * `DisableCachingByDefault.because()`, read reflectively; blank/absent → null. The
     * accessor is invoked, so a hostile same-named annotation with a side-effecting
     * `because()` would run it here — assumed inert like every real annotation member,
     * and any throw is contained by the surrounding `runCatching`.
     */
    private fun becauseText(annotation: Annotation): String? = runCatching {
        (annotation.annotationType().getMethod("because").invoke(annotation) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // kotlin.Annotation has no accessor; the JDK proxy behind it is a java.lang.annotation.Annotation.
    private fun Annotation.typeName(): String = annotationType().name

    // The cast to java.lang.annotation.Annotation is deliberate — annotationType() is a JDK method
    // with no kotlin.Annotation equivalent; suppress the platform-class-mapping advisory.
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun Annotation.annotationType(): Class<out Annotation> =
        (this as java.lang.annotation.Annotation).annotationType()
}
