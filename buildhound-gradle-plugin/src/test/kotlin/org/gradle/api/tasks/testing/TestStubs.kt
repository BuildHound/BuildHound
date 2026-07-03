package org.gradle.api.tasks.testing

/**
 * Test-only stubs standing in for Gradle's `Test` task class. The plugin's plain `test` source
 * set has no `gradleApi()` on its classpath, so `TestTaskIntrospection` (which matches by
 * fully-qualified name, not by symbol) can be unit-tested against a class that genuinely carries
 * the `org.gradle.api.tasks.testing.Test` FQN — no Gradle dependency required.
 */
open class Test

/** Mimics Gradle's generated `_Decorated` runtime subclass whose stripped name is the Test FQN. */
class Test_Decorated : Test()
