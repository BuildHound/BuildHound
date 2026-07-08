package dev.buildhound.gradle

import dev.buildhound.commons.payload.BuildMode
import dev.buildhound.commons.payload.BuildOutcome
import dev.buildhound.commons.payload.ConfigurationCacheState
import dev.buildhound.commons.payload.GuhWarmth
import dev.buildhound.commons.payload.PayloadCaps
import dev.buildhound.commons.payload.PropertyOrigin
import dev.buildhound.commons.payload.StartMarker
import dev.buildhound.commons.payload.TaskExecution
import dev.buildhound.commons.payload.TaskOutcome
import dev.buildhound.commons.payload.WrapperDistributionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PayloadAssemblerTest {

    private val ci = CollectedCi(
        provider = "azure-devops",
        pipelineId = "17",
        pipelineName = "android-ci",
        runId = "20260702",
        jobId = "job-1",
        stageId = "Build",
        branch = "feature/x",
        commitSha = "a".repeat(40),
        pullRequestId = "7",
        targetBranch = "main",
        buildUrl = "https://dev.azure.com/acme/mobile/_build/results?buildId=20260702",
    )

    private val benchmark = CollectedBenchmark(scenario = "clean", iteration = 3, isolationMode = "no_build_cache")

    @Test
    fun `mode resolution follows the spec matrix`() {
        assertEquals(BuildMode.CI, PayloadAssembler.resolveMode(TelemetryMode.AUTO, ci, null))
        assertEquals(BuildMode.LOCAL, PayloadAssembler.resolveMode(TelemetryMode.AUTO, null, null))
        assertEquals(BuildMode.CI, PayloadAssembler.resolveMode(TelemetryMode.CI, null, null))
        assertEquals(BuildMode.LOCAL, PayloadAssembler.resolveMode(TelemetryMode.LOCAL, ci, null))
        assertNull(PayloadAssembler.resolveMode(TelemetryMode.DISABLED, ci, null))
    }

    @Test
    fun `an active benchmark forces BENCHMARK over CI-LOCAL but not over DISABLED`() {
        assertEquals(BuildMode.BENCHMARK, PayloadAssembler.resolveMode(TelemetryMode.AUTO, ci, benchmark))
        assertEquals(BuildMode.BENCHMARK, PayloadAssembler.resolveMode(TelemetryMode.CI, ci, benchmark))
        assertEquals(BuildMode.BENCHMARK, PayloadAssembler.resolveMode(TelemetryMode.LOCAL, null, benchmark))
        // DISABLED still short-circuits — a benchmark env never re-enables a disabled build.
        assertNull(PayloadAssembler.resolveMode(TelemetryMode.DISABLED, ci, benchmark))
    }

    @Test
    fun `assembleInterrupted synthesizes a never-finalized payload from a marker`() {
        val marker = StartMarker(
            buildId = "dead-build-id",
            startedAtMs = 1_751_450_000_000,
            mode = BuildMode.CI,
            projectKey = "pilot",
            requestedTasks = listOf("assembleDebug"),
        )
        val payload = PayloadAssembler.assembleInterrupted(marker, projectRoots = emptyList())

        assertEquals("dead-build-id", payload.buildId)
        assertEquals(BuildOutcome.INTERRUPTED, payload.outcome)
        assertEquals(BuildMode.CI, payload.mode)
        assertEquals("pilot", payload.projectKey)
        assertEquals(listOf("assembleDebug"), payload.requestedTasks)
        assertEquals(marker.startedAtMs, payload.startedAt)
        assertEquals(marker.startedAtMs, payload.finishedAt, "finishedAt == startedAt for a lost build")
        assertTrue(payload.tasks.isEmpty())
        assertNull(payload.derived)
        assertNull(payload.caps, "an empty payload is below every cap")
    }

    @Test
    fun `failure detail is populated on a failed build`() {
        val payload = assemble(
            tasks = listOf(task(":app:compileKotlin", 0, 10, TaskOutcome.FAILED)),
            buildFailed = true,
            failure = CollectedFailure(
                exceptionClass = "org.gradle.api.tasks.TaskExecutionException",
                message = "Execution failed for task ':app:compileKotlin'.",
                messageHash = "abc123",
                stackTrace = "org.gradle.api.tasks.TaskExecutionException: boom\n\tat Foo.kt:1",
            ),
        )
        assertEquals(BuildOutcome.FAILED, payload.outcome)
        val failure = payload.failure!!
        assertEquals("org.gradle.api.tasks.TaskExecutionException", failure.exceptionClass)
        assertEquals("abc123", failure.messageHash)
        assertEquals("Execution failed for task ':app:compileKotlin'.", failure.message)
        assertTrue(failure.stackTrace!!.contains("boom"))
    }

    @Test
    fun `a successful build carries no failure detail`() {
        val payload = assemble(tasks = listOf(task(":app:compileKotlin", 0, 10, TaskOutcome.EXECUTED)))
        assertEquals(BuildOutcome.SUCCESS, payload.outcome)
        assertNull(payload.failure)
    }

    @Test
    fun `ci context fills vcs gaps but never dirty`() {
        assertNull(PayloadAssembler.vcsInfo(null, null))

        val fromCi = PayloadAssembler.vcsInfo(CollectedVcs(), ci)
        assertEquals("feature/x", fromCi?.branch)
        assertEquals("a".repeat(40), fromCi?.sha)
        assertNull(fromCi?.dirty)

        val git = PayloadAssembler.vcsInfo(CollectedVcs(branch = "main", sha = "b".repeat(40), dirty = true), ci)
        assertEquals("main", git?.branch)
        assertEquals("b".repeat(40), git?.sha)
        assertEquals(true, git?.dirty)
    }

    @Test
    fun `ci info carries declared fields and puts pr correlation into attributes`() {
        val info = PayloadAssembler.ciInfo(ci)!!

        assertEquals("azure-devops", info.provider)
        assertEquals("20260702", info.runId)
        assertEquals("android-ci", info.pipelineName)
        assertEquals("job-1", info.jobId)
        assertEquals(
            mapOf("pipelineId" to "17", "stageId" to "Build", "pullRequestId" to "7", "targetBranch" to "main"),
            info.attributes,
        )
    }

    @Test
    fun `provider attributes pass through but derived keys win`() {
        val info = PayloadAssembler.ciInfo(
            ci.copy(attributes = mapOf("queue" to "hosted", "pullRequestId" to "spoofed")),
        )!!

        assertEquals("hosted", info.attributes["queue"])
        assertEquals("7", info.attributes["pullRequestId"])
    }

    @Test
    fun `non http build urls are dropped centrally`() {
        assertNull(PayloadAssembler.ciInfo(ci.copy(buildUrl = "javascript:alert(1)"))?.buildUrl)
        assertEquals(ci.buildUrl, PayloadAssembler.ciInfo(ci)?.buildUrl)
    }

    @Test
    fun `agent name never reaches the payload`() {
        // CollectedCi has no agentName field by design (plan 005); pin the payload shape too.
        val json = dev.buildhound.commons.payload.BuildHoundJson.payload.encodeToString(
            dev.buildhound.commons.payload.CiInfo.serializer(),
            PayloadAssembler.ciInfo(ci)!!,
        )
        assertFalse(json.contains("agent", ignoreCase = true), json)
    }

    @Test
    fun `assembles timestamps from tasks with sane fallbacks`() {
        val tasks = listOf(
            task(":a", startMs = 1_000, durationMs = 500, outcome = TaskOutcome.EXECUTED),
            task(":b", startMs = 1_200, durationMs = 2_000, outcome = TaskOutcome.FROM_CACHE),
        )

        val payload = assemble(tasks = tasks, nowMs = 99_999)
        assertEquals(1_000, payload.startedAt)
        assertEquals(3_200, payload.finishedAt)

        val empty = assemble(tasks = emptyList(), nowMs = 99_999)
        assertEquals(99_999, empty.startedAt)
        assertEquals(99_999, empty.finishedAt)
    }

    @Test
    fun `assembles environment toolchain outcome and derived metrics`() {
        val payload = assemble(
            tasks = listOf(
                task(":a", startMs = 0, durationMs = 1_000, outcome = TaskOutcome.EXECUTED, cacheable = true),
                task(":b", startMs = 0, durationMs = 1_000, outcome = TaskOutcome.FROM_CACHE, cacheable = true),
            ),
            buildFailed = true,
            configurationMs = 750,
        )

        assertEquals(BuildOutcome.FAILED, payload.outcome)
        assertEquals("Linux", payload.environment?.os)
        assertEquals(true, payload.environment?.daemonReused)
        assertEquals(ConfigurationCacheState.HIT, payload.environment?.configurationCache)
        assertEquals("8.14.3", payload.toolchain?.gradle)
        assertEquals("21.0.10", payload.toolchain?.jdk)
        assertEquals(0.5, payload.derived?.cacheableHitRate)
        assertEquals(750, payload.derived?.configurationMs)
        assertEquals(mapOf("team" to "mobile"), payload.tags)
        assertEquals("fixture", payload.projectKey)
        assertEquals(listOf("build"), payload.requestedTasks)
    }

    @Test
    fun `detected AGP KGP KSP versions join gradle and jdk in the toolchain block`() {
        val payload = assemble(
            tasks = listOf(task(":a", 0, 1_000, TaskOutcome.EXECUTED)),
            agp = "8.9.0",
            kgp = "2.2.20",
            ksp = "2.2.20-2.0.2",
        )

        assertEquals("8.14.3", payload.toolchain?.gradle)
        assertEquals("21.0.10", payload.toolchain?.jdk)
        assertEquals("8.9.0", payload.toolchain?.agp)
        assertEquals("2.2.20", payload.toolchain?.kgp)
        assertEquals("2.2.20-2.0.2", payload.toolchain?.ksp)
    }

    @Test
    fun `an undetected toolchain leaves AGP KGP KSP null without dropping gradle and jdk`() {
        val payload = assemble(tasks = listOf(task(":a", 0, 1_000, TaskOutcome.EXECUTED)))

        assertEquals("8.14.3", payload.toolchain?.gradle)
        assertNull(payload.toolchain?.agp)
        assertNull(payload.toolchain?.kgp)
        assertNull(payload.toolchain?.ksp)
        assertNull(payload.toolchain?.springBoot)
    }

    @Test
    fun `a detected springBoot version joins the toolchain block (plan 072)`() {
        val withBoot = assemble(tasks = listOf(task(":a", 0, 1_000, TaskOutcome.EXECUTED)), springBoot = "3.3.2")
        assertEquals("3.3.2", withBoot.toolchain?.springBoot)
        assertEquals("8.14.3", withBoot.toolchain?.gradle, "springBoot does not drop gradle/jdk")
        // Reported even without an environment snapshot, like agp/kgp/ksp.
        val noEnv = PayloadAssembler.assemble(
            buildId = "b", projectKey = null, mode = BuildMode.CI, buildFailed = false,
            requestedTasks = emptyList(), tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)),
            environment = null, vcs = null, ci = null,
            configurationCache = ConfigurationCacheState.DISABLED, daemonReused = false,
            tags = emptyMap(), nowMs = 0, projectRoots = emptyList(), springBoot = "3.3.2",
        )
        assertEquals("3.3.2", noEnv.toolchain?.springBoot)
    }

    @Test
    fun `assemble caps an oversized tag value and records the summary`() {
        val payload = assemble(
            tasks = listOf(task(":a", 0, 1_000, TaskOutcome.EXECUTED)),
            tags = mapOf("big" to "v".repeat(400)),
        )

        assertEquals(300, payload.tags["big"]?.length, "value truncated to the default char cap")
        assertEquals(1, payload.caps?.truncatedValues)
    }

    @Test
    fun `derived metrics reflect the full task list even when tasks are truncated`() {
        val tasks = listOf(
            task(":a", 0, 100, TaskOutcome.FROM_CACHE, cacheable = true),
            task(":b", 0, 100, TaskOutcome.EXECUTED, cacheable = true),
            task(":c", 0, 100, TaskOutcome.EXECUTED, cacheable = true),
        )

        val payload = assemble(tasks = tasks, caps = PayloadCaps(maxTasks = 1))

        assertEquals(1, payload.tasks.size, "task array truncated")
        assertEquals(2, payload.caps?.droppedTasks)
        // Hit rate is 1 avoided / 3 cacheable over the FULL list — unshifted by truncation.
        assertEquals(1.0 / 3.0, payload.derived?.cacheableHitRate)
    }

    @Test
    fun `assemble sets fingerprints when populated and omits an empty block`() {
        val fp = dev.buildhound.commons.payload.FingerprintInfo(
            build = mapOf("jdk.home" to "9f86d081884c7d65…"),
            tasks = mapOf(":app:test" to mapOf("sysProps-x" to "2c26b46b68ffc68f…")),
        )
        val populated = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), fingerprints = fp)
        assertEquals("9f86d081884c7d65…", populated.fingerprints?.build?.get("jdk.home"))
        assertEquals("2c26b46b68ffc68f…", populated.fingerprints?.tasks?.get(":app:test")?.get("sysProps-x"))

        // An empty FingerprintInfo (no salt / nothing captured) becomes a null field.
        val empty = assemble(
            tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)),
            fingerprints = dev.buildhound.commons.payload.FingerprintInfo(),
        )
        assertNull(empty.fingerprints)
    }

    @Test
    fun `assemble passes the kotlin report through`() {
        val kotlin = dev.buildhound.commons.payload.KotlinInfo(
            reportSchema = "KOTLIN_2_4",
            perTask = listOf(dev.buildhound.commons.payload.KotlinTaskReport(taskPath = ":app:compileKotlin", durationMs = 1168)),
        )
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), kotlin = kotlin)
        assertEquals("KOTLIN_2_4", payload.kotlin?.reportSchema)
        assertEquals(1168, payload.kotlin?.perTask?.single()?.durationMs)
    }

    @Test
    fun `assemble maps collected processes to ProcessInfo and survives scrubbing`() {
        val processes = listOf(
            CollectedProcess(
                role = dev.buildhound.commons.payload.ProcessRole.GRADLE_DAEMON,
                pid = 41214,
                heapUsedMb = 1462, heapCommittedMb = 2048, heapMaxMb = 4096,
                configuredXmxMb = 4096, gcTimeMs = 3120, rssMb = 2711, uptimeS = 812,
                gcCollector = dev.buildhound.commons.payload.GcCollector.G1,
                compactObjectHeaders = false,
            ),
            CollectedProcess(role = dev.buildhound.commons.payload.ProcessRole.KOTLIN_DAEMON, heapUsedMb = 640),
        )
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), processes = processes)
        assertEquals(2, payload.processes.size)
        val daemon = payload.processes.first { it.role == dev.buildhound.commons.payload.ProcessRole.GRADLE_DAEMON }
        assertEquals(1462, daemon.heapUsedMb)
        assertEquals(4096, daemon.configuredXmxMb)
        assertEquals(3120, daemon.gcTimeMs)
        // Plan 065: pid + the typed-allowlist tuning flags survive assembly + scrubbing unchanged
        // (discrete int/enum/bool — the scrubber has nothing free-form to touch here).
        assertEquals(41214, daemon.pid)
        assertEquals(dev.buildhound.commons.payload.GcCollector.G1, daemon.gcCollector)
        assertEquals(false, daemon.compactObjectHeaders)
        // A field-sparse process (only a role + used) round-trips with the rest null, not dropped.
        val kotlinDaemon = payload.processes.first { it.role == dev.buildhound.commons.payload.ProcessRole.KOTLIN_DAEMON }
        assertEquals(640, kotlinDaemon.heapUsedMb)
        assertNull(kotlinDaemon.configuredXmxMb)
        assertNull(kotlinDaemon.pid)
        assertNull(kotlinDaemon.gcCollector)
        assertNull(kotlinDaemon.compactObjectHeaders)
    }

    @Test
    fun `a pid too large for the wire Int degrades to an honest null, never a wrapped value`() {
        val payload = assemble(
            tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)),
            processes = listOf(
                CollectedProcess(
                    role = dev.buildhound.commons.payload.ProcessRole.GRADLE_DAEMON,
                    pid = Int.MAX_VALUE.toLong() + 1,
                ),
            ),
        )
        assertNull(payload.processes.single().pid)
    }

    @Test
    fun `assemble defaults processes to an empty list`() {
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)))
        assertTrue(payload.processes.isEmpty())
    }

    @Test
    fun `workersMax rides the environment block and survives scrubbing`() {
        val payload = assemble(
            tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)),
            environment = CollectedEnvironment(os = "Linux", workersMax = 8),
        )
        assertEquals(8, payload.environment?.workersMax)
        // Uncaptured stays an honest null (the default assemble() environment has no workersMax).
        assertNull(assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED))).environment?.workersMax)
    }

    @Test
    fun `assemble sets the benchmark block and mirrors its keys into tags, user tags winning`() {
        val payload = assemble(
            tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)),
            benchmark = CollectedBenchmark(scenario = "clean", iteration = 3, isolationMode = "no_build_cache", seedRef = "seed-1"),
            tags = mapOf("scenario" to "user-override", "team" to "mobile"),
        )
        val bench = payload.benchmark ?: error("benchmark block")
        assertEquals("clean", bench.scenario)
        assertEquals(3, bench.iteration)
        assertEquals("no_build_cache", bench.isolationMode)
        assertEquals("seed-1", bench.seedRef)
        // Mirrored into tags, but a user's explicit tag wins the clash (matches ciInfo's merge).
        assertEquals("user-override", payload.tags["scenario"])
        assertEquals("3", payload.tags["iteration"])
        assertEquals("no_build_cache", payload.tags["isolationMode"])
        assertEquals("mobile", payload.tags["team"])
    }

    @Test
    fun `assemble leaves benchmark null and tags unmirrored on a non-benchmark build`() {
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)))
        assertNull(payload.benchmark)
        assertNull(payload.tags["scenario"])
    }

    @Test
    fun `assemble leaves artifacts null when none were collected`() {
        assertNull(assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED))).artifacts)
    }

    @Test
    fun `assemble carries artifacts under the cap unchanged`() {
        val artifacts = listOf(
            dev.buildhound.commons.payload.ArtifactSize("release", ":app", dev.buildhound.commons.payload.ArtifactType.APK, 8000),
            dev.buildhound.commons.payload.ArtifactSize("release", ":lib", dev.buildhound.commons.payload.ArtifactType.AAR, 200),
        )
        val android = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), artifacts = artifacts).artifacts?.android
        assertEquals(artifacts, android)
    }

    @Test
    fun `assemble truncates an over-cap artifacts list largest-first`() {
        // 250 records with ascending sizes; the cap keeps the 200 largest.
        val artifacts = (1..250).map {
            dev.buildhound.commons.payload.ArtifactSize("v$it", ":app", dev.buildhound.commons.payload.ArtifactType.APK, it.toLong())
        }
        val android = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), artifacts = artifacts).artifacts?.android!!
        assertEquals(200, android.size)
        assertEquals(250L, android.maxOf { it.sizeBytes })
        assertEquals(51L, android.minOf { it.sizeBytes }) // smallest kept = 250 - 200 + 1
    }

    @Test
    fun `assemble carries jvm artifacts and leaves android empty when only jvm were collected`() {
        val jvm = listOf(
            dev.buildhound.commons.payload.JvmArtifactSize(":app", dev.buildhound.commons.payload.JvmArtifactKind.BOOT_JAR, 24_117_248),
            dev.buildhound.commons.payload.JvmArtifactSize(":core", dev.buildhound.commons.payload.JvmArtifactKind.JAR, 131_072),
        )
        val artifacts = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), jvmArtifacts = jvm).artifacts
            ?: error("expected an artifacts block when jvm sizes are present")
        assertEquals(jvm, artifacts.jvm)
        assertTrue(artifacts.android.isEmpty(), "a pure-JVM build carries no android artifacts")
    }

    @Test
    fun `assemble emits the artifacts block when android and jvm are both present`() {
        val android = listOf(
            dev.buildhound.commons.payload.ArtifactSize("release", ":app", dev.buildhound.commons.payload.ArtifactType.APK, 8000),
        )
        val jvm = listOf(
            dev.buildhound.commons.payload.JvmArtifactSize(":svc", dev.buildhound.commons.payload.JvmArtifactKind.BOOT_JAR, 9000),
        )
        val artifacts = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), artifacts = android, jvmArtifacts = jvm).artifacts
            ?: error("expected an artifacts block")
        assertEquals(android, artifacts.android)
        assertEquals(jvm, artifacts.jvm)
    }

    @Test
    fun `assemble leaves artifacts null when neither android nor jvm were collected`() {
        assertNull(
            assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), artifacts = emptyList(), jvmArtifacts = emptyList()).artifacts,
        )
    }

    @Test
    fun `assemble truncates an over-cap jvm artifacts list largest-first`() {
        // 250 records with ascending sizes; the cap keeps the 200 largest (mirrors the android cap).
        val jvm = (1..250).map {
            dev.buildhound.commons.payload.JvmArtifactSize(":m$it", dev.buildhound.commons.payload.JvmArtifactKind.JAR, it.toLong())
        }
        val kept = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), jvmArtifacts = jvm).artifacts?.jvm!!
        assertEquals(200, kept.size)
        assertEquals(250L, kept.maxOf { it.sizeBytes })
        assertEquals(51L, kept.minOf { it.sizeBytes }) // smallest kept = 250 - 200 + 1
    }

    @Test
    fun `assemble leaves projectEvaluations null when none were collected`() {
        assertNull(assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED))).projectEvaluations)
    }

    @Test
    fun `assemble ranks projectEvaluations slowest-first regardless of input order`() {
        val evaluations = listOf(
            dev.buildhound.commons.payload.ProjectEvaluation(":fast", 100),
            dev.buildhound.commons.payload.ProjectEvaluation(":slow", 4200),
            dev.buildhound.commons.payload.ProjectEvaluation(":mid", 900),
        )
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), projectEvaluations = evaluations)
        assertEquals(listOf(":slow", ":mid", ":fast"), payload.projectEvaluations?.map { it.path })
    }

    @Test
    fun `assemble truncates an over-cap projectEvaluations list slowest-first`() {
        val evaluations = (1..10).map { dev.buildhound.commons.payload.ProjectEvaluation(":m$it", it.toLong()) }
        val payload = assemble(
            tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)),
            projectEvaluations = evaluations,
            caps = PayloadCaps(maxProjectEvaluations = 3),
        )
        val kept = payload.projectEvaluations ?: error("expected a capped projectEvaluations list")
        assertEquals(3, kept.size)
        assertEquals(listOf(10L, 9L, 8L), kept.map { it.evaluationMs })
        assertEquals(7, payload.caps?.droppedProjectEvaluations)
    }

    @Test
    fun `assemble maps a collected build-structure onto BuildStructureInfo`() {
        val structure = CollectedBuildStructure(
            projectCount = 12,
            maxDepth = 3,
            includedBuildCount = 1,
            buildSrcPresent = true,
            sourcesInRoot = false,
            emptyIntermediateCandidates = listOf(":libs:legacy"),
        )
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), buildStructure = structure)

        val info = payload.buildStructure ?: error("expected a buildStructure block")
        assertEquals(12, info.projectCount)
        assertEquals(3, info.maxDepth)
        assertEquals(1, info.includedBuildCount)
        assertEquals(true, info.buildSrcPresent)
        assertEquals(false, info.sourcesInRoot)
        assertEquals(listOf(":libs:legacy"), info.emptyIntermediateCandidates)
    }

    @Test
    fun `assemble folds a build-structure ValueSource-side drop count into the caps summary`() {
        // The MAX_EMPTY_INTERMEDIATE_CANDIDATES cap is enforced inside BuildStructureValueSource
        // itself, at collection time — not by PayloadCapper — so its overflow count arrives here
        // pre-computed on the collected DTO (plan 069 review) rather than being derived from an
        // over-long emptyIntermediateCandidates list.
        val structure = CollectedBuildStructure(
            projectCount = 600,
            maxDepth = 1,
            includedBuildCount = 0,
            buildSrcPresent = false,
            sourcesInRoot = false,
            emptyIntermediateCandidates = listOf(":libs:legacy"),
            droppedEmptyIntermediateCandidates = 37,
        )
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), buildStructure = structure)

        assertEquals(37, payload.caps?.droppedEmptyIntermediateCandidates)
        // The candidate list itself is untouched here — BuildStructureValueSource already truncated
        // it before assembly; PayloadAssembler only surfaces the count it was told about.
        assertEquals(listOf(":libs:legacy"), payload.buildStructure?.emptyIntermediateCandidates)
    }

    @Test
    fun `assemble leaves caps null when the build-structure drop count is zero`() {
        val structure = CollectedBuildStructure(
            projectCount = 2,
            maxDepth = 1,
            includedBuildCount = 0,
            emptyIntermediateCandidates = listOf(":libs"),
        )
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), buildStructure = structure)

        assertNull(payload.caps, "nothing was dropped, so no caps summary should be attached")
    }

    @Test
    fun `assemble leaves buildStructure null when nothing was captured`() {
        assertNull(assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED))).buildStructure)
        // An all-null/empty capture (a guarded failure degraded every dimension) reports the same
        // as "uncaptured" — never a half-populated block.
        val degraded = assemble(
            tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)),
            buildStructure = CollectedBuildStructure(),
        )
        assertNull(degraded.buildStructure)
    }

    @Test
    fun `assemble maps a collected wrapper onto WrapperInfo with COLD warmth when the dist was unpacked around this daemon's own start`() {
        val wrapper = CollectedWrapper(
            variant = WrapperDistributionType.BIN,
            distributionSha256Pinned = true,
            wrapperJarSha256 = "a".repeat(64),
            distMtimeMs = 1_000_500,
            jarMtimeMs = 100,
            distPresent = true,
            jvmStartMs = 1_000_000,
        )
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), wrapper = wrapper)

        val info = payload.wrapper ?: error("expected a wrapper block")
        assertEquals(WrapperDistributionType.BIN, info.distributionVariant)
        assertEquals(true, info.distributionSha256Pinned)
        assertEquals("a".repeat(64), info.wrapperJarSha256)
        assertEquals(GuhWarmth.COLD, info.guhWarmth)
    }

    @Test
    fun `assemble reports WARM when the dist predates this daemon's own JVM start by more than the fresh window`() {
        val wrapper = CollectedWrapper(
            variant = WrapperDistributionType.ALL,
            distPresent = true,
            distMtimeMs = 0,
            jvmStartMs = GuhWarmth.FRESH_WINDOW_MS + 1_000,
        )
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), wrapper = wrapper)

        assertEquals(GuhWarmth.WARM, payload.wrapper?.guhWarmth)
    }

    @Test
    fun `assemble reports UNKNOWN warmth but still ships variant and pinned when the dist is absent`() {
        // System/IDE Gradle (no wrapper dist under GUH): variant/pinned still describe the
        // committed wrapper config; warmth is honestly UNKNOWN, never guessed.
        val wrapper = CollectedWrapper(
            variant = WrapperDistributionType.CUSTOM,
            distributionSha256Pinned = false,
            distPresent = false,
        )
        val payload = assemble(tasks = listOf(task(":a", 500, 1, TaskOutcome.EXECUTED)), wrapper = wrapper)

        val info = payload.wrapper ?: error("expected a wrapper block")
        assertEquals(WrapperDistributionType.CUSTOM, info.distributionVariant)
        assertEquals(false, info.distributionSha256Pinned)
        assertEquals(GuhWarmth.UNKNOWN, info.guhWarmth)
    }

    @Test
    fun `assemble still classifies warmth on a task-less build (the anchor is JVM start, not task timing)`() {
        // Superseding an earlier design draft that compared the dist mtime against the first
        // task's startMs: the wrapper's bootstrap unpacks the dist before this JVM launches, before
        // configuration, before any task — so no in-build task timestamp can ever observe COLD, and
        // a task-less build is no longer a special case at all once the anchor is this daemon's own
        // JVM start (always available, independent of tasks).
        val wrapper = CollectedWrapper(
            variant = WrapperDistributionType.BIN,
            distPresent = true,
            distMtimeMs = 1_000,
            jvmStartMs = 1_200,
        )
        val payload = assemble(tasks = emptyList(), wrapper = wrapper)

        assertEquals(GuhWarmth.COLD, payload.wrapper?.guhWarmth)
    }

    @Test
    fun `assemble leaves wrapper null when nothing was captured`() {
        assertNull(assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED))).wrapper)
        // An all-null/unknown capture (every probe degraded) reports the same as "uncaptured".
        val degraded = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), wrapper = CollectedWrapper())
        assertNull(degraded.wrapper)
    }

    @Test
    fun `assemble maps isolatedProjects onto the environment block, defaulting to null when uncaptured`() {
        val active = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), isolatedProjects = true)
        assertEquals(true, active.environment?.isolatedProjects)

        val uncaptured = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)))
        assertNull(uncaptured.environment?.isolatedProjects)
    }

    @Test
    fun `assemble passes test results through and scrubs the failure message`() {
        val tests = listOf(
            dev.buildhound.commons.payload.TestTaskResult(
                taskPath = ":app:test",
                module = ":app",
                failedOrRetried = listOf(
                    dev.buildhound.commons.payload.TestCaseDetail(
                        className = "com.example.FooTest",
                        name = "reads()",
                        message = "missing /home/ci/agent/work/project/src/x.txt",
                    ),
                ),
            ),
        )
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), tests = tests)
        assertEquals(":app:test", payload.tests.single().taskPath)
        // projectRoots is emptyList in this helper, so only the absolute-path rule applies: the
        // out-of-declared-root path collapses to <path> (scrub still runs on test messages).
        assertEquals("missing <path>", payload.tests.single().failedOrRetried.single().message)
    }

    @Test
    fun `scrub runs before cap so a boundary secret is redacted not sliced`() {
        // A shape-matched secret (AWS access key = AKIA + exactly 16 chars) only matches the
        // scrubber WHOLE. It straddles the 500-char reason cap: scrub-then-cap redacts it and
        // "<redacted>" fits under the budget; cap-then-scrub would slice it to "AKIA…" (14 chars,
        // no longer the fixed-width shape), which the scrubber then misses and the fragment
        // leaks. So this fixture fails if the order is ever flipped — unlike a keyed secret,
        // whose truncated "token=…" prefix the scrubber would still redact.
        val awsKey = "AKIA" + "ABCDEFGHIJKLMNOP" // AKIA + 16 uppercase chars = valid shape
        val reason = "a".repeat(485) + " " + awsKey
        val payload = assemble(
            tasks = listOf(task(":a", 0, 1_000, TaskOutcome.EXECUTED, reasons = listOf(reason))),
            caps = PayloadCaps(maxReasonChars = 500),
        )

        val scrubbed = payload.tasks.single().executionReasons.single()
        assertTrue(scrubbed.contains("<redacted>"), "the whole key must be redacted: $scrubbed")
        assertFalse(scrubbed.contains("AKIA"), "no key fragment may survive: $scrubbed")
    }

    @Test
    fun `ide and agent detection map into the environment block`() {
        val env = CollectedEnvironment(os = "Linux", ide = "IntelliJ IDEA", ideVersion = "2024.2", ideSync = true, aiAgent = "Claude Code")
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), environment = env)
        assertEquals("IntelliJ IDEA", payload.environment?.ide)
        assertEquals("2024.2", payload.environment?.ideVersion)
        assertEquals(true, payload.environment?.ideSync)
        assertEquals("Claude Code", payload.environment?.aiAgent)
    }

    @Test
    fun `invocation posture maps onto environment invocation`() {
        val invocation = CollectedInvocation(
            buildCacheEnabled = true,
            offline = false,
            rerunTasks = true,
            refreshDependencies = false,
            configureOnDemand = false,
            maxWorkerCount = 8,
            parallel = true,
            fileEncoding = "UTF-8",
            locale = "en-US",
            properties = listOf(
                CollectedPropertyPosture("org.gradle.caching", "true", PropertyOrigin.GRADLE_USER_HOME),
            ),
        )
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), invocation = invocation)

        val info = payload.environment?.invocation ?: error("expected environment.invocation")
        assertEquals(true, info.buildCacheEnabled)
        assertEquals(true, info.rerunTasks)
        assertEquals(8, info.maxWorkerCount)
        assertEquals("UTF-8", info.fileEncoding)
        assertEquals("en-US", info.locale)
        val posture = info.properties.single()
        assertEquals("org.gradle.caching", posture.key)
        assertEquals("true", posture.value)
        assertEquals(PropertyOrigin.GRADLE_USER_HOME, posture.origin)
    }

    @Test
    fun `no invocation source leaves environment invocation null`() {
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)))
        assertNull(payload.environment?.invocation)
    }

    @Test
    fun `links compose from the redacted remote, sha, and the ci pr number`() {
        val vcs = CollectedVcs(branch = "feature", sha = "a".repeat(40), remoteUrl = "https://github.com/org/repo.git")
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), vcs = vcs)
        assertEquals("https://github.com/org/repo.git", payload.vcs?.remoteUrl)
        assertEquals("https://github.com/org/repo/commit/${"a".repeat(40)}", payload.links?.commitUrl)
        // The fixture ci carries pullRequestId "7".
        assertEquals("https://github.com/org/repo/pull/7", payload.links?.pullRequestUrl)
    }

    @Test
    fun `links are null without a supported remote`() {
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), vcs = CollectedVcs(sha = "a".repeat(40)))
        assertNull(payload.links)
    }

    @Test
    fun `ci attributes carry run attempt through`() {
        val info = PayloadAssembler.ciInfo(ci.copy(attributes = mapOf("runAttempt" to "3")))!!
        assertEquals("3", info.attributes["runAttempt"])
    }

    // --- Addon extensions (plan 039) ---

    @Test
    fun `extensions round-trip onto the payload and survive scrubbing untouched`() {
        // A value that the scrubber WOULD redact in a declared free-text field — proving core treats
        // addon JSON as opaque (it never deep-scrubs it; the addon owns the §3.7 bar).
        val ext = mapOf(
            "testQuarantine" to kotlinx.serialization.json.buildJsonObject {
                put("schemaVersion", kotlinx.serialization.json.JsonPrimitive(1))
                put("secretish", kotlinx.serialization.json.JsonPrimitive("ghp_0123456789abcdefABCDEF0123456789abcd"))
            },
        )
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)), extensions = ext)
        assertEquals(setOf("testQuarantine"), payload.extensions.keys)
        assertEquals(ext, payload.extensions, "opaque addon JSON passes through the scrubber unchanged")
    }

    @Test
    fun `no contributed extensions leaves an empty map`() {
        val payload = assemble(tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)))
        assertTrue(payload.extensions.isEmpty())
    }

    @Test
    fun `the extensions byte budget drops the largest entry and keeps the envelope plus smaller entries`() {
        val ext = mapOf(
            "big" to kotlinx.serialization.json.JsonPrimitive("x".repeat(500)),
            "small" to kotlinx.serialization.json.JsonPrimitive("y"),
        )
        val payload = assemble(
            tasks = listOf(task(":a", 0, 1, TaskOutcome.EXECUTED)),
            caps = PayloadCaps(maxExtensionsBytes = 64),
            extensions = ext,
        )
        assertEquals(setOf("small"), payload.extensions.keys, "largest-first drop keeps the small entry")
        assertEquals(1, payload.caps?.droppedExtensions)
        // The build envelope always survives a cap.
        assertEquals("test-build", payload.buildId)
        assertEquals(BuildOutcome.SUCCESS, payload.outcome)
    }

    private fun assemble(
        tasks: List<TaskExecution>,
        buildFailed: Boolean = false,
        failure: CollectedFailure? = null,
        nowMs: Long = 0,
        configurationMs: Long? = null,
        tags: Map<String, String> = mapOf("team" to "mobile"),
        caps: dev.buildhound.commons.payload.PayloadCaps = dev.buildhound.commons.payload.PayloadCaps.DEFAULT,
        fingerprints: dev.buildhound.commons.payload.FingerprintInfo? = null,
        kotlin: dev.buildhound.commons.payload.KotlinInfo? = null,
        tests: List<dev.buildhound.commons.payload.TestTaskResult> = emptyList(),
        processes: List<CollectedProcess> = emptyList(),
        benchmark: CollectedBenchmark? = null,
        artifacts: List<dev.buildhound.commons.payload.ArtifactSize> = emptyList(),
        jvmArtifacts: List<dev.buildhound.commons.payload.JvmArtifactSize> = emptyList(),
        projectEvaluations: List<dev.buildhound.commons.payload.ProjectEvaluation> = emptyList(),
        buildStructure: CollectedBuildStructure? = null,
        isolatedProjects: Boolean? = null,
        wrapper: CollectedWrapper? = null,
        environment: CollectedEnvironment = CollectedEnvironment(
            os = "Linux", arch = "amd64", cores = 8, ramMb = 16_000,
            hostnameHash = "h_0123456789ab", userId = "u_0123456789ab",
            gradleVersion = "8.14.3", jdkVersion = "21.0.10",
        ),
        invocation: CollectedInvocation? = null,
        vcs: CollectedVcs = CollectedVcs(branch = "main", sha = "c".repeat(40), dirty = false),
        ci: CollectedCi? = this.ci,
        extensions: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
        agp: String? = null,
        kgp: String? = null,
        ksp: String? = null,
        springBoot: String? = null,
    ) = PayloadAssembler.assemble(
        buildId = "test-build",
        projectKey = "fixture",
        mode = BuildMode.CI,
        buildFailed = buildFailed,
        failure = failure,
        requestedTasks = listOf("build"),
        tasks = tasks,
        environment = environment,
        invocation = invocation,
        vcs = vcs,
        ci = ci,
        configurationCache = ConfigurationCacheState.HIT,
        daemonReused = true,
        tags = tags,
        nowMs = nowMs,
        projectRoots = emptyList(),
        configurationMs = configurationMs,
        caps = caps,
        fingerprints = fingerprints,
        kotlin = kotlin,
        tests = tests,
        processes = processes,
        benchmark = benchmark,
        artifacts = artifacts,
        jvmArtifacts = jvmArtifacts,
        projectEvaluations = projectEvaluations,
        extensions = extensions,
        agp = agp,
        kgp = kgp,
        ksp = ksp,
        springBoot = springBoot,
        buildStructure = buildStructure,
        isolatedProjects = isolatedProjects,
        wrapper = wrapper,
    )

    private fun task(
        path: String,
        startMs: Long,
        durationMs: Long,
        outcome: TaskOutcome,
        cacheable: Boolean? = null,
        reasons: List<String> = emptyList(),
    ) = TaskExecution(
        path = path, startMs = startMs, durationMs = durationMs, outcome = outcome,
        cacheable = cacheable, executionReasons = reasons,
    )
}
