// Smoke harness for dashboard.js: minimal DOM stub, canned fetch responses, then drive
// every view + detail routing + the plan-018 empty/ledger states and assert no exception
// escapes (the plan-006 lesson: string assertions miss SyntaxErrors and render bugs).
"use strict";
const fs = require("fs");
const vm = require("vm");

function makeNode(tag) {
    const node = {
        tag,
        children: [],
        listeners: {},
        attrs: {},
        hidden: false,
        value: "",
        set textContent(v) { node._text = String(v); node.children = []; },
        get textContent() { return node._text || ""; },
        set className(v) { node._class = v; },
        get className() { return node._class || ""; },
        append(...kids) { node.children.push(...kids); },
        appendChild(kid) { node.children.push(kid); return kid; },
        setAttribute(k, v) { node.attrs[k] = v; },
        addEventListener(type, fn) { (node.listeners[type] = node.listeners[type] || []).push(fn); },
    };
    return node;
}

const byId = {};
for (const id of ["app", "token-bar", "token-save", "token-input"]) byId[id] = makeNode("div");

const documentStub = {
    createElement: makeNode,
    createElementNS: (ns, tag) => makeNode(tag),
    createDocumentFragment: () => makeNode("fragment"),
    getElementById: id => byId[id] || null,
};

// Recursive DOM-stub search helpers.
function findAll(node, pred, out) {
    out = out || [];
    if (!node || typeof node !== "object") return out;
    if (pred(node)) out.push(node);
    for (const child of node.children || []) findAll(child, pred, out);
    return out;
}
const countTag = (node, tag) => findAll(node, n => n.tag === tag).length;
const findTag = (node, tag) => findAll(node, n => n.tag === tag);
const hasText = (node, sub) => findAll(node, n => (n.textContent || "").indexOf(sub) >= 0).length > 0;
const hasExact = (node, text) => findAll(node, n => (n.textContent || "") === text).length > 0;
const hasClass = (node, cls) => findAll(node, n => (n.className || "").indexOf(cls) >= 0).length > 0;
function clickButton(node, label) {
    const button = findTag(node, "button").find(b => (b.textContent || "") === label);
    if (!button || !button.listeners.click) throw new Error("button not found: " + label);
    button.listeners.click[0]();
}

