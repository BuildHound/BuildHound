package dev.buildhound.gradle

/**
 * Name-based "is this a `Test` task?" check (plan 024), kept free of Gradle types (takes
 * `Class<*>`, matches by fully-qualified name) so it unit-tests as plain logic — the same
 * split [TaskClassIntrospection] uses (plan 016 precedent). Robust to Gradle's generated
 * `_Decorated` subclasses; never links a Gradle symbol into this module.
 */
internal object TestTaskIntrospection {

    private const val TEST_TASK = "org.gradle.api.tasks.testing.Test"
    private const val DECORATED_SUFFIX = "_Decorated"

    /** True iff [taskClass] is-a `org.gradle.api.tasks.testing.Test`. Never throws. */
    fun isTestTask(taskClass: Class<*>): Boolean = runCatching {
        var current: Class<*>? = taskClass
        while (current != null && current != Any::class.java) {
            if (current.name.removeSuffix(DECORATED_SUFFIX) == TEST_TASK) return true
            current = current.superclass
        }
        false
    }.getOrElse { false }
}
