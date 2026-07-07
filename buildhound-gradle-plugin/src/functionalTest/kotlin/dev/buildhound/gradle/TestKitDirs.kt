package dev.buildhound.gradle

import java.io.File
import java.nio.file.Files
import org.gradle.testkit.runner.GradleRunner

/**
 * Points the runner at a per-call-unique TestKit dir that lives **outside** the JUnit
 * `@TempDir` project dir (plan 049).
 *
 * The TestKit daemon lingers after `.build()` and keeps files open under its working dir. When
 * that dir sat inside `projectDir` (the `@TempDir`), JUnit's post-test `@TempDir` deletion hit
 * the daemon's open handles and threw `TempDirDeletionStrategy$DeletionException` on macOS and
 * Windows (Linux's POSIX unlink is immune). Relocating the dir keeps `projectDir` free of
 * daemon-held files, so its deletion always succeeds.
 *
 * A **fresh** (unique-every-call) dir is deliberate: these tests inject env via
 * `withEnvironment`, and TestKit daemon selection ignores env differences — a reused daemon
 * would serve a stale environment (or, across a rebuild, a stale plugin classpath). A unique dir
 * forces a new daemon that re-reads the test's injected env.
 */
internal fun GradleRunner.freshDaemon(): GradleRunner = withTestKitDir(newTestKitDir())

/**
 * A fresh TestKit dir under `buildhound.testkit.root` (set by the `functionalTest` task to the
 * module `build/` dir so `clean` reclaims the lingering daemons), falling back to the JVM temp
 * dir when the property is absent.
 */
private fun newTestKitDir(): File {
    val root = File(System.getProperty("buildhound.testkit.root") ?: System.getProperty("java.io.tmpdir"))
    root.mkdirs()
    return Files.createTempDirectory(root.toPath(), "testkit-").toFile()
}
