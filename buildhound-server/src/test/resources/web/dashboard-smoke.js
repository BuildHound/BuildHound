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
        kotlin: {
            reportSchema: "KOTLIN_2_4",
            truncatedTasks: 2,
            perTask: [
                { taskPath: ":app:compileKotlin", durationMs: 1168, incremental: false, nonIncrementalReasons: ["UNKNOWN_CHANGES_IN_GRADLE_INPUTS"], compilerTimesMs: { RUN_COMPILATION: 190, COMPILER_INITIALIZATION: 22 }, linesOfCode: 42 },
                { taskPath: ":lib:compileKotlin", durationMs: 300, incremental: true, compilerTimesMs: { RUN_COMPILATION: 80 } },
            ],
        },
        processes: [
            { role: "GRADLE_DAEMON", heapUsedMb: 1462, heapCommittedMb: 2048, heapMaxMb: 4096, configuredXmxMb: 4096, gcTimeMs: 3120, rssMb: 2711, uptimeS: 812 },
            { role: "KOTLIN_DAEMON", heapUsedMb: 640, configuredXmxMb: 2048 },
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
    "/v1/trends?days=30": [
        { day: "2026-06-30", builds: 3, failures: 1, avgDurationMs: 60000, avgHitRate: 0.5 },
        { day: "2026-07-01", builds: 2, failures: 0, avgDurationMs: null, avgHitRate: null },
    ],
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
    "/v1/rollups/task-duration?days=30": {
        byName: [{ key: "compileKotlin", count: 40, totalMs: 800000, avgMs: 20000, minMs: 1000, maxMs: 50000 }],
        byType: [],
        byTypeAvailable: false,
    },
    "/v1/rollups/negative-avoidance?days=30": [
        { key: "checkstyle", count: 5, totalExcessMs: 12000, worstExcessMs: 4000 },
    ],
};
// X-Total-Count values by path; a path absent here → header missing (tolerance case).
const totals = {
    "/v1/builds?limit=50&offset=0": 2,
    "/v1/builds?branch=none&limit=50&offset=0": 0,
};

const fetched = [];
async function fetchStub(path, opts) {
    fetched.push(path);
    if (!opts.headers.Authorization.startsWith("Bearer ")) throw new Error("no bearer header");
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
    if (!fetched.includes("/v1/builds?limit=50&offset=0")) throw new Error("builds view did not fetch after token save");
    if (!hasText(byId["app"], "2 builds")) throw new Error("builds count-summary sentence missing");

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
    // No process data on b2 → the panel is absent (renders only with data).
    if (hasText(byId["app"], "Process snapshot")) throw new Error("process panel must be absent without process data");

    // Tests page (plan 024): defaults to the latest build (b1), renders its tests panel.
    context.location.hash = "#/tests"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "Test results")) throw new Error("tests page header missing");
    if (countTag(byId["app"], "select") < 1) throw new Error("tests page build picker missing");
    if (!hasText(byId["app"], "totalsWithDiscount()")) throw new Error("tests page did not render the default build's failures");

    context.location.hash = "#/trends"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/trends?days=30")) throw new Error("trends view did not fetch");
    if (!hasText(byId["app"], "active day")) throw new Error("trends count-summary sentence missing");

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
    if (!hasText(byId["app"], "compileKotlin")) throw new Error("task-duration by-name row missing");
    if (!hasText(byId["app"], "checkstyle")) throw new Error("negative-avoidance row missing");
    clickButton(byId["app"], "By type");
    if (!hasText(byId["app"], "Task types not populated yet")) throw new Error("by-type empty state missing when byTypeAvailable is false");

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

    // Unfiltered empty project → get-started state: env-var snippet, never the token value.
    responses["/v1/builds?limit=50&offset=0"] = [];
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    if (!hasText(byId["app"], "BUILDHOUND_TOKEN")) throw new Error("get-started snippet missing the env-var provider");
    if (hasText(byId["app"], "s3cr3t-token-value")) throw new Error("get-started snippet must never contain the session token");

    console.log("dashboard smoke OK — fetched " + fetched.length + " request(s)");
})().catch(err => { console.error("SMOKE FAILURE:", err); process.exit(1); });