const store = {};
const responses = {
    "/v1/builds?limit=50&offset=0": [
        { buildId: "b1", startedAt: 1751450000000, durationMs: 65000, outcome: "SUCCESS", mode: "CI", branch: "main", hitRate: 0.5 },
        { buildId: "b2", startedAt: 1751450100000, durationMs: 900, outcome: "FAILED", mode: "LOCAL", branch: null },
        { buildId: "t1", startedAt: 1751450200000, durationMs: 12000, outcome: "SUCCESS", mode: "CI", branch: "main" },
    ],
    "/v1/builds?branch=none&limit=50&offset=0": [],
    "/v1/builds/b1": {
        buildId: "b1", projectKey: "pilot", startedAt: 1751450000000, finishedAt: 1751450065000,
        outcome: "SUCCESS", mode: "CI",
        tasks: [
            { path: ":a", module: ":a", outcome: "EXECUTED", durationMs: 10, incremental: true },
            { path: ":b", module: ":b", outcome: "FROM_CACHE", durationMs: 500 },
        ],
        derived: { cacheableHitRate: 0.75 },
        environment: { configurationCache: "HIT" },
        vcs: { branch: "main", sha: "abcdef0123456789" },
        // JDK 24 (plan-065 review fix): needed so the compact-object-headers tuning card has a
        // qualifying jdkMajor to fire against below.
        toolchain: { jdk: "24.0.1" },
        kotlin: {
            reportSchema: "KOTLIN_2_4",
            truncatedTasks: 2,
            perTask: [
                { taskPath: ":app:compileKotlin", durationMs: 1168, incremental: false, nonIncrementalReasons: ["UNKNOWN_CHANGES_IN_GRADLE_INPUTS"], compilerTimesMs: { RUN_COMPILATION: 190, COMPILER_INITIALIZATION: 22 }, linesOfCode: 42 },
                { taskPath: ":lib:compileKotlin", durationMs: 300, incremental: true, compilerTimesMs: { RUN_COMPILATION: 80 } },
            ],
        },
        processes: [
            // compactObjectHeaders: true so this calm daemon does NOT also trip the compact-headers
            // card once toolchain.jdk (above) makes jdkMajor 24-qualifying — that card is exercised
            // in isolation by the high-GC Kotlin daemon row below.
            { role: "GRADLE_DAEMON", heapUsedMb: 1462, heapCommittedMb: 2048, heapMaxMb: 4096, configuredXmxMb: 4096, gcTimeMs: 3120, rssMb: 2711, uptimeS: 812, pid: 41214, gcCollector: "G1", compactObjectHeaders: true },
            // Pinned at 1900/2048 ≈ 93 % → the plan-065 kotlin.daemon.jvmargs tuning card fires.
            { role: "KOTLIN_DAEMON", heapUsedMb: 1900, configuredXmxMb: 2048 },
            // High-GC-fraction G1 Kotlin daemon on JDK 24 (plan-065 review fix): gcTimeMs 4000 /
            // uptimeS 20 → 20 % lifetime GC fraction, tripping the GC-pressure card and (since
            // gcCollector is G1) the ParallelGC-trial card; rssMb 3072 with compactObjectHeaders
            // false trips the compact-object-headers card. Previously CI-unverified.
            { role: "KOTLIN_DAEMON", heapUsedMb: 800, configuredXmxMb: 4096, gcTimeMs: 4000, uptimeS: 20, rssMb: 3072, pid: 55832, gcCollector: "G1", compactObjectHeaders: false },
        ],
        tests: [
            {
                taskPath: ":app:testDebugUnitTest", module: ":app", durationMs: 44000, truncatedClasses: 3,
                classes: [
                    { className: "com.example.CartTest", passed: 11, failed: 1, skipped: 0, durationMs: 3200 },
                    { className: "com.example.CheckoutTest", passed: 8, failed: 0, skipped: 1, durationMs: 1500 },
                ],
                failedOrRetried: [
                    { className: "com.example.CartTest", name: "totalsWithDiscount()", outcomes: ["FAILED"], durationMs: 120, message: "expected: <42> but was: <41>" },
                    { className: "com.example.CheckoutTest", name: "flakyGateway()", outcomes: ["FAILED", "PASSED"], durationMs: 640, message: "connection reset" },
                ],
            },
        ],
    },
    // Missing-optional-keys build: everything the schema allows to be absent, absent.
    "/v1/builds/b2": { buildId: "b2", startedAt: 1, finishedAt: 2, outcome: "FAILED", mode: "LOCAL" },
    // Lost build (plan 033): a never-finalized INTERRUPTED build — empty tasks, synthetic zero duration.
    "/v1/builds/int-1": { buildId: "int-1", startedAt: 1751450000000, finishedAt: 1751450000000, outcome: "INTERRUPTED", mode: "LOCAL", tasks: [] },
    // Failed build with failure detail + opt-in warnings (plan 044/045): exercises the Failure section
    // (exception class, message, scrubbed stacktrace) and the Warnings section (deprecations, log
    // warnings, dropped count) that the detail view renders from the full payload.
    "/v1/builds/f1": {
        buildId: "f1", projectKey: "pilot", startedAt: 1751450000000, finishedAt: 1751450005000,
        outcome: "FAILED", mode: "CI",
        tasks: [{ path: ":app:compileKotlin", module: ":app", outcome: "FAILED", durationMs: 300 }],
        failure: {
            exceptionClass: "org.gradle.api.GradleException",
            message: "Execution failed for task ':app:compileKotlin'",
            stackTrace: "org.gradle.api.GradleException: Execution failed for task ':app:compileKotlin'\n\tat org.example.Widget.build(Widget.java:42)",
        },
        extensions: {
            internalAdapters: {
                schemaVersion: 1, gradleVersion: "9.6.1",
                deprecations: ["The Foo API has been deprecated. This will fail with an error in Gradle 10."],
                logWarnings: ["warning: [deprecation] bar() in Baz has been deprecated"],
                droppedWarnings: 3,
            },
        },
    },
    // Honest degraded test telemetry (plan 053, research F3): an executed Test task ran with
    // `reports.junitXml.required = false` — `tests` is empty, but `testTelemetry` names the task, so
    // the detail page and the #/tests page must show the note rather than "no test results".
    "/v1/builds/t1": {
        buildId: "t1", projectKey: "pilot", startedAt: 1751450200000, finishedAt: 1751450212000,
        outcome: "SUCCESS", mode: "CI",
        tasks: [{ path: ":app:testDebugUnitTest", module: ":app", outcome: "EXECUTED", durationMs: 12000 }],
        tests: [],
        testTelemetry: { xmlDisabledTasks: [":app:testDebugUnitTest"] },
    },
    "/v1/trends?days=30": [
        { day: "2026-06-30", builds: 3, failures: 1, avgDurationMs: 60000, avgHitRate: 0.5 },
        { day: "2026-07-01", builds: 2, failures: 0, avgDurationMs: null, avgHitRate: null },
    ],
    // Artifact-size trends (plan 031): one series for :app release APK.
    "/v1/artifacts/trends?days=30": [
        { day: "2026-06-30", module: ":app", variant: "release", type: "APK", avgSizeBytes: 8000000, maxSizeBytes: 8200000, builds: 3 },
        { day: "2026-07-01", module: ":app", variant: "release", type: "APK", avgSizeBytes: 8100000, maxSizeBytes: 8300000, builds: 2 },
    ],
    // Tag-cohort comparison (plan 057): one tag key (R8) with two values; selecting it in the
    // trends split picker fetches the per-cohort series + a distinguishable delta.
    "/v1/tags": [
        { key: "R8", values: [{ value: "true", count: 8 }, { value: "false", count: 4 }] },
    ],
    "/v1/trends/cohorts?days=30&tag=R8": {
        tagKey: "R8",
        cohorts: [
            {
                value: "true", sampleCount: 8, medianDurationMs: 58000,
                points: [
                    { day: "2026-06-30", builds: 4, failures: 0, avgDurationMs: 58000, maxDurationMs: 60000, avgHitRate: 0.6, interrupted: 0 },
                    { day: "2026-07-01", builds: 4, failures: 0, avgDurationMs: 57000, maxDurationMs: 59000, avgHitRate: 0.65, interrupted: 0 },
                ],
            },
            {
                value: "false", sampleCount: 4, medianDurationMs: 70000,
                points: [
                    { day: "2026-06-30", builds: 2, failures: 0, avgDurationMs: 70000, maxDurationMs: 72000, avgHitRate: 0.5, interrupted: 0 },
                    { day: "2026-07-01", builds: 2, failures: 0, avgDurationMs: 71000, maxDurationMs: 73000, avgHitRate: 0.5, interrupted: 0 },
                ],
            },
        ],
        delta: {
            referenceValue: "true",
            comparisons: [{ value: "false", medianDeltaMs: 12000, pctChange: 0.206897, robustZ: 4.1, status: "DISTINGUISHABLE" }],
        },
    },
    "/v1/builds/b1/compare/b2": {
        a: { buildId: "b1", startedAt: 1751450000000, outcome: "SUCCESS", mode: "CI", branch: "main", sha: "abcdef0123456789" },
        b: { buildId: "b2", startedAt: 1751450100000, outcome: "FAILED", mode: "LOCAL" },
        requestedTasksMatch: false,
        missesToExplain: [":app:compileKotlin"],
        diffs: [{ key: "jdk.home", scope: "BUILD", valueA: "aaaa1111…", valueB: "bbbb2222…", differingTaskCount: 0, coverage: 1.0, note: "JDK install path differs" }],
    },
    // CI span tree (plan 028): a completed, enriched run — nested stage→job→step spans + derived views.
    "/v1/builds/b1/ci-run": {
        status: "OK",
        queuedMs: 4200,
        gradleSharePct: 0.35,
        spans: [
            { id: "s1", kind: "STAGE", name: "Build stage", startMs: 0, finishMs: 300000, result: "SUCCEEDED" },
            { id: "j1", parentId: "s1", kind: "JOB", name: "Compile job", startMs: 1000, finishMs: 120000, result: "SUCCEEDED" },
            { id: "t1", parentId: "j1", kind: "STEP", name: "Gradle build step", startMs: 2000, finishMs: 118000, result: "FAILED" },
        ],
    },
    // b2 has no ci-run response → 404 → the honest amber "not available" notice.
    // Tasks explorer rollups (plan 026). byTypeAvailable false → the by-type toggle shows an empty state.
    "/v1/rollups/project-cost?days=30": [
        { module: ":app", builds: 12, executedBuilds: 9, buildImpactedUsers: 3, serialTaskMs: 45000, buildAvgDurationMs: 60000, buildPercentage: 0.8, buildCostScalar: 4800000 },
    ],
    // Costliest modules to change (plan 063): the card beside project cost.
    "/v1/rollups/change-blast-radius?days=30": [
        { module: ":core:common", changeCount: 8, medianDownstreamMs: 42000, blastScore: 336000 },
    ],
    "/v1/rollups/task-duration?days=30": {
        byName: [{ key: "compileKotlin", count: 40, totalMs: 800000, avgMs: 20000, minMs: 1000, maxMs: 50000 }],
        byType: [],
        byTypeAvailable: false,
    },
    "/v1/rollups/negative-avoidance?days=30": [
        { key: "checkstyle", count: 5, totalExcessMs: 12000, worstExcessMs: 4000 },
    ],
    // Owning-plugin cost rollup (plan 058): lazily fetched only when "By plugin" is clicked.
    "/v1/rollups/plugin-cost?days=30": {
        available: true,
        plugins: [
            { plugin: "Kotlin Gradle Plugin", totalMs: 800000, count: 40, sharePct: 0.8 },
            { plugin: "(unattributed)", totalMs: 200000, count: 10, sharePct: 0.2 },
        ],
    },
    // Benchmark series (plan 030): one scenario with two isolation modes, percentile summaries.
    "/v1/benchmark/series?days=90": [
        {
            scenario: "clean", isolationMode: "full_cache",
            points: [
                { startedAt: 1751450000000, buildId: "b1", iteration: 1, durationMs: 60000, hitRate: 0.9 },
                { startedAt: 1751450100000, buildId: "b2", iteration: 2, durationMs: 62000, hitRate: 0.9 },
            ],
            summary: { p50: 62000, p90: 62000, min: 60000, count: 2 },
        },
        {
            scenario: "clean", isolationMode: "no_build_cache",
            points: [
                { startedAt: 1751450000000, buildId: "b3", iteration: 1, durationMs: 120000, hitRate: 0.0 },
            ],
            summary: { p50: 120000, p90: 120000, min: 120000, count: 1 },
        },
    ],
    // Bottlenecks landing rollup (plan 032): headline KPI deltas + four ranked families incl. a
    // regressed/new/vanished trio and a cache-miss hotspot. budget/trend counts null → card omitted.
    "/v1/rollups/bottlenecks?period=7": {
        period: 7,
        buildCount: { current: 12, prior: 10, deltaPct: 0.2 },
        successRate: { current: 0.9, prior: 0.95, deltaPct: -0.052632 },
        avgDurationMs: { current: 65000, prior: 60000, deltaPct: 0.083333 },
        hitRate: { current: 0.7, prior: 0.6, deltaPct: 0.166667 },
        regressedTasks: [
            { key: "KotlinCompile", module: ":app", currentMs: 5000, priorMs: 3000, deltaMs: 2000, deltaPct: 0.666667, isNew: false, isVanished: false, count: 8 },
            { key: "NewType", currentMs: 800, priorMs: null, deltaMs: 800, deltaPct: null, isNew: true, isVanished: false, count: 4 },
            { key: "VanishedType", currentMs: 0, priorMs: 500, deltaMs: -500, deltaPct: null, isNew: false, isVanished: true, count: 0 },
        ],
        slowestWork: [{ key: "Test", module: ":app", currentMs: 44000, count: 20 }],
        negativeAvoidance: [{ key: "checkstyle", currentMs: 12000, count: 5 }],
        cacheMissHotspots: [{ key: "CacheMissType", module: ":app", currentMs: 1200, count: 3 }],
        cacheDataAvailable: true,
        budgetBreaches: null,
        trendRegressions: null,
        // Top plugins by time (plan 058; topPluginsAvailable added in review follow-up): omitted
        // entirely on period=14/30 below to also cover the additive-field-absent (older-server)
        // rendering path — topPluginsAvailable is then undefined/falsy, so the degraded notice shows.
        topPlugins: [
            { key: "Kotlin Gradle Plugin", currentMs: 5000, count: 8 },
            { key: "(unattributed)", currentMs: 800, count: 4 },
        ],
        topPluginsAvailable: true,
    },
    "/v1/rollups/bottlenecks?period=14": {
        period: 14,
        buildCount: { current: 20, prior: 18, deltaPct: 0.111111 },
        successRate: { current: 0.92, prior: 0.9, deltaPct: 0.022222 },
        avgDurationMs: { current: 61000, prior: 62000, deltaPct: -0.016129 },
        hitRate: { current: 0.65, prior: 0.66, deltaPct: -0.015152 },
        regressedTasks: [], slowestWork: [], negativeAvoidance: [], cacheMissHotspots: [],
        cacheDataAvailable: true, budgetBreaches: null, trendRegressions: null,
    },
    "/v1/rollups/bottlenecks?period=30": {
        period: 30,
        buildCount: { current: 40, prior: 39, deltaPct: 0.025641 },
        successRate: { current: 0.93, prior: 0.93, deltaPct: 0.0 },
        avgDurationMs: { current: 60000, prior: 60000, deltaPct: 0.0 },
        hitRate: { current: 0.67, prior: 0.64, deltaPct: 0.046875 },
        regressedTasks: [], slowestWork: [], negativeAvoidance: [], cacheMissHotspots: [],
        cacheDataAvailable: true, budgetBreaches: null, trendRegressions: null,
    },
    // Flaky tests (plan 036): two records — a cross-run and a retry — with an allowlisted signal badge.
    "/v1/flaky?days=30": [
        { module: ":app", className: "com.example.CartTest", signal: "CROSS_RUN", flakeRate: 0.4, sampleCount: 5, firstSeenMs: 1751440000000, lastSeenMs: 1751450000000, affectedBuildIds: ["b1", "b2"] },
        { module: ":lib", className: "com.example.PayTest", signal: "RETRY", flakeRate: 0.2, sampleCount: 10, firstSeenMs: 1751440000000, lastSeenMs: 1751450000000, affectedBuildIds: ["b3"] },
    ],
    // Toolchain adoption (plan 032): gradle has a behind list (8.9 < 8.10), jdk is uniform, and
    // agp/kgp/ksp are honestly unavailable (available:false) until the plugin reports them.
    "/v1/rollups/toolchain?days=30": {
        gradle: {
            available: true,
            versions: [
                { version: "8.10", builds: 9, sharePct: 0.75, distinctUsers: 3, lastSeenMs: 1751450000000 },
                { version: "8.9", builds: 3, sharePct: 0.25, distinctUsers: 1, lastSeenMs: 1751440000000 },
            ],
            behind: [{ version: "8.9", builds: 3, sharePct: 0.25, distinctUsers: 1, lastSeenMs: 1751440000000 }],
        },
        jdk: { available: true, versions: [{ version: "21", builds: 12, sharePct: 1.0, distinctUsers: 3, lastSeenMs: 1751450000000 }], behind: [] },
        agp: { available: false, versions: [], behind: [] },
        kgp: { available: false, versions: [], behind: [] },
        ksp: { available: false, versions: [], behind: [] },
        springBoot: { available: false, versions: [], behind: [] },
    },
    // Delivery health (plan 059): connector-enriched happy path — CFR rows (one green, one red),
    // time-to-green incl. a still-red open episode, lead time with queue/share populated, and a
    // retry-tax card with a flaky-rerun candidate linking to #/flaky.
    "/v1/rollups/delivery-health?days=30": {
        period: 30,
        changeFailureRate: [
            { branch: "main", pipelineName: "android-ci", failed: 3, succeeded: 7, changeFailureRate: 0.3 },
            { branch: "release", pipelineName: "android-ci", failed: 0, succeeded: 5, changeFailureRate: 0.0 },
        ],
        timeToGreen: [
            { branch: "main", pipelineName: "android-ci", recoveries: 2, medianRecoveryMs: 5400000, p90RecoveryMs: 9000000, openEpisode: false },
            { branch: "release", pipelineName: "android-ci", recoveries: 0, medianRecoveryMs: null, p90RecoveryMs: null, openEpisode: true },
        ],
        leadTime: [
            { branch: "main", pipelineName: "android-ci", buildCount: 10, medianDurationMs: 300000, medianQueuedMs: 42000, medianGradleSharePct: 0.35 },
            { branch: "release", pipelineName: "android-ci", buildCount: 5, medianDurationMs: 280000, medianQueuedMs: null, medianGradleSharePct: null },
        ],
        retryTax: {
            chainCount: 2, rerunBuildIds: ["b1", "b2"], wastedCiMinutesLowerBound: 12.5,
            runAttemptReruns: 1, sameKeyCandidates: 1,
        },
        connectorDataAvailable: true,
        flakyRerunTax: [
            { module: ":app", className: "com.example.CartTest", rerunBuildCount: 2, wastedCiMinutesLowerBound: 8.0 },
        ],
    },
    // Warning taxonomy (plan 060): one ALWAYS_RUN candidate with evidence; period=14/30 are empty
    // (also covers the additive-field-absent path, mirroring topPlugins above).
    "/v1/rollups/warnings?period=7": {
        period: 7,
        warnings: [
            { category: "ALWAYS_RUN", key: "customTask", module: ":app", buildsObserved: 10, buildsAffected: 9, share: 0.9, totalMs: 9000, evidenceReason: "Task.upToDateWhen is false." },
        ],
        typeDataAvailable: true,
    },
    "/v1/rollups/warnings?period=14": { period: 14, warnings: [], typeDataAvailable: true },
    // Remote-cache ROI (plan 067, research F17): a configured remote with near-zero CI reuse — the
    // config-snapshot summary, the per-mode rate table, and the ranked investigate-candidate all fire.
    "/v1/rollups/cache-roi?days=30": {
        remoteHitRateAvailable: true,
        buildsWithConfig: 10,
        remoteConfiguredShare: 0.8,
        perMode: [
            { mode: "CI", remoteHitRate: 0.02, localHitRate: 0.4, consideredExecutions: 314, remoteHits: 6, localHits: 126 },
        ],
        ciReuseCandidate: {
            mode: "CI", remoteHitRate: 0.02, consideredExecutions: 314,
            note: "CI shows near-zero remote-cache reuse (2% over 314 cacheable task executions) despite a configured remote cache — investigate cache reachability and key stability.",
        },
    },
};
// X-Total-Count values by path; a path absent here → header missing (tolerance case).
const totals = {
    "/v1/builds?limit=50&offset=0": 3,
    "/v1/builds?branch=none&limit=50&offset=0": 0,
};

