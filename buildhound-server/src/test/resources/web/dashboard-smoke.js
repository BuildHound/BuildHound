// Smoke harness for dashboard.js: minimal DOM stub, canned fetch responses, then
// drive all three views + detail routing and assert no exception escapes.
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
byId["token-input"].value = "  tok  ";

const documentStub = {
    createElement: makeNode,
    createElementNS: (ns, tag) => makeNode(tag),
    getElementById: id => byId[id] || null,
};

// Recursively count descendants (plus self) with a given tag — used to assert the
// timeline <svg> is (or is not) rendered into the detail view.
function countTag(node, tag) {
    if (!node || typeof node !== "object") return 0;
    let n = node.tag === tag ? 1 : 0;
    for (const child of node.children || []) n += countTag(child, tag);
    return n;
}

const store = {};
const responses = {
    "/v1/builds?limit=50&offset=0": [
        { buildId: "b1", startedAt: 1751450000000, durationMs: 65000, outcome: "SUCCESS", mode: "CI", branch: "main", hitRate: 0.5 },
        { buildId: "b2", startedAt: 1751450100000, durationMs: 900, outcome: "FAILED", mode: "LOCAL", branch: null },
    ],
    "/v1/builds/b1": {
        buildId: "b1", projectKey: "pilot", startedAt: 1751450000000, finishedAt: 1751450065000,
        outcome: "SUCCESS", mode: "CI",
        tasks: [
            { path: ":a", outcome: "EXECUTED", durationMs: 10, incremental: true },
            { path: ":b", outcome: "FROM_CACHE", durationMs: 500 },
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
};

const fetched = [];
async function fetchStub(path, opts) {
    fetched.push(path);
    if (!opts.headers.Authorization.startsWith("Bearer ")) throw new Error("no bearer header");
    const body = responses[path];
    if (body === undefined) return { ok: false, status: 404, json: async () => ({}) };
    return { ok: true, status: 200, json: async () => body };
}

const context = {
    document: documentStub,
    window: { addEventListener: (t, fn) => { context._onhashchange = fn; } },
    location: { hash: "" },
    sessionStorage: {
        getItem: k => (k in store ? store[k] : null),
        setItem: (k, v) => { store[k] = String(v); },
    },
    fetch: fetchStub,
    URLSearchParams,
    Option: function (label, value) { const n = makeNode("option"); n.textContent = label; n.value = value; return n; },
    Date, Math, Object, String, Number, Array, Boolean, isFinite, Error, console,
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
    await tick(); await tick();
    if (!fetched.includes("/v1/builds?limit=50&offset=0")) throw new Error("builds view did not fetch: " + fetched);

    context.location.hash = "#/build/b1";
    context._onhashchange();
    await tick(); await tick();
    if (!fetched.includes("/v1/builds/b1")) throw new Error("detail view did not fetch");
    // Timeline present (buildhoundTimeline loaded): b1 has tasks, so an <svg> must render.
    if (context.buildhoundTimeline && countTag(byId["app"], "svg") === 0) {
        throw new Error("detail view did not render the timeline svg");
    }

    context.location.hash = "#/build/b2"; // minimal payload — missing-keys defensiveness
    context._onhashchange();
    await tick(); await tick();
    if (!fetched.includes("/v1/builds/b2")) throw new Error("minimal detail view did not fetch");

    context.location.hash = "#/trends";
    context._onhashchange();
    await tick(); await tick();
    if (!fetched.includes("/v1/trends?days=30")) throw new Error("trends view did not fetch");

    // The error view must render, not throw, on API failure.
    context.location.hash = "#/build/missing";
    context._onhashchange();
    await tick(); await tick();

    // Timeline global absent → detail view must still render (graceful omission), no throw.
    // (A function-declared global is non-configurable, so overwrite rather than delete.)
    context.buildhoundTimeline = undefined;
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/build/b1"; context._onhashchange(); await tick(); await tick();
    if (countTag(byId["app"], "svg") !== 0) throw new Error("timeline must be omitted when the global is absent");
    if (countTag(byId["app"], "table") === 0) throw new Error("detail view must still render without the timeline global");

    // Throwing renderer → the detail view's try/catch swallows it and still renders the
    // rest of the page (the never-blank guarantee, exercised here rather than only asserted).
    context.buildhoundTimeline = function () { throw new Error("boom"); };
    context.location.hash = "#/builds"; context._onhashchange(); await tick(); await tick();
    context.location.hash = "#/build/b1"; context._onhashchange(); await tick(); await tick();
    if (countTag(byId["app"], "svg") !== 0) throw new Error("a throwing renderer must not leave a timeline svg");
    if (countTag(byId["app"], "table") === 0) throw new Error("detail view must survive a throwing renderer");

    // Token save handler wiring.
    byId["token-save"].listeners.click[0]();
    if (store["buildhound.token"] !== "tok") throw new Error("token not trimmed/saved: " + JSON.stringify(store));

    console.log("dashboard smoke OK — fetched: " + fetched.join(", "));
})().catch(err => { console.error("SMOKE FAILURE:", err); process.exit(1); });
