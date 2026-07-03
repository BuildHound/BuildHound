package org.gradle.work

/**
 * Unit-test double for `org.gradle.work.DisableCachingByDefault` (see the sibling
 * `org.gradle.api.tasks.CacheableTask` double). Carries the real `because` member so the
 * reflective reader in [dev.buildhound.gradle.TaskClassIntrospection] can be exercised.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class DisableCachingByDefault(val because: String = "")
