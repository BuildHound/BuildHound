package dev.buildhound.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure path→module mapping (plan 063). The load-bearing attribution gate, so it is pinned with
 * adversarial + boundary cases: segment-safe prefixes (the `app` vs `app-core` trap), nested modules,
 * root-owned files, the empty/partial index that drives `unattributedChanges`, and blank-line hygiene.
 */
class ChangedModuleMapperTest {

    // relDir -> gradlePath (the shape ChangedModulesValueSource inverts the walk's map into).
    private val index = mapOf(
        "" to ":",
        "app" to ":app",
        "app-core" to ":app-core",
        "core/common" to ":core:common",
        "core" to ":core",
    )

    @Test
    fun `a file under a subproject dir attributes to that module`() {
        val mapped = ChangedModuleMapper.map(index, listOf("app/src/main/kotlin/Foo.kt"))
        assertEquals(listOf(":app"), mapped.modules)
        assertFalse(mapped.unattributedChanges)
    }

    @Test
    fun `the deepest owning module wins for a nested subproject`() {
        // "core/common/..." must attribute to :core:common, not the shallower :core.
        val mapped = ChangedModuleMapper.map(index, listOf("core/common/src/Bar.kt"))
        assertEquals(listOf(":core:common"), mapped.modules)
    }

    @Test
    fun `a file directly under a shallower module stays with that module`() {
        val mapped = ChangedModuleMapper.map(index, listOf("core/build.gradle.kts"))
        assertEquals(listOf(":core"), mapped.modules)
    }

    @Test
    fun `prefix matching is segment-safe — app-core never attributes to app`() {
        // Naive startsWith("app") would misattribute "app-core/..." to :app; the "/"-boundary guard
        // keeps it on :app-core (the plan's named adversarial case).
        val mapped = ChangedModuleMapper.map(index, listOf("app-core/src/Baz.kt"))
        assertEquals(listOf(":app-core"), mapped.modules)
    }

    @Test
    fun `root-level build files attribute to the root project`() {
        // A root build file / version catalog has no deeper subproject prefix → ":" (whole-build radius).
        val mapped = ChangedModuleMapper.map(
            index,
            listOf("build.gradle.kts", "gradle/libs.versions.toml", "settings.gradle.kts"),
        )
        assertEquals(listOf(":"), mapped.modules)
        assertFalse(mapped.unattributedChanges, "root legitimately owns non-subproject paths — not unattributed")
    }

    @Test
    fun `the distinct sorted module set is emitted for a multi-module change`() {
        val mapped = ChangedModuleMapper.map(
            index,
            listOf("core/common/A.kt", "app/B.kt", "core/common/C.kt", "app/D.kt"),
        )
        assertEquals(listOf(":app", ":core:common"), mapped.modules, "distinct + sorted")
    }

    @Test
    fun `an exact match on a module dir path attributes to that module`() {
        val mapped = ChangedModuleMapper.map(index, listOf("app"))
        assertEquals(listOf(":app"), mapped.modules)
    }

    @Test
    fun `an empty index flags every change unattributed and emits no module`() {
        // The descriptor walk failed (no root entry either) while git diff succeeded — honest degraded.
        val mapped = ChangedModuleMapper.map(emptyMap(), listOf("app/src/Foo.kt", "core/Bar.kt"))
        assertTrue(mapped.modules.isEmpty())
        assertTrue(mapped.unattributedChanges)
    }

    @Test
    fun `a partial index attributes what it can and flags the rest unattributed`() {
        // Root absent, only :app known: an :app file maps, everything else is unattributed.
        val partial = mapOf("app" to ":app")
        val mapped = ChangedModuleMapper.map(partial, listOf("app/Foo.kt", "docs/README.md"))
        assertEquals(listOf(":app"), mapped.modules)
        assertTrue(mapped.unattributedChanges, "a non-app path with no root entry is unattributed")
    }

    @Test
    fun `blank and whitespace-only lines are ignored`() {
        val mapped = ChangedModuleMapper.map(index, listOf("", "   ", "app/Foo.kt"))
        assertEquals(listOf(":app"), mapped.modules)
        assertFalse(mapped.unattributedChanges)
    }

    @Test
    fun `no changed files yields an empty, non-degraded result`() {
        val mapped = ChangedModuleMapper.map(index, emptyList())
        assertTrue(mapped.modules.isEmpty())
        assertFalse(mapped.unattributedChanges, "a resolvable base with no changes is honest, not degraded")
    }
}