const fetched = [];
async function fetchStub(path, opts) {
    fetched.push(path);
    if (!opts.headers.Authorization.startsWith("Bearer ")) throw new Error("no bearer header");
    const headersOnly = { get: name => (name === "X-Total-Count" && totals[path] != null ? String(totals[path]) : null) };
    // Admin retention (plan 042): GET returns the current config; PUT validates the windows like the
    // server (invalid → 400) so the smoke exercises both the save and the validation-error branch.
    if (path === "/v1/admin/retention") {
        if (opts.method === "PUT") {
            const cfg = JSON.parse(opts.body);
            if (cfg.rawDays < 1 || cfg.buildDays < cfg.rawDays) {
                return { ok: false, status: 400, json: async () => ({ error: "buildDays must be >= rawDays" }), headers: headersOnly };
            }
            return { ok: true, status: 200, json: async () => cfg, headers: headersOnly };
        }
        return { ok: true, status: 200, json: async () => ({ rawDays: 90, buildDays: 395 }), headers: headersOnly };
    }
    const body = responses[path];
    const headers = { get: name => (name === "X-Total-Count" && totals[path] != null ? String(totals[path]) : null) };
    if (body === undefined) return { ok: false, status: 404, json: async () => ({}), headers };
    return { ok: true, status: 200, json: async () => body, headers };
}

