// Smoke harness for the report template's render() IIFE (plan 045): runs the real inlined
// scripts from a rendered report against a minimal DOM stub and asserts the Failure + Warnings
// sections populate. ReportAssetsTest only checks string-splice invariants, so without this the
// report's render logic (a hand-copy of the dashboard's, which the dashboard smoke covers) ships
// untested — the plan-006 lesson: string assertions miss render bugs. argv[2] = rendered .html.
"use strict";
const fs = require("fs");
const vm = require("vm");

function makeNode(tag) {
    const node = {
        tag,
        children: [],
        hidden: false,
        _qs: null,
        set textContent(v) { node._text = String(v); node.children = []; },
        get textContent() { return node._text || ""; },
        set className(v) { node._class = v; },
        get className() { return node._class || ""; },
        classList: { add() {}, remove() {}, contains() { return false; } },
        append(...kids) { node.children.push(...kids); },
        appendChild(kid) { node.children.push(kid); return kid; },
        setAttribute() {},
        // Only the tasks table queries a tbody; return a stable stand-in so row appends don't throw.
        querySelector() { return node._qs || (node._qs = makeNode("tbody")); },
    };
    return node;
}

const byId = {};
const documentStub = {
    createElement: makeNode,
    createElementNS: (_ns, tag) => makeNode(tag),
    createDocumentFragment: () => makeNode("fragment"),
    // Auto-create + cache any static id the template's render path reaches.
    getElementById: id => byId[id] || (byId[id] = makeNode("div")),
};

function findAll(node, pred, out) {
    out = out || [];
    if (!node || typeof node !== "object") return out;
    if (pred(node)) out.push(node);
    for (const child of node.children || []) findAll(child, pred, out);
    return out;
}
const hasText = (node, sub) => findAll(node, n => (n.textContent || "").indexOf(sub) >= 0).length > 0;

const context = {
    document: documentStub,
    console,
    Date, Math, Object, String, Number, Array, Boolean, isFinite, Set, Map, Error,
};
context.globalThis = context;
vm.createContext(context);

// The browser runs both inline <script> blocks in order (timeline renderer, then the render IIFE
// which auto-executes on the embedded buildhoundData). Extract and run them the same way.
const html = fs.readFileSync(process.argv[2], "utf8");
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
if (scripts.length < 2) throw new Error("expected the timeline + render script blocks, found " + scripts.length);
scripts.forEach((src, i) => vm.runInContext(src, context, { filename: "report-script-" + i + ".js" }));

// Failure section (plan 044): exception class + message in the summary, stacktrace in the <pre>.
const failure = byId["failure"];
if (!failure || failure.hidden) throw new Error("failure section stayed hidden on a failed build");
if (!hasText(byId["failure-summary"], "org.gradle.api.GradleException")) throw new Error("failure exception class missing");
if (!hasText(byId["failure-summary"], "Execution failed for task ':app:compileKotlin'")) throw new Error("failure message missing");
const stack = byId["failure-stacktrace"];
if (!stack || stack.hidden) throw new Error("failure stacktrace <pre> stayed hidden");
if (!hasText(stack, "org.example.Widget.build(Widget.java:42)")) throw new Error("failure stacktrace body missing");

// Warnings section (plan 045): labelled deprecation + log-warning lists and the dropped-count note.
const warnings = byId["warnings"];
if (!warnings || warnings.hidden) throw new Error("warnings section stayed hidden");
const body = byId["warnings-body"];
if (!hasText(body, "Deprecations (1)")) throw new Error("deprecations count label missing");
if (!hasText(body, "The Foo API has been deprecated")) throw new Error("deprecation entry missing");
if (!hasText(body, "Log warnings (1)")) throw new Error("log-warnings count label missing");
if (!hasText(body, "bar() in Baz has been deprecated")) throw new Error("log-warning entry missing");
if (!hasText(body, "3 more warnings dropped past the cap")) throw new Error("dropped-warnings note missing");
if (findAll(body, n => n.tag === "ul" && (n.className || "").indexOf("warnings") >= 0).length === 0) throw new Error("warnings <ul> structure missing");

// Tests section, degraded state (plan 053): an empty `tests` block plus a populated `testTelemetry`
// still surfaces the honest "JUnit XML disabled" note — the section must not stay hidden just
// because `tests` itself is empty (that would silently read as "no tests ran").
const tests = byId["tests"];
if (!tests || tests.hidden) throw new Error("tests section stayed hidden with a populated testTelemetry block");
if (!hasText(byId["tests-degraded-note"], "Test telemetry unavailable")) throw new Error("degraded-note text missing");
if (!hasText(byId["tests-degraded-note"], ":app:test")) throw new Error("degraded-note task path missing");

// Daemon-tuning candidates (plan 065): the pinned Kotlin daemon (1900/2048 ≈ 93 %) fires the
// advisory card naming kotlin.daemon.jvmargs; the calm G1 Gradle daemon (0.4 % lifetime GC)
// fires neither the GC-pressure card nor the ParallelGC trial suggestion.
const tuning = byId["tuning-candidates"];
if (!tuning || tuning.hidden) throw new Error("tuning-candidates block stayed hidden with a pinned Kotlin daemon");
if (!hasText(tuning, "kotlin.daemon.jvmargs")) throw new Error("pinned-Xmx card must name kotlin.daemon.jvmargs");
if (hasText(tuning, "org.gradle.jvmargs")) throw new Error("no GC-pressure card may fire for the calm Gradle daemon");
if (hasText(tuning, "ParallelGC")) throw new Error("no ParallelGC trial may fire below the GC threshold");

console.log("report smoke OK");
