package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.BuildPayload
import java.io.File
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class BuildHoundSettingsPluginFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun setUpProject(extraDsl: String = "") {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("dev.buildhound")
            }

            rootProject.name = "buildhound-fixture"

            buildhound {
                tags.put("team", "mobile")
                $extraDsl
            }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            tasks.register("hello") {
                doLast { println("hello from fixture") }
            }
            """.trimIndent(),
        )
    }

    /** Default runner: inner-build CC mode injected from `buildhound.testkit.cc` (plan 021). */
    private fun runner(vararg arguments: String): GradleRunner =
        runnerExplicit(*arguments, testkitCcFlag())

    /** Escape hatch for tests that pin CC semantics themselves — they pass their own flags. */
    private fun runnerExplicit(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments)

    private fun summaryLine(output: String): String =
        output.lineSequence().single { it.startsWith("[buildhound] build ") }

    private fun readPayload(): BuildPayload {
        val file = File(projectDir, "build/buildhound/build-payload.json")
        assertTrue(file.isFile, "expected payload at $file")
        return BuildHoundJson.payload.decodeFromString(BuildPayload.serializer(), file.readText())
    }

    @Test
    fun `plugin applies cleanly and writes a schema v1 payload`() {
        setUpProject()

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        val payload = readPayload()
        assertEquals(BuildPayload.SCHEMA_VERSION, payload.schemaVersion)
        assertEquals(BuildOutcome.SUCCESS, payload.outcome)
        assertEquals("buildhound-fixture", payload.projectKey)
        assertEquals(listOf("hello"), payload.requestedTasks)
        assertEquals(mapOf("team" to "mobile"), payload.tags)
        assertTrue(payload.tasks.any { it.path == ":hello" }, "expected :hello in ${payload.tasks}")
        assertTrue(payload.finishedAt >= payload.startedAt)
        assertNotNull(payload.environment, "environment block missing")
        assertNotNull(payload.toolchain?.gradle, "toolchain.gradle missing")
        assertNotNull(payload.derived, "derived metrics missing")
    }

    @Test
    fun `payload keeps flowing on configuration cache reuse`() {
        setUpProject()

        runnerExplicit("hello", "--configuration-cache").build()
        val secondRun = runnerExplicit("hello", "--configuration-cache").build()

        assertTrue(summaryLine(secondRun.output).contains("cc=HIT"), summaryLine(secondRun.output))
        assertTrue(readPayload().tasks.isNotEmpty(), "telemetry must survive configuration-cache reuse")
    }

    @Test
    fun `cc state tracks store then hit and disabled`() {
        setUpProject()

        val firstRun = runnerExplicit("hello", "--configuration-cache").build()
        val secondRun = runnerExplicit("hello", "--configuration-cache").build()
        val noCcRun = runnerExplicit("hello", "--no-configuration-cache").build()

        assertTrue(summaryLine(firstRun.output).contains("cc=MISS_STORED"), summaryLine(firstRun.output))
        assertTrue(summaryLine(secondRun.output).contains("cc=HIT"), summaryLine(secondRun.output))
        assertTrue(summaryLine(noCcRun.output).contains("cc=DISABLED"), summaryLine(noCcRun.output))
    }

    @Test
    fun `pseudonymized identity never leaks plaintext`() {
        setUpProject()

        val result = runner("hello").build()

        val username = System.getProperty("user.name")
        val hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        val environment = readPayload().environment
        assertNotNull(environment)
        environment.userId?.let { userId ->
            assertTrue(userId.matches(Regex("u_[0-9a-f]{12}")), "unexpected userId shape: $userId")
        }
        environment.hostnameHash?.let { hash ->
            assertTrue(hash.matches(Regex("h_[0-9a-f]{12}")), "unexpected hostnameHash shape: $hash")
        }
        // Neither the log nor the payload may carry the plaintext identity.
        val payloadText = File(projectDir, "build/buildhound/build-payload.json").readText()
        assertFalse(payloadText.contains("\"$username\""), "plaintext username in payload")
        if (!hostname.isNullOrEmpty()) {
            assertFalse(payloadText.contains("\"$hostname\""), "plaintext hostname in payload")
            assertFalse(result.output.lineSequence().any { it.startsWith("[buildhound]") && it.contains(hostname) })
        }
        assertTrue(
            File(projectDir, ".gradle/buildhound/identity.salt").isFile,
            "expected the interim identity salt to be created",
        )
    }

    @Test
    fun `identity pseudonymize can be disabled via dsl but fingerprints stay salted`() {
        setUpProject(extraDsl = "identity { pseudonymize = false }")

        runner("hello").build()

        val payload = readPayload()
        // Identity is plaintext in this mode…
        assertEquals(System.getProperty("user.name"), payload.environment?.userId)
        // …but input fingerprints are ALWAYS salted (plan 022), so the shared salt is still
        // created here (harmless: identity is already plaintext, and the salt file is gitignored).
        assertTrue(
            File(projectDir, ".gradle/buildhound/identity.salt").isFile,
            "the always-salted fingerprints create the shared salt even in plaintext-identity mode",
        )
        val jdkHome = payload.fingerprints?.build?.get("jdk.home")
        assertTrue(jdkHome.orEmpty().matches(Regex("[0-9a-f]{16}…")), "jdk.home stays a salted hash: $jdkHome")
        assertFalse(jdkHome.orEmpty().contains(System.getProperty("java.home")), "no plaintext path in fingerprints")
    }

    @Test
    fun `vcs is collected from a git working copy`() {
        setUpProject()
        // The build itself creates .gradle/ and build/ — ignore them or the clean run is dirty.
        File(projectDir, ".gitignore").writeText(".gradle/\nbuild/\n")
        exec("git", "init", "--initial-branch=main")
        exec("git", "config", "user.email", "test@example.com")
        exec("git", "config", "user.name", "Test")
        exec("git", "add", ".")
        exec("git", "commit", "-m", "init")

        runnerExplicit("hello", "--configuration-cache").build()
        val clean = readPayload().vcs
        assertEquals("main", clean?.branch)
        assertTrue(clean?.sha.orEmpty().matches(Regex("[0-9a-f]{40}")), "sha: ${clean?.sha}")
        assertEquals(false, clean?.dirty)

        File(projectDir, "untracked.txt").writeText("x")
        // CC-pinned: the dirty rerun must be a CC hit whose freshness comes from re-executing
        // the ValueSource, not a CC miss — so it uses explicit flags, not the harness mode.
        val dirtyRun = runnerExplicit("hello", "--configuration-cache").build()
        assertEquals(true, readPayload().vcs?.dirty)
        // Freshness must come from re-executing the source on reuse, not from a CC miss.
        assertTrue(summaryLine(dirtyRun.output).contains("cc=HIT"))
    }

    @Test
    fun `non git projects degrade to a null vcs block`() {
        setUpProject()

        runner("hello").build()

        val payload = readPayload()
        if (payload.ci == null) {
            assertNull(payload.vcs, "expected no vcs block without git or CI: ${payload.vcs}")
        } else {
            assertNull(payload.vcs?.dirty, "dirty must never come from CI context")
        }
    }

    /**
     * The CCUD-documented hang, reproduced for real: an fsmonitor hook that never
     * answers blocks `git status` (plan 015). A PATH-shadowed fake git cannot simulate
     * this under TestKit — the daemon's native environ keeps the original PATH, so
     * `ProcessBuilder`'s executable lookup ignores `withEnvironment` overrides.
     * The hook script is POSIX shell; CI runs ubuntu.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `hung git degrades gracefully without blocking the build`() {
        setUpProject()
        File(projectDir, ".gitignore").writeText(".gradle/\nbuild/\n")
        exec("git", "init", "--initial-branch=main")
        exec("git", "config", "user.email", "test@example.com")
        exec("git", "config", "user.name", "Test")
        exec("git", "add", ".")
        exec("git", "commit", "-m", "init")
        val hook = File(projectDir, "hung-fsmonitor").apply {
            writeText("#!/bin/sh\nexec sleep 300\n")
            setExecutable(true)
        }
        exec("git", "config", "core.fsmonitor", hook.absolutePath)

        val startedNs = System.nanoTime()
        val result = runner("hello", "-Pbuildhound.vcs.timeout.ms=1000").build()
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        val vcs = readPayload().vcs
        assertEquals("main", vcs?.branch, "probes before the hung one must still deliver")
        assertNotNull(vcs?.sha, "probes before the hung one must still deliver")
        assertNull(vcs?.dirty, "the hung status probe must degrade to null")
        assertEquals(
            1,
            result.output.lineSequence().count { it.contains("[buildhound] git") && it.contains("timed out") },
            "expected exactly one timeout warn line:\n${result.output}",
        )
        // Well under the hook's 300 s sleep — the build must not ride out the hang.
        assertTrue(elapsedMs < 120_000, "build took $elapsedMs ms with a hung git")
    }

    @Test
    fun `generic ci detection resolves mode and fills the ci block`() {
        setUpProject()

        val cleanedEnv = System.getenv().filterKeys { it != "GITHUB_ACTIONS" && it != "TF_BUILD" }
        runner("hello")
            // Fresh daemon: an inherited-env daemon from earlier tests would not see
            // the injected variables (daemon selection ignores env differences).
            .withTestKitDir(File(projectDir, "testkit"))
            .withEnvironment(
                cleanedEnv + mapOf(
                    "BUILDHOUND_CI" to "true",
                    "BUILDHOUND_CI_PROVIDER" to "my-inhouse-ci",
                    "BUILDHOUND_CI_RUN_ID" to "42",
                    "BUILDHOUND_CI_BRANCH" to "main",
                ),
            )
            .build()

        val payload = readPayload()
        assertEquals(BuildMode.CI, payload.mode)
        assertEquals("my-inhouse-ci", payload.ci?.provider)
        assertEquals("42", payload.ci?.runId)
        assertEquals("main", payload.vcs?.branch, "CI context must fill vcs gaps")
    }

    /**
     * Ambient CI markers stripped so only the injected variables steer detection.
     * Extend this list whenever a new built-in provider (and its detection marker)
     * lands, or these tests misdetect on that CI.
     */
    private fun ciNeutralEnv(): Map<String, String> = System.getenv().filterKeys {
        it != "GITHUB_ACTIONS" && it != "TF_BUILD" && it != "CI" && !it.startsWith("BUILDHOUND_")
    }

    @Test
    fun `bare CI variable resolves mode ci with the generic provider`() {
        setUpProject()

        runner("hello")
            // Fresh daemon, same reason as the generic-detection test above.
            .withTestKitDir(File(projectDir, "testkit"))
            .withEnvironment(ciNeutralEnv() + mapOf("CI" to "true"))
            .build()

        val payload = readPayload()
        assertEquals(BuildMode.CI, payload.mode)
        assertEquals("generic", payload.ci?.provider)
        assertNull(payload.ci?.runId, "bare CI detection must not invent fields")
    }

    @Test
    fun `explicit CI=false keeps the build local`() {
        setUpProject()

        runner("hello")
            .withTestKitDir(File(projectDir, "testkit"))
            .withEnvironment(ciNeutralEnv() + mapOf("CI" to "false"))
            .build()

        val payload = readPayload()
        assertEquals(BuildMode.LOCAL, payload.mode)
        assertNull(payload.ci, "CI=false must not produce a ci block")
    }

    @Test
    fun `execution reasons are scrubbed of absolute paths`() {
        setUpProject()
        File(projectDir, "input.txt").writeText("one")
        File(projectDir, "build.gradle.kts").appendText(
            """

            abstract class Sum : DefaultTask() {
                @get:InputFile abstract val source: RegularFileProperty
                @get:OutputFile abstract val target: RegularFileProperty
                @TaskAction fun run() { target.get().asFile.writeText(source.get().asFile.readText()) }
            }
            tasks.register<Sum>("sum") {
                source = layout.projectDirectory.file("input.txt")
                target = layout.buildDirectory.file("out.txt")
            }
            """.trimIndent(),
        )

        runner("sum").build()
        File(projectDir, "input.txt").writeText("two")
        runner("sum").build()

        val reasons = readPayload().tasks.single { it.path == ":sum" }.executionReasons
        assertTrue(reasons.isNotEmpty(), "expected an execution reason for the changed input")
        val absoluteRoot = projectDir.canonicalPath
        for (reason in reasons) {
            assertFalse(reason.contains(absoluteRoot), "absolute path leaked: $reason")
            assertFalse(reason.contains(projectDir.absolutePath), "absolute path leaked: $reason")
        }
        // Relativization must actually happen — not everything collapsing to <path>.
        assertTrue(
            reasons.any { it.contains("input.txt") && !it.contains("/input.txt") },
            "expected a relativized input path in: $reasons",
        )
        // The artifact embeds the same scrubbed payload.
        val html = File(projectDir, "build/buildhound/buildhound-report.html").readText()
        assertFalse(html.contains(absoluteRoot), "absolute path leaked into the artifact")
    }

    @Test
    fun `html artifact is written next to the payload`() {
        setUpProject()

        runner("hello").build()

        val html = File(projectDir, "build/buildhound/buildhound-report.html")
        assertTrue(html.isFile, "expected the standalone report artifact")
        val content = html.readText()
        assertTrue(content.startsWith("<!DOCTYPE html>"), "artifact must stay a full HTML document")
        assertTrue(content.contains(readPayload().buildId), "artifact must embed the payload data")
        // The shared timeline renderer is spliced into the artifact (plan 017).
        assertTrue(content.contains("id=\"timeline\""), "artifact must carry the timeline section")
        assertTrue(content.contains("function buildhoundTimeline"), "artifact must inline the timeline renderer")
        assertFalse(content.contains("__BUILDHOUND_TIMELINE_JS__"), "timeline placeholder must be spliced away")
    }

    @Test
    fun `html artifact can be disabled via dsl`() {
        setUpProject(extraDsl = "htmlReport { enabled = false }")

        runner("hello").build()

        assertTrue(File(projectDir, "build/buildhound/build-payload.json").isFile, "payload still written")
        assertFalse(
            File(projectDir, "build/buildhound/buildhound-report.html").exists(),
            "report must be skippable",
        )
    }

    @Test
    fun `mode disabled writes no payload`() {
        setUpProject(extraDsl = "mode = dev.buildhound.gradle.TelemetryMode.DISABLED")

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertFalse(result.output.contains("[buildhound] build "), result.output)
        assertFalse(File(projectDir, "build/buildhound").exists(), "disabled mode must not write payloads")
        assertFalse(File(projectDir, ".gradle/buildhound").exists(), "disabled mode must not create a salt")
    }

    @Test
    fun `finalization failure never fails the build and leaves a marker`() {
        setUpProject()
        // Occupy the output dir path with a file so the payload write must fail.
        File(projectDir, "build").mkdirs()
        File(projectDir, "build/buildhound").writeText("in the way")

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertTrue(
            result.output.contains("[buildhound] telemetry finalization failed (build unaffected)"),
            result.output,
        )
        assertTrue(
            File(projectDir, "build/buildhound-failure.marker").isFile,
            "expected a failure marker next to the output dir",
        )
    }

    @Test
    fun `enabled false disables collection and salt creation`() {
        setUpProject(extraDsl = "enabled = false")

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        assertFalse(result.output.contains("[buildhound] build "), result.output)
        assertFalse(
            File(projectDir, ".gradle/buildhound").exists(),
            "enabled=false must not touch the identity salt",
        )
        assertFalse(File(projectDir, "build/buildhound").exists())
    }

    // --- Plan 016: task type + cacheable capture, honest hit rate, configurationMs ------

    /**
     * Three task shapes exercising [TaskClassIntrospection]: a `@CacheableTask` with real
     * inputs/outputs (goes UP_TO_DATE on rerun), a `@DisableCachingByDefault(because=…)`
     * task, and a plain `DefaultTask` (itself `@DisableCachingByDefault` upstream).
     */
    private fun setUpTaskTypeProject() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("dev.buildhound")
            }

            rootProject.name = "buildhound-fixture"
            """.trimIndent(),
        )
        File(projectDir, "input.txt").writeText("one")
        File(projectDir, "build.gradle.kts").writeText(
            """
            import org.gradle.work.DisableCachingByDefault

            @CacheableTask
            abstract class CopyFile : DefaultTask() {
                @get:InputFile
                @get:PathSensitive(PathSensitivity.RELATIVE)
                abstract val source: RegularFileProperty
                @get:OutputFile
                abstract val target: RegularFileProperty
                @TaskAction fun run() { target.get().asFile.writeText(source.get().asFile.readText()) }
            }

            @DisableCachingByDefault(because = "no stable output")
            abstract class Ephemeral : DefaultTask() {
                @TaskAction fun run() { println("ephemeral ran") }
            }

            tasks.register<CopyFile>("copyFile") {
                source = layout.projectDirectory.file("input.txt")
                target = layout.buildDirectory.file("copied.txt")
            }
            tasks.register<Ephemeral>("ephemeral")
            tasks.register("plain") { doLast { println("plain ran") } }
            """.trimIndent(),
        )
    }

    private fun BuildPayload.taskAt(path: String) = tasks.single { it.path == path }

    @Test
    fun `task type and cacheable metadata populate and configuration is timed`() {
        setUpTaskTypeProject()

        val store = runnerExplicit("copyFile", "ephemeral", "plain", "--configuration-cache").build()

        assertTrue(summaryLine(store.output).contains("cc=MISS_STORED"), summaryLine(store.output))
        val payload = readPayload()

        val copy = payload.taskAt(":copyFile")
        assertEquals(true, copy.cacheable, "@CacheableTask → cacheable")
        assertTrue(copy.type.orEmpty().contains("CopyFile"), "unexpected type: ${copy.type}")
        assertFalse(copy.type.orEmpty().contains("_Decorated"), "the _Decorated suffix must be stripped: ${copy.type}")
        assertNull(copy.nonCacheableReason)

        val ephemeral = payload.taskAt(":ephemeral")
        assertEquals(false, ephemeral.cacheable)
        assertEquals("no stable output", ephemeral.nonCacheableReason)

        val plain = payload.taskAt(":plain")
        assertEquals(false, plain.cacheable)
        assertEquals("org.gradle.api.DefaultTask", plain.type)

        val configMs = payload.derived?.configurationMs
        assertNotNull(configMs, "configurationMs must be measured on a cold build")
        assertTrue(configMs > 0, "expected a positive configuration duration, got $configMs")
    }

    @Test
    fun `cacheable hit rate ignores non-cacheable work and metadata survives a cc hit`() {
        setUpTaskTypeProject()

        runnerExplicit("copyFile", "ephemeral", "plain", "--configuration-cache").build()
        val hit = runnerExplicit("copyFile", "ephemeral", "plain", "--configuration-cache").build()

        assertTrue(summaryLine(hit.output).contains("cc=HIT"), summaryLine(hit.output))
        assertEquals(TaskOutcome.UP_TO_DATE, hit.task(":copyFile")?.outcome)

        val payload = readPayload()
        // copyFile is the only cacheable task; UP_TO_DATE → 1/1 even though two
        // non-cacheable tasks executed — they must not dilute the denominator.
        assertEquals(1.0, payload.derived?.cacheableHitRate)
        // Metadata replays from the stored service parameters even though configuration
        // (and the whenReady dictionary walk) was skipped on the hit.
        val copy = payload.taskAt(":copyFile")
        assertEquals(true, copy.cacheable)
        assertTrue(copy.type.orEmpty().contains("CopyFile"), "type must replay from CC: ${copy.type}")
        // Configuration was skipped on the hit → reported as 0, not the (absent) marks.
        assertEquals(0L, payload.derived?.configurationMs)
    }

    @Test
    fun `isolated projects degrades task metadata to null without failing the build`() {
        setUpTaskTypeProject()

        // IP implies config cache, so this test pins CC-on explicitly rather than using the
        // harness mode (which could inject --no-configuration-cache).
        val result = runnerExplicit(
            "copyFile", "ephemeral", "plain",
            "--configuration-cache", "-Dorg.gradle.unsafe.isolated-projects=true",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":copyFile")?.outcome)
        assertFalse(
            result.output.contains("[buildhound] task metadata capture failed"),
            "isolated-projects degradation must be silent, not an error:\n${result.output}",
        )
        val payload = readPayload()
        val copy = payload.taskAt(":copyFile")
        assertNull(copy.type, "types must be null under isolated projects")
        assertNull(copy.cacheable, "cacheable must be null under isolated projects")
        assertNull(payload.derived?.cacheableHitRate, "no cacheable flags → null hit rate")
    }

    @Test
    fun `task metadata capture failure degrades to null types without failing the build`() {
        setUpTaskTypeProject()

        val result = runner(
            "copyFile", "ephemeral", "plain",
            "-Pbuildhound.internal.failTaskGraphSnapshot=true",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":copyFile")?.outcome)
        assertEquals(
            1,
            result.output.lineSequence().count { it.contains("[buildhound] task metadata capture failed") },
            "expected exactly one capture-failure warn line:\n${result.output}",
        )
        val payload = readPayload()
        val copy = payload.taskAt(":copyFile")
        assertNull(copy.type, "type must be null when the dictionary is empty")
        assertNull(copy.cacheable)
        assertNull(payload.derived?.cacheableHitRate, "no cacheable flags → null hit rate")
        // The rest of the payload is still assembled and written.
        assertTrue(payload.tasks.size >= 3, "tasks: ${payload.tasks.map { it.path }}")
        assertNotNull(payload.derived)
    }

    // --- Plan 019: cardinality + payload size caps -----------------------------------

    @Test
    fun `pathological tags are capped without failing the build`() {
        val bigValue = "x".repeat(400)
        val manyTags = (1..150).joinToString("\n            ") { "tags.put(\"k$it\", \"v$it\")" }
        setUpProject(extraDsl = "tags.put(\"big\", \"$bigValue\")\n            $manyTags")

        val result = runner("hello").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
        val payload = readPayload()
        // Entry-count cap (team + big + 150 = 152 tags → 100 kept, 52 dropped).
        assertEquals(100, payload.tags.size, "tag map capped to the entry budget")
        assertEquals(300, payload.tags["big"]?.length, "tag value truncated to 300 chars")
        assertNotNull(payload.caps, "caps summary present when something was dropped")
        assertEquals(52, payload.caps?.droppedTags)
        assertEquals(1, payload.caps?.truncatedValues)
        // Counts-only warn line (never the keys or values).
        assertTrue(
            result.output.lineSequence().any { it.contains("[buildhound] payload capped to budget") },
            result.output,
        )
        // The HTML artifact embeds the same capped payload — no un-capped 400-char value.
        val html = File(projectDir, "build/buildhound/buildhound-report.html").readText()
        assertFalse(html.contains(bigValue), "artifact must carry the capped value, not the original")
    }

    private fun exec(vararg command: String) {
        val process = ProcessBuilder(*command).directory(projectDir).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "command ${command.joinToString(" ")} failed:\n$output" }
    }
}