const context = {
    document: documentStub,
    window: { addEventListener: (t, fn) => { context._onhashchange = fn; } },
    location: { hash: "", origin: "http://localhost:8080" },
    sessionStorage: {
        getItem: k => (k in store ? store[k] : null),
        setItem: (k, v) => { store[k] = String(v); },
    },
    fetch: fetchStub,
    URLSearchParams,
    Option: function (label, value) { const n = makeNode("option"); n.textContent = label; n.value = value; return n; },
    Date, Math, Object, String, Number, Array, Boolean, isFinite, Set, Map, Error, console,
    setTimeout, // not used, but harmless
};
context.globalThis = context;

vm.createContext(context);
// Load the shared timeline renderer (argv[3]) first so buildhoundTimeline is a global for
// the detail view, mirroring the browser loading /timeline.js before /dashboard.js.
if (process.argv[3]) vm.runInContext(fs.readFileSync(process.argv[3], "utf8"), context, { filename: "timeline.js" });
vm.runInContext(fs.readFileSync(process.argv[2], "utf8"), context, { filename: "dashboard.js" });

const tick = () => new Promise(resolve => setTimeout(resolve, 0));

(async () => {
    // First run: no token stored → the initial route() must render a friendly panel and
    // perform NO fetch (plan 018), not fire a request that 401s into red error text.
    await tick(); await tick();
    if (fetched.length !== 0) throw new Error("first-run must not fetch: " + fetched);
    if (!hasText(byId["app"], "read token")) throw new Error("first-run panel missing");

    // Save a token → route() re-runs and the builds view fetches.
    byId["token-input"].value = "  s3cr3t-token-value  ";
    byId["token-save"].listeners.click[0]();
    await tick(); await tick();
    if (store["buildhound.token"] !== "s3cr3t-token-value") throw new Error("token not trimmed/saved: " + JSON.stringify(store));
    // Landing page is Bottlenecks (plan 032): saving a token renders it and fetches its rollups.
    if (!fetched.includes("/v1/rollups/bottlenecks?period=7")) throw new Error("bottlenecks landing did not fetch after token save");
    if (!hasText(byId["app"], "What got worse")) throw new Error("bottlenecks landing summary missing");
    // The Builds page is reachable via its own hash and shows the filter-aware count.
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/builds?limit=50&offset=0")) throw new Error("builds view did not fetch");
    if (!hasText(byId["app"], "3 builds")) throw new Error("builds count-summary sentence missing");

    // Detail view: timeline svg (present), work-avoidance ledger with the unknown-cacheability
    // bucket and explicit-zero rows.
    context.location.hash = "#/build/b1"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/builds/b1")) throw new Error("detail view did not fetch");
    if (context.buildhoundTimeline && countTag(byId["app"], "svg") === 0) throw new Error("detail view did not render the timeline svg");
    if (!hasText(byId["app"], "Unknown cacheability")) throw new Error("ledger missing the unknown-cacheability bucket");
    if (!hasText(byId["app"], "0.0%")) throw new Error("ledger missing explicit-zero rows");
    if (!hasText(byId["app"], "total task time")) throw new Error("detail count-summary sentence missing");

    // Kotlin panel (plan 023): present with a bundled report; chips, slowest-compilation table
    // (with the non-incremental reason), and summed compiler-phase time all render.
    if (!hasText(byId["app"], "Kotlin compilation")) throw new Error("kotlin panel header missing");
    if (!hasText(byId["app"], ":app:compileKotlin")) throw new Error("kotlin slowest-compilation row missing");
    if (!hasText(byId["app"], "1 / 2")) throw new Error("kotlin incremental-effectiveness chip missing");
    // incremental time share: 300ms incremental of 1468ms total ≈ 20%.
    if (!hasText(byId["app"], "incremental time")) throw new Error("kotlin incremental-time chip missing");
    if (!hasText(byId["app"], "20%")) throw new Error("kotlin incremental-time share value missing");
    if (!hasText(byId["app"], "UNKNOWN_CHANGES_IN_GRADLE_INPUTS")) throw new Error("kotlin non-incremental reason missing");
    if (!hasText(byId["app"], "Compiler phase time")) throw new Error("kotlin phase-time summary missing");

    // Tests section (plan 024): summary sentence, failures/retries table (with the retry outcome
    // sequence), and slowest classes all render on the build detail.
    if (!hasText(byId["app"], "Failures & retries")) throw new Error("tests failures table missing");
    if (!hasText(byId["app"], "totalsWithDiscount()")) throw new Error("tests failure row missing");
    if (!hasText(byId["app"], "FAILED → PASSED")) throw new Error("retry outcome sequence missing");
    if (!hasText(byId["app"], "Slowest classes")) throw new Error("tests slowest-classes table missing");

    // CI span tree (plan 028): the enriched pipeline section renders with queue-time + Gradle-share
    // chips and the nested stage→job→step span names.
    if (!hasText(byId["app"], "CI pipeline")) throw new Error("ci-run section header missing");
    if (!hasText(byId["app"], "queue time")) throw new Error("ci-run queue-time chip missing");
    if (!hasText(byId["app"], "gradle share")) throw new Error("ci-run gradle-share chip missing");
    if (!hasText(byId["app"], "Build stage")) throw new Error("ci-run stage span missing");
    if (!hasText(byId["app"], "Gradle build step")) throw new Error("ci-run nested step span missing");

    // Process snapshot (plan 029): the panel renders per-JVM memory with a native <progress> used-vs-Xmx bar.
    if (!hasText(byId["app"], "Process snapshot")) throw new Error("process panel header missing");
    if (!hasText(byId["app"], "Gradle daemon")) throw new Error("process panel role row missing");
    if (!hasText(byId["app"], "Kotlin daemon")) throw new Error("process panel second role row missing");
    // Daemon-tuning candidates (plan 065): the pinned Kotlin daemon (1900/2048) fires the advisory
    // card naming kotlin.daemon.jvmargs; the calm Gradle daemon (0.4 % GC, compactObjectHeaders
    // true) fires no GC-pressure card at all (org.gradle.jvmargs never appears).
    if (!hasText(byId["app"], "Tuning candidates")) throw new Error("tuning-candidate cards missing");
    if (!hasText(byId["app"], "kotlin.daemon.jvmargs")) throw new Error("pinned-Xmx candidate must name kotlin.daemon.jvmargs");
    if (hasText(byId["app"], "org.gradle.jvmargs")) throw new Error("no GC-pressure card may fire for the calm Gradle daemon");
    // The high-GC-fraction G1 Kotlin daemon (plan-065 review fix) trips all three previously
    // CI-unverified cards; each assertion pins the interpolated PROCESS_ROLE_LABELS role label too.
    if (!hasText(byId["app"], "Investigate high GC time (20 % of Kotlin daemon JVM time)")) throw new Error("GC-pressure candidate missing for the high-GC Kotlin daemon");
    if (!hasText(byId["app"], "The Kotlin daemon runs G1 with high GC time — a ParallelGC trial")) throw new Error("ParallelGC-trial candidate missing for the high-GC Kotlin daemon");
    if (!hasText(byId["app"], "The Kotlin daemon uses 3072 MB RSS on JDK 24 without compact object headers")) throw new Error("compact-object-headers candidate missing for the high-GC Kotlin daemon");

    // Minimal build (no tasks): ledger renders all-zero rows without dividing by zero.
    context.location.hash = "#/build/b2"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/builds/b2")) throw new Error("minimal detail view did not fetch");
    if (!hasText(byId["app"], "0 tasks")) throw new Error("empty-tasks detail sentence missing");
    // No bundled report → no Kotlin panel at all (panel renders only with data).
    if (hasText(byId["app"], "Kotlin compilation")) throw new Error("kotlin panel must be absent without a report");
    // No test results on b2 → no Tests section either.
    if (hasText(byId["app"], "Failures & retries")) throw new Error("tests section must be absent without results");
    // No connector run for b2 (ci-run 404) → the honest amber notice, never a hidden section.
    if (!hasText(byId["app"], "CI timeline not available")) throw new Error("ci-run degraded notice missing on a build with no run");
    // No process data on b2 → the panel is absent (renders only with data), and so are the
    // plan-065 tuning cards (no probe rows → no candidate, never a fabricated card).
    if (hasText(byId["app"], "Process snapshot")) throw new Error("process panel must be absent without process data");
    if (hasText(byId["app"], "Tuning candidates")) throw new Error("tuning cards must be absent without process data");
    // b2 is FAILED but carries no failure object (a config-phase failure — plan-044 extraction is
    // execution-phase) and no warnings block → both new sections must be absent, and not throw.
    if (findAll(byId["app"], n => (n.className || "").indexOf("failure-summary") >= 0).length !== 0) throw new Error("failure section must be absent when the payload has no failure object");
    if (hasText(byId["app"], "Warnings")) throw new Error("warnings section must be absent without an internal-adapters block");
    if (hasText(byId["app"], "Test telemetry unavailable")) throw new Error("degraded-test note must be absent without a testTelemetry block");

    // Honest degraded test telemetry (plan 053, research F3): t1 has an empty `tests` array but a
    // populated `testTelemetry` — the Tests section must still render, with the note naming the
    // disabled task, and no empty "Slowest classes"/"Failures & retries" tables pretending there is
    // real data.
    context.location.hash = "#/build/t1"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/builds/t1")) throw new Error("degraded-test detail view did not fetch");
    if (!hasText(byId["app"], "Test telemetry unavailable")) throw new Error("detail view degraded note missing");
    if (!hasText(byId["app"], ":app:testDebugUnitTest")) throw new Error("detail view degraded note missing the task path");
    if (hasText(byId["app"], "Slowest classes")) throw new Error("degraded build must not render an empty slowest-classes table");
    if (hasText(byId["app"], "Failures & retries")) throw new Error("degraded build must not render an empty failures table");

    // Failure + warnings (plan 044/045): a failed build with a failure object and the opt-in
    // internal-adapters block renders the Failure section (class + message + scrubbed stacktrace) and
    // the Warnings section (labelled deprecation + log-warning lists and the dropped-count note).
    context.location.hash = "#/build/f1"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/builds/f1")) throw new Error("failure detail view did not fetch");
    if (findAll(byId["app"], n => (n.className || "").indexOf("failure-summary") >= 0).length === 0) throw new Error("failure section missing on a build with failure detail");
    if (!hasText(byId["app"], "org.gradle.api.GradleException")) throw new Error("failure exception class missing");
    if (!hasText(byId["app"], "Execution failed for task ':app:compileKotlin'")) throw new Error("failure message missing");
    if (findAll(byId["app"], n => (n.className || "").indexOf("failure-trace") >= 0).length === 0) throw new Error("failure stacktrace <pre> missing");
    if (!hasText(byId["app"], "org.example.Widget.build(Widget.java:42)")) throw new Error("failure stacktrace body missing");
    if (!hasText(byId["app"], "Warnings")) throw new Error("warnings section header missing");
    if (!hasText(byId["app"], "Deprecations (1)")) throw new Error("deprecations count label missing");
    if (!hasText(byId["app"], "The Foo API has been deprecated")) throw new Error("deprecation entry missing");
    if (!hasText(byId["app"], "Log warnings (1)")) throw new Error("log-warnings count label missing");
    if (!hasText(byId["app"], "bar() in Baz has been deprecated")) throw new Error("log-warning entry missing");
    if (!hasText(byId["app"], "3 more warnings dropped past the cap")) throw new Error("dropped-warnings note missing");
    // Structure, not just text: warnings must render as <ul class="warnings"> lists, so a regression that
    // dumped every warning into one paragraph (losing the list/count pairing) is caught, not passed.
    if (findAll(byId["app"], n => n.tag === "ul" && (n.className || "").indexOf("warnings") >= 0).length === 0) throw new Error("warnings <ul> structure missing");

    // Lost build (plan 033): an INTERRUPTED, empty-task build renders the honest amber "did not
    // finish" note (not an all-zero ledger) and stops there, never throwing.
    context.location.hash = "#/build/int-1"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/builds/int-1")) throw new Error("interrupted detail did not fetch");
    if (!hasText(byId["app"], "did not finish")) throw new Error("interrupted amber note missing");
    if (hasText(byId["app"], "Work avoidance")) throw new Error("interrupted build must not render the work-avoidance ledger");

    // Tests page (plan 024): defaults to the latest build (b1), renders its tests panel.
    context.location.hash = "#/tests"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "Test results")) throw new Error("tests page header missing");
    if (countTag(byId["app"], "select") < 1) throw new Error("tests page build picker missing");
    if (!hasText(byId["app"], "totalsWithDiscount()")) throw new Error("tests page did not render the default build's failures");

    // Selecting the JUnit-XML-disabled build (t1) on the Tests page renders the same honest note
    // (plan 053) as the detail page — the shared testsPanel() must not silently blank the picker path.
    const testsSelect = findTag(byId["app"], "select")[0];
    testsSelect.value = "t1";
    testsSelect.listeners.change[0]();
    await tick(); await tick();
    if (!hasText(byId["app"], "Test telemetry unavailable")) throw new Error("tests page degraded note missing");
    if (!hasText(byId["app"], ":app:testDebugUnitTest")) throw new Error("tests page degraded note missing the task path");
    if (hasText(byId["app"], "Slowest classes")) throw new Error("tests page must not render an empty slowest-classes table for a degraded build");

    context.location.hash = "#/trends"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/trends?days=30")) throw new Error("trends view did not fetch");
    if (!hasText(byId["app"], "active day")) throw new Error("trends count-summary sentence missing");
    // Artifact-size panel (plan 031): fetched as a second async round-trip; assert the section + series.
    await tick(); await tick();
    if (!fetched.includes("/v1/artifacts/trends?days=30")) throw new Error("trends view did not fetch artifact sizes");
    if (!hasText(byId["app"], "Artifact sizes")) throw new Error("artifact-size panel header missing");
    if (!hasText(byId["app"], ":app · release · APK")) throw new Error("artifact-size series label missing");

    // Tag-cohort split (plan 057): the picker populates from /v1/tags (fetched alongside the
    // artifact panel above); selecting "R8" re-renders the page and additionally fetches the
    // cohort comparison, rendering a legend + multi-series chart + delta table with a semantic
    // (goodness) coloured chip. CSP-safe: every fetch is same-origin, like every other view.
    if (!fetched.includes("/v1/tags")) throw new Error("trends view did not fetch tag keys");
    const tagSelect = findTag(byId["app"], "select").find(s => findAll(s, n => n.tag === "option" && n.value === "R8").length > 0);
    if (!tagSelect) throw new Error("tag split picker missing an R8 option");
    if (!tagSelect.listeners.change) throw new Error("tag split picker has no change listener");
    tagSelect.value = "R8";
    tagSelect.listeners.change[0]();
    await tick(); await tick(); await tick(); await tick();
    if (!fetched.includes("/v1/trends/cohorts?days=30&tag=R8")) throw new Error("tag split did not fetch the cohort comparison");
    if (!hasText(byId["app"], "true (n=8)")) throw new Error("cohort legend missing the reference cohort");
    if (!hasText(byId["app"], "false (n=4)")) throw new Error("cohort legend missing the second cohort");
    if (!hasText(byId["app"], "true (reference)")) throw new Error("cohort delta table reference row missing");
    if (!hasClass(byId["app"], "delta-bad")) throw new Error("cohort delta table missing the semantic-colouring chip");
    // Baseline is 4 svgs (duration, hit-rate, builds-per-day bars, one artifact-size series); the
    // cohort multi-series chart must add a 5th.
    if (countTag(byId["app"], "svg") < 5) throw new Error("cohort chart svg missing (in addition to the duration/hit-rate/artifact charts)");

    // The error view must render, not throw, on API failure.
    context.location.hash = "#/build/missing"; context._onhashchange(); await tick(); await tick();

    // Timeline global absent → detail view still renders (graceful omission), no throw.
    // (A function-declared global is non-configurable, so overwrite rather than delete.)
    context.buildhoundTimeline = undefined;
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/build/b1"; context._onhashchange(); await tick(); await tick();
    if (countTag(byId["app"], "svg") !== 0) throw new Error("timeline must be omitted when the global is absent");
    if (countTag(byId["app"], "table") === 0) throw new Error("detail view must still render without the timeline global");

    // Throwing renderer → the detail view's try/catch swallows it and still renders.
    context.buildhoundTimeline = function () { throw new Error("boom"); };
    context.location.hash = "#/build/b1"; context._onhashchange(); await tick(); await tick();
    if (countTag(byId["app"], "svg") !== 0) throw new Error("a throwing renderer must not leave a timeline svg");
    if (countTag(byId["app"], "table") === 0) throw new Error("detail view must survive a throwing renderer");

    // Filtered empty → clear-filters is offered and clicking it re-fetches unfiltered.
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    findTag(byId["app"], "input")[0].value = "none";
    clickButton(byId["app"], "Apply");
    await tick(); await tick();
    if (!hasText(byId["app"], "No builds match this filter")) throw new Error("filtered-empty state missing");
    const beforeClear = fetched.length;
    clickButton(byId["app"], "Clear filters");
    await tick(); await tick();
    if (fetched.length <= beforeClear) throw new Error("clear-filters must re-fetch");

    // Branch-qualified non-empty list → sentence uses the filter-aware total (5), not the
    // page size (1), and appends the branch qualifier.
    responses["/v1/builds?branch=main&limit=50&offset=0"] = [
        { buildId: "m1", startedAt: 1751450000000, durationMs: 1000, outcome: "SUCCESS", mode: "CI", branch: "main" },
    ];
    totals["/v1/builds?branch=main&limit=50&offset=0"] = 5;
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    findTag(byId["app"], "input")[0].value = "main";
    clickButton(byId["app"], "Apply");
    await tick(); await tick();
    if (!hasText(byId["app"], "5 builds on main")) throw new Error("branch-qualified count-summary sentence missing");

    // Comparisons (plan 022): picker renders two selects; the comparison view renders the diff.
    context.location.hash = "#/compare"; context._onhashchange(); await tick(); await tick();
    if (countTag(byId["app"], "select") < 2) throw new Error("compare picker must render two build selects");
    context.location.hash = "#/compare/b1/b2"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "Comparison")) throw new Error("comparison view header missing");
    if (!hasText(byId["app"], "jdk.home")) throw new Error("comparison view must render the changed input");
    if (!hasText(byId["app"], ":app:compileKotlin")) throw new Error("comparison view must list the cache miss");

    // Tasks explorer (plan 026): the three rollups render; the by-type toggle shows the
    // "types not populated" empty state (byTypeAvailable false); no throw on any of it.
    context.location.hash = "#/tasks"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/rollups/project-cost?days=30")) throw new Error("tasks view did not fetch project-cost");
    if (!hasText(byId["app"], "Project cost by module")) throw new Error("project-cost section missing");
    if (!hasText(byId["app"], ":app")) throw new Error("project-cost row missing");
    // Costliest modules to change (plan 063): the card renders its heading + a module row.
    if (!fetched.includes("/v1/rollups/change-blast-radius?days=30")) throw new Error("tasks view did not fetch change-blast-radius");
    if (!hasText(byId["app"], "Costliest modules to change")) throw new Error("change-blast-radius section missing");
    if (!hasText(byId["app"], ":core:common")) throw new Error("change-blast-radius row missing");
    if (!hasText(byId["app"], "compileKotlin")) throw new Error("task-duration by-name row missing");
    if (!hasText(byId["app"], "checkstyle")) throw new Error("negative-avoidance row missing");
    clickButton(byId["app"], "By type");
    if (!hasText(byId["app"], "Task types not populated yet")) throw new Error("by-type empty state missing when byTypeAvailable is false");

    // Owning-plugin grouping (plan 058): lazily fetched only once "By plugin" is clicked.
    if (fetched.includes("/v1/rollups/plugin-cost?days=30")) throw new Error("plugin-cost must not fetch before the by-plugin button is clicked");
    clickButton(byId["app"], "By plugin");
    await tick(); await tick();
    if (!fetched.includes("/v1/rollups/plugin-cost?days=30")) throw new Error("by-plugin toggle did not fetch plugin-cost");
    if (!hasText(byId["app"], "Kotlin Gradle Plugin")) throw new Error("by-plugin row missing");
    if (!hasText(byId["app"], "80%")) throw new Error("by-plugin share missing");

    // available:false (isolated-projects / plan-016 degradation) → the honest not-populated notice,
    // never an empty table. A fresh #/tasks visit is needed since the view caches its fetch per-visit.
    responses["/v1/rollups/plugin-cost?days=30"] = { available: false, plugins: [] };
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/tasks"; context._onhashchange(); await tick(); await tick();
    clickButton(byId["app"], "By plugin");
    await tick(); await tick();
    if (!hasText(byId["app"], "Plugin attribution needs task types")) throw new Error("by-plugin unavailable notice missing when available is false");

    // Benchmark series (plan 030): per-scenario percentile chips + chart, isolation selector, empty state.
    context.location.hash = "#/benchmark"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/benchmark/series?days=90")) throw new Error("benchmark view did not fetch the series");
    if (!hasText(byId["app"], "Benchmark series")) throw new Error("benchmark header missing");
    if (!hasText(byId["app"], "Scenario: clean")) throw new Error("benchmark scenario section missing");
    if (!hasText(byId["app"], "p50")) throw new Error("benchmark percentile chips missing");
    if (countTag(byId["app"], "svg") === 0) throw new Error("benchmark duration chart missing");
    if (countTag(byId["app"], "select") < 1) throw new Error("benchmark isolation selector missing (two modes present)");
    // Empty series → the honest empty state, not a blank page.
    responses["/v1/benchmark/series?days=90"] = [];
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/benchmark"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "No benchmark builds yet")) throw new Error("benchmark empty state missing");

    // Bottlenecks landing (plan 032): KPI strip with semantic delta chips (colour = goodness, not
    // sign), four ranked families incl. new/vanished flags, cache-miss hotspots, and toolchain
    // adoption with a behind list + honest agp/kgp/ksp "not collected" panels.
    context.location.hash = "#/bottlenecks"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/rollups/bottlenecks?period=7")) throw new Error("bottlenecks did not fetch the rollup");
    if (!fetched.includes("/v1/rollups/toolchain?days=30")) throw new Error("bottlenecks did not fetch toolchain adoption");
    if (!hasText(byId["app"], "Success rate")) throw new Error("KPI strip missing");
    if (!hasClass(byId["app"], "delta-good")) throw new Error("no green (good) delta chip — semantic colouring missing");
    if (!hasClass(byId["app"], "delta-bad")) throw new Error("no red (bad) delta chip — semantic colouring missing");
    if (!hasText(byId["app"], "Regressed tasks")) throw new Error("regressed-tasks section missing");
    if (!hasText(byId["app"], "KotlinCompile")) throw new Error("regressed row missing");
    if (!hasExact(byId["app"], "new")) throw new Error("new-group flag missing");
    if (!hasExact(byId["app"], "gone")) throw new Error("vanished-group flag missing");
    if (!hasText(byId["app"], "CacheMissType")) throw new Error("cache-miss hotspot row missing");
    if (!hasText(byId["app"], "Top plugins by time")) throw new Error("top-plugins card header missing");
    if (!hasText(byId["app"], "Kotlin Gradle Plugin")) throw new Error("top-plugins row missing");
    if (!hasText(byId["app"], "Toolchain adoption")) throw new Error("toolchain section missing");
    if (!hasText(byId["app"], "8.10")) throw new Error("toolchain version row missing");
    if (!hasText(byId["app"], "behind the latest")) throw new Error("toolchain behind list missing");
    if (!hasText(byId["app"], "Not collected yet")) throw new Error("agp/kgp/ksp degraded panel missing");
    if (!hasText(byId["app"], "Spring Boot")) throw new Error("Spring Boot toolchain panel missing (plan 072)");
    // Remote-cache ROI (plan 067, research F17): best-effort fetch alongside toolchain/warnings; the
    // config-snapshot summary, the per-mode rate table, and the ranked investigate-candidate all render.
    if (!fetched.includes("/v1/rollups/cache-roi?days=30")) throw new Error("bottlenecks did not fetch cache-roi");
    if (!hasText(byId["app"], "Remote-cache ROI")) throw new Error("cache-roi section header missing");
    if (!hasText(byId["app"], "80% of 10 builds")) throw new Error("cache-roi config summary sentence missing");
    if (!hasExact(byId["app"], "314")) throw new Error("cache-roi per-mode table row missing");
    if (!hasText(byId["app"], "investigate cache reachability")) throw new Error("cache-roi reuse-candidate note missing");
    // Verdict card omitted while budget/trend counts are null.
    if (hasText(byId["app"], "budget breaches")) throw new Error("verdict card must be omitted when counts are null");

    // Warnings section (plan 060): best-effort fetch, one ranked ALWAYS_RUN candidate with its
    // evidence and remediation copy, an allowlisted category badge class, no console error.
    if (!fetched.includes("/v1/rollups/warnings?period=7")) throw new Error("bottlenecks did not fetch warnings");
    if (!hasText(byId["app"], "Warnings — candidates to investigate")) throw new Error("warnings section header missing");
    if (!hasText(byId["app"], "customTask")) throw new Error("warnings candidate row missing");
    if (!hasText(byId["app"], "9/10 builds")) throw new Error("warnings evidence (buildsAffected/buildsObserved) missing");
    if (!hasText(byId["app"], "Task.upToDateWhen is false.")) throw new Error("warnings evidenceReason missing");
    if (!hasText(byId["app"], "investigate")) throw new Error("warnings remediation copy missing");
    if (findAll(byId["app"], n => (n.className || "").indexOf("warn-always-run") >= 0).length === 0) throw new Error("warning category badge class missing");

    // Period toggle refetches the new window (bottlenecks + warnings both share `period`); the
    // period=14 fixture has an empty warnings list → the honest empty-state line, not a blank card.
    clickButton(byId["app"], "14 days");
    await tick(); await tick();
    if (!fetched.includes("/v1/rollups/bottlenecks?period=14")) throw new Error("period toggle did not refetch period=14");
    if (!fetched.includes("/v1/rollups/warnings?period=14")) throw new Error("period toggle did not refetch warnings at period=14");
    if (!hasText(byId["app"], "No warning candidates in this window.")) throw new Error("warnings empty-state missing at period=14");

    // Warnings fetch failure must not blank the rest of the Bottlenecks page (best-effort, like toolchain).
    delete responses["/v1/rollups/warnings?period=7"];
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/bottlenecks"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "Warnings data unavailable.")) throw new Error("warnings fetch-failure notice missing");
    if (!hasText(byId["app"], "Success rate")) throw new Error("a warnings fetch failure must not blank the rest of the bottlenecks page");

    // Cacheability unavailable → the honest degraded notice, never an empty table read as consensus.
    responses["/v1/rollups/bottlenecks?period=7"] = Object.assign({}, responses["/v1/rollups/bottlenecks?period=7"], { cacheDataAvailable: false, cacheMissHotspots: [] });
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/bottlenecks"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "Cacheability not collected yet")) throw new Error("cache-miss degraded notice missing");

    // Plugin attribution unavailable (isolated-projects, plan 058 review fix) → the honest degraded
    // notice, never the "(unattributed)" fold rendered as if it were real plugin-cost data.
    responses["/v1/rollups/bottlenecks?period=7"] = Object.assign({}, responses["/v1/rollups/bottlenecks?period=7"], {
        topPluginsAvailable: false,
        topPlugins: [{ key: "(unattributed)", currentMs: 800, count: 4 }],
    });
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/bottlenecks"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "Task types not collected yet")) throw new Error("top-plugins degraded notice missing");
    if (hasText(byId["app"], "(unattributed)")) throw new Error("top-plugins unattributed fold must not render as if it were real data");

    // Remote-cache ROI: remoteHitRateAvailable=false → the opt-in notice, never a synthesized rate; the
    // config-snapshot summary still renders independently (plan 067's two-tier design).
    responses["/v1/rollups/cache-roi?days=30"] = {
        remoteHitRateAvailable: false, buildsWithConfig: 10, remoteConfiguredShare: 0.8, perMode: [],
    };
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/bottlenecks"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "80% of 10 builds")) throw new Error("cache-roi config summary must still render when the rate is unavailable");
    if (!hasText(byId["app"], "needs the opt-in cache-origin capture")) throw new Error("cache-roi opt-in notice missing");
    if (hasText(byId["app"], "investigate cache reachability")) throw new Error("cache-roi candidate note must not render without an available rate");

    // Remote-cache ROI: buildsWithConfig=0 → the honest "not collected yet" notice (pre-067 plugin fleet).
    responses["/v1/rollups/cache-roi?days=30"] = { remoteHitRateAvailable: false, buildsWithConfig: 0, remoteConfiguredShare: 0.0, perMode: [] };
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/bottlenecks"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "Build-cache config not collected yet")) throw new Error("cache-roi not-collected notice missing");

    // Remote-cache ROI: a fetch failure is best-effort (like toolchain/warnings) — the whole section is
    // omitted rather than blanking the rest of the bottlenecks page.
    delete responses["/v1/rollups/cache-roi?days=30"];
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/bottlenecks"; context._onhashchange(); await tick(); await tick();
    if (hasText(byId["app"], "Remote-cache ROI")) throw new Error("cache-roi section must be omitted on a fetch failure, not throw");
    if (!hasText(byId["app"], "Success rate")) throw new Error("a cache-roi fetch failure must not blank the rest of the bottlenecks page");

    // Flaky page (plan 036): renders the records with the allowlisted signal badge classes.
    context.location.hash = "#/flaky"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/flaky?days=30")) throw new Error("flaky view did not fetch");
    if (!hasText(byId["app"], "Flaky tests")) throw new Error("flaky header missing");
    if (!hasText(byId["app"], "com.example.CartTest")) throw new Error("flaky cross-run row missing");
    if (!hasText(byId["app"], "com.example.PayTest")) throw new Error("flaky retry row missing");
    if (findAll(byId["app"], n => (n.className || "").indexOf("flaky-cross") >= 0).length === 0) throw new Error("cross-run signal badge class missing");
    if (findAll(byId["app"], n => (n.className || "").indexOf("flaky-retry") >= 0).length === 0) throw new Error("retry signal badge class missing");
    // Empty flaky → honest empty state.
    responses["/v1/flaky?days=30"] = [];
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/flaky"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "No flaky tests detected")) throw new Error("flaky empty state missing");

    // Delivery-health page (plan 059): all four panels render — CFR with semantic (goodness)
    // colouring, time-to-green with the honest CI-recovery caption + a still-red open episode,
    // lead time with connector-populated queue/share, and the retry-tax card whose flaky-rerun
    // candidate links to #/flaky. CSP-safe: same-origin fetch, textContent only, like every view.
    context.location.hash = "#/delivery"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/rollups/delivery-health?days=30")) throw new Error("delivery view did not fetch");
    if (!hasText(byId["app"], "Delivery health")) throw new Error("delivery summary sentence missing");
    if (!hasText(byId["app"], "not real DORA metrics")) throw new Error("delivery proxy-honesty caption missing");
    if (!hasText(byId["app"], "Change-failure rate")) throw new Error("CFR section missing");
    if (!hasText(byId["app"], "android-ci")) throw new Error("CFR pipeline row missing");
    if (!hasClass(byId["app"], "delta-bad")) throw new Error("CFR semantic (bad) colouring missing");
    if (!hasClass(byId["app"], "delta-good")) throw new Error("CFR semantic (good) colouring missing");
    if (!hasText(byId["app"], "CI recovery, not production MTTR")) throw new Error("time-to-green honesty caption missing");
    if (!hasExact(byId["app"], "still red")) throw new Error("open-episode still-red badge missing");
    if (!hasText(byId["app"], "Lead-time contribution")) throw new Error("lead-time section missing");
    if (hasText(byId["app"], "need a CI connector")) throw new Error("connector notice must be absent when connectorDataAvailable is true");
    if (!hasText(byId["app"], "Retry tax")) throw new Error("retry-tax section missing");
    if (!hasText(byId["app"], "12.5 (lower bound)")) throw new Error("wasted-CI-minutes lower-bound chip missing");
    if (!hasText(byId["app"], "same-key candidates")) throw new Error("retry-tax signal split missing");
    if (!hasText(byId["app"], "Flaky-rerun candidates")) throw new Error("flaky-rerun candidates section missing");
    if (!hasText(byId["app"], "com.example.CartTest")) throw new Error("flaky-rerun candidate row missing");
    const flakyLink = findTag(byId["app"], "a").find(a => a.href === "#/flaky");
    if (!flakyLink) throw new Error("flaky-rerun candidates must link to #/flaky");

    // Delivery-health degraded state (plan 059): no connector data → the honest "connect a CI
    // connector" notice; empty families → honest empty lines, never zeros read as data; the
    // flaky-rerun section is absent entirely.
    responses["/v1/rollups/delivery-health?days=30"] = {
        period: 30, changeFailureRate: [], timeToGreen: [], leadTime: [],
        retryTax: { chainCount: 0, rerunBuildIds: [], wastedCiMinutesLowerBound: 0.0, runAttemptReruns: 0, sameKeyCandidates: 0 },
        connectorDataAvailable: false, flakyRerunTax: [],
    };
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/delivery"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "need a CI connector")) throw new Error("connector-absent notice missing");
    if (!hasText(byId["app"], "Not enough finished builds")) throw new Error("CFR empty state missing");
    if (!hasText(byId["app"], "No failure-to-recovery episodes")) throw new Error("time-to-green empty state missing");
    if (!hasText(byId["app"], "No rerun chains detected")) throw new Error("retry-tax empty state missing");
    if (hasText(byId["app"], "Flaky-rerun candidates")) throw new Error("flaky-rerun section must be absent without candidates");

    // Empty rollups render without throwing (server-side never-fail analogue).
    responses["/v1/rollups/project-cost?days=30"] = [];
    responses["/v1/rollups/task-duration?days=30"] = { byName: [], byType: [], byTypeAvailable: false };
    responses["/v1/rollups/negative-avoidance?days=30"] = [];
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/tasks"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "No task data yet")) throw new Error("empty project-cost state missing");

    // Missing X-Total-Count → tolerated: the builds view still renders (total falls back).
    delete totals["/v1/builds?limit=50&offset=0"];
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    if (countTag(byId["app"], "table") === 0) throw new Error("builds view must tolerate a missing X-Total-Count");

    // Builds list (plan 033): an INTERRUPTED row carries the INTERRUPTED badge class, and the
    // outcome filter offers "interrupted".
    responses["/v1/builds?limit=50&offset=0"] = [
        { buildId: "int-1", startedAt: 1751450000000, durationMs: 0, outcome: "INTERRUPTED", mode: "LOCAL", branch: "main" },
    ];
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    if (findAll(byId["app"], n => (n.className || "").indexOf("INTERRUPTED") >= 0).length === 0) throw new Error("INTERRUPTED badge class missing in the builds list");
    if (findAll(byId["app"], n => n.tag === "option" && n.value === "interrupted").length === 0) throw new Error("interrupted outcome filter option missing");

    // Unfiltered empty project → get-started state: env-var snippet, never the token value.
    responses["/v1/builds?limit=50&offset=0"] = [];
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "BUILDHOUND_TOKEN")) throw new Error("get-started snippet missing the env-var provider");
    if (hasText(byId["app"], "s3cr3t-token-value")) throw new Error("get-started snippet must never contain the session token");

    // Admin/retention page (plan 042): its own admin-token slot (not the read token) loads the form
    // from GET, a valid PUT confirms "Saved.", and an inverted window shows the rejection — no throw.
    store["buildhound.adminToken"] = "admin-tok";
    context.location.hash = "#/admin"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/admin/retention")) throw new Error("admin view did not fetch retention");
    if (!hasText(byId["app"], "Retention settings")) throw new Error("admin header missing");
    const numInputs = () => findTag(byId["app"], "input").filter(i => i.type === "number");
    if (numInputs().length !== 2) throw new Error("admin form must render two number inputs from the GET config");
    numInputs()[0].value = "30"; numInputs()[1].value = "180";
    clickButton(byId["app"], "Save"); await tick(); await tick();
    if (!hasText(byId["app"], "Saved.")) throw new Error("admin valid save did not confirm");
    numInputs()[0].value = "200"; numInputs()[1].value = "100"; // buildDays < rawDays → rejected
    clickButton(byId["app"], "Save"); await tick(); await tick();
    if (!hasText(byId["app"], "Rejected")) throw new Error("admin invalid save did not show the validation error");

    console.log("dashboard smoke OK — fetched " + fetched.length + " request(s)");
})().catch(err => { console.error("SMOKE FAILURE:", err); process.exit(1); });
