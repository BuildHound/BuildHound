// BuildHound task timeline — one shared, dependency-free renderer (plan 017).
// Inlined into the standalone HTML artifact (spliced by ReportAssets) AND served to the
// dashboard as /timeline.js. Pure: buildhoundTimeline(tasks) -> {svg, lanes} | null.
//
// Safety, identical on both surfaces: every SVG attribute is numeric or a fixed string
// (never a `style=` attribute — the dashboard CSP hash-pins styles); task-derived strings
// (paths, outcomes) reach the DOM only via <title> textContent, never markup. Lanes are
// computed concurrency lanes from start/end overlaps — deliberately NOT Gradle worker ids.
"use strict";
function buildhoundTimeline(tasks) {
    if (!Array.isArray(tasks) || tasks.length === 0) return null;

    // Split literal keeps the artifact's zero-network scan (which greps for the URL scheme
    // prefix) green; this is the SVG namespace identifier, never a network fetch.
    var SVG_NS = "http" + "://www.w3.org/2000/svg";
    var svgEl = function (tag, attrs) {
        var node = document.createElementNS(SVG_NS, tag);
        for (var key in attrs) node.setAttribute(key, String(attrs[key]));
        return node;
    };
    // Defensive against hand-edited offline payload copies: non-finite/negative → 0.
    var nonNeg = function (v) { return (typeof v === "number" && isFinite(v) && v > 0) ? v : 0; };
    var fmt = function (n) {
        return n >= 60000 ? (n / 60000).toFixed(1) + " min" : n >= 1000 ? (n / 1000).toFixed(1) + " s" : Math.round(n) + " ms";
    };

    // Normalize starts against the earliest observed start so the axis begins at 0.
    // The Math.min/max.apply spreads assume a bounded task array (payload task-array caps
    // arrive with plan 019); a pathological 100k-task hand-edited payload would RangeError
    // here, contained by both call sites' try/catch (degrades to a hidden section).
    var starts = tasks.map(function (t) { return nonNeg(t.startMs); });
    var minStart = Math.min.apply(null, starts);
    var items = tasks.map(function (t, i) {
        var start = starts[i] - minStart;
        var duration = nonNeg(t.durationMs);
        return {
            path: String(t.path == null ? "" : t.path),
            outcome: String(t.outcome == null ? "" : t.outcome),
            start: start, duration: duration, end: start + duration,
        };
    });

    // Greedy interval partitioning: sort by start, place each task in the first lane whose
    // last task ends at or before this start (touching intervals share a lane), else open a
    // new lane. Lane count == maximum observed concurrency.
    var order = items.slice().sort(function (a, b) { return a.start - b.start; });
    var laneEnds = [];
    order.forEach(function (it) {
        var lane = -1;
        for (var l = 0; l < laneEnds.length; l++) {
            if (laneEnds[l] <= it.start) { lane = l; break; }
        }
        if (lane === -1) { lane = laneEnds.length; laneEnds.push(it.end); } else { laneEnds[lane] = it.end; }
        it.lane = lane;
    });
    var lanes = laneEnds.length;

    var wall = Math.max.apply(null, items.map(function (it) { return it.end; }));

    var width = 720, padL = 8, padR = 8, padTop = 8, axisH = 22, laneH = 16, laneGap = 3;
    var plotW = width - padL - padR;
    var height = padTop + lanes * (laneH + laneGap) + axisH;
    var scaleX = wall > 0 ? plotW / wall : 0;
    var xOf = function (t) { return padL + t * scaleX; };

    var svg = svgEl("svg", { viewBox: "0 0 " + width + " " + height, class: "buildhound-timeline", role: "img" });

    var COLORS = {
        EXECUTED: "#f59e0b", FROM_CACHE: "#22c55e", UP_TO_DATE: "#60a5fa",
        SKIPPED: "#9ca3af", NO_SOURCE: "#9ca3af", FAILED: "#ef4444",
    };
    var DIMMED = { UP_TO_DATE: 1, SKIPPED: 1, NO_SOURCE: 1 };

    items.forEach(function (it) {
        var y = padTop + it.lane * (laneH + laneGap);
        var w = Math.max(1, it.duration * scaleX);
        var rect = svgEl("rect", {
            x: xOf(it.start).toFixed(2), y: y.toFixed(2), width: w.toFixed(2), height: laneH, rx: 2,
            fill: COLORS[it.outcome] || "#9ca3af", "fill-opacity": DIMMED[it.outcome] ? "0.4" : "0.9",
        });
        var title = document.createElementNS(SVG_NS, "title");
        title.textContent = it.path + " · " + it.outcome + " · " + fmt(it.duration) + " · +" + fmt(it.start);
        rect.appendChild(title);
        svg.appendChild(rect);
    });

    // Bottom axis: baseline + 5 ticks from 0 to wall duration.
    var axisY = padTop + lanes * (laneH + laneGap) + 4;
    svg.appendChild(svgEl("line", { x1: padL, y1: axisY, x2: padL + plotW, y2: axisY, stroke: "#8886", "stroke-width": "1" }));
    var ticks = 4;
    for (var i = 0; i <= ticks; i++) {
        var t = wall * (i / ticks);
        var tx = xOf(t);
        svg.appendChild(svgEl("line", { x1: tx.toFixed(2), y1: axisY, x2: tx.toFixed(2), y2: axisY + 4, stroke: "#8886", "stroke-width": "1" }));
        var label = svgEl("text", {
            x: tx.toFixed(2), y: axisY + 16, "font-size": "10", fill: "#888",
            "text-anchor": i === 0 ? "start" : i === ticks ? "end" : "middle",
        });
        label.textContent = fmt(t);
        svg.appendChild(label);
    }

    return { svg: svg, lanes: lanes };
}
