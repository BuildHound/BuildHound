package org.gradle.api.tasks

/**
 * Unit-test double for `org.gradle.api.tasks.CacheableTask`. [TaskClassIntrospection]
 * matches annotations by fully-qualified name, and gradleApi() is deliberately off the
 * unit-`test` classpath (the suite stays Gradle-free, plan 015/016), so we stand in a
 * same-FQN annotation with RUNTIME retention to drive the reflection walk.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class CacheableTask
