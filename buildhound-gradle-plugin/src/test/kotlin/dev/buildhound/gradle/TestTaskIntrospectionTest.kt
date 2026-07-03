package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestTaskIntrospectionTest {

    private class MyUnitTest : org.gradle.api.tasks.testing.Test()

    @Test
    fun `a direct Test subclass is a test task`() {
        assertTrue(TestTaskIntrospection.isTestTask(MyUnitTest::class.java))
    }

    @Test
    fun `the exact Test class is a test task`() {
        assertTrue(TestTaskIntrospection.isTestTask(org.gradle.api.tasks.testing.Test::class.java))
    }

    @Test
    fun `a generated _Decorated subclass is a test task via its stripped name`() {
        assertTrue(TestTaskIntrospection.isTestTask(org.gradle.api.tasks.testing.Test_Decorated::class.java))
    }

    @Test
    fun `a non-test class is not a test task`() {
        assertFalse(TestTaskIntrospection.isTestTask(String::class.java))
        assertFalse(TestTaskIntrospection.isTestTask(TestTaskIntrospectionTest::class.java))
    }
}
