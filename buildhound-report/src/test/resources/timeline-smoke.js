// Smoke harness for timeline.js (plan 017): loads the renderer in a real JS engine against
// a minimal DOM stub and drives the pure lane algorithm + SVG output, so a SyntaxError or a
// lane-math regression can't ship green (the plan-006 lesson). Node-only; skipped when absent.
"use strict";
const fs = require("fs");
const vm = require("vm");

function makeNode(tag) {
    const node = {
        tag, children: [], attrs: {},
        set textContent(v) { node._text = String(v); },
        get textContent() { return node._text || ""; },
        setAttribute(k, v) { node.attrs[k] = v; },
        appendChild(kid) { node.children.push(kid); return kid; },
        append() { for (let i = 0; i < arguments.length; i++) node.children.push(arguments[i]); },
    };
    return node;
}

const context = {
    document: { createElementNS: (ns, tag) => makeNode(tag) },
    Math, Array, Number, String, isFinite, console,
};
context.globalThis = context;
vm.createContext(context);
vm.runInContext(fs.readFileSync(process.argv[2], "utf8"), context, { filename: "timeline.js" });

const timeline = context.buildhoundTimeline;
if (typeof timeline !== "function") { console.error("buildhoundTimeline global not defined"); process.exit(1); }

function assert(cond, msg) { if (!cond) throw new Error(msg); }
function countTag(node, tag) {
    let n = node.tag === tag ? 1 : 0;
    for (const child of node.children || []) n += countTag(child, tag);
    return n;
}
const task = (path, outcome, startMs, durationMs) => ({ path, outcome, startMs, durationMs });

(function () {
    // Empty / non-array inputs → null.
    assert(timeline([]) === null, "empty array must return null");
    assert(timeline("nope") === null, "non-array must return null");
    assert(timeline(undefined) === null, "undefined must return null");

    // Sequential tasks collapse to one lane.
    const seq = timeline([task(":a", "EXECUTED", 0, 100), task(":b", "EXECUTED", 100, 100)]);
    assert(seq.lanes === 1, "sequential tasks share one lane, got " + seq.lanes);

    // Touching intervals (end === next start) share a lane.
    const touch = timeline([task(":a", "EXECUTED", 0, 100), task(":b", "EXECUTED", 100, 50)]);
    assert(touch.lanes === 1, "touching intervals share a lane, got " + touch.lanes);

    // Overlapping intervals open a second lane; one rect + one title per task.
    const overlap = timeline([task(":a", "EXECUTED", 0, 100), task(":b", "FROM_CACHE", 50, 100)]);
    assert(overlap.lanes === 2, "overlapping tasks need two lanes, got " + overlap.lanes);
    assert(countTag(overlap.svg, "svg") === 1, "returns one <svg>");
    assert(countTag(overlap.svg, "rect") === 2, "one <rect> per task");
    assert(countTag(overlap.svg, "title") === 2, "one <title> per task");
    assert(countTag(overlap.svg, "text") >= 2, "axis tick labels present");

    // Zero-duration task still renders a (min-width) bar without throwing.
    const zero = timeline([task(":z", "EXECUTED", 0, 0)]);
    assert(zero.lanes === 1 && countTag(zero.svg, "rect") === 1, "zero-duration task still renders a bar");

    // Hostile input: negative + non-finite values, then 5000 tasks — must render, not throw.
    const hostile = [task(":neg", "EXECUTED", -5, -10), task(":nan", "FROM_CACHE", NaN, Infinity)];
    for (let i = 0; i < 5000; i++) hostile.push(task(":t" + i, "EXECUTED", i, 1));
    const big = timeline(hostile);
    assert(big && countTag(big.svg, "rect") === hostile.length, "hostile input renders every bar without throwing");

    console.log("timeline smoke OK — lanes seq=1 touch=1 overlap=2, rects=" + hostile.length);
})();
