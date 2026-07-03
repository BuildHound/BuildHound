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
    Date, Math, Object, String, Number, Array, Boolean, isFinite, Set, Error, console,
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

    // Minimal build (no tasks): ledger renders all-zero rows without dividing by zero.
    context.location.hash = "#/build/b2"; context._onhashchange(); await tick(); await tick();
    if (!fetched.includes("/v1/builds/b2")) throw new Error("minimal detail view did not fetch");
    if (!hasText(byId["app"], "0 tasks")) throw new Error("empty-tasks detail sentence missing");

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
