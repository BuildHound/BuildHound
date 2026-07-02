// BuildHound dashboard v0 (plan 012). Zero dependencies; every payload-derived string
// reaches the DOM via textContent only — payload data is untrusted in an HTML context.
"use strict";

(function () {
    const app = document.getElementById("app");
    const tokenBar = document.getElementById("token-bar");

    const token = () => sessionStorage.getItem("buildhound.token") || "";

    document.getElementById("token-save").addEventListener("click", () => {
        const input = document.getElementById("token-input");
        sessionStorage.setItem("buildhound.token", input.value.trim());
        input.value = ""; // don't leave the token sitting in the live DOM
        tokenBar.hidden = true;
        route();
    });

    // Outcome strings come from the server but are untrusted in principle; only
    // allowlisted values may become CSS class names (everything else renders unstyled).
    const BADGE_CLASSES = ["SUCCESS", "FAILED", "EXECUTED", "UP_TO_DATE", "FROM_CACHE", "SKIPPED", "NO_SOURCE"];
    const badgeClass = outcome => BADGE_CLASSES.includes(outcome) ? outcome : "";

    // Views are async; only the most recently started render may touch the DOM.
    let renderSeq = 0;

    const el = (tag, text, className) => {
        const node = document.createElement(tag);
        if (text !== undefined && text !== null) node.textContent = String(text);
        if (className) node.className = className;
        return node;
    };
    const ms = n => n >= 60000 ? (n / 60000).toFixed(1) + " min" : n >= 1000 ? (n / 1000).toFixed(1) + " s" : n + " ms";
    const when = t => new Date(t).toISOString().replace("T", " ").slice(0, 16) + "Z";

    async function api(path) {
        const response = await fetch(path, { headers: { Authorization: "Bearer " + token() } });
        if (response.status === 401 || response.status === 403) {
            tokenBar.hidden = false;
            throw new Error(response.status === 401 ? "token required" : "token lacks read scope");
        }
        if (!response.ok) throw new Error("request failed: " + response.status);
        return response.json();
    }

    function fail(err) {
        app.textContent = "";
        app.append(el("p", String(err && err.message || err), "error"));
    }

    function filterControls(current, onApply) {
        const bar = el("div", null, "filters");
        const branch = document.createElement("input");
        branch.placeholder = "branch";
        branch.value = current.branch || "";
        const mode = document.createElement("select");
        for (const value of ["", "ci", "local", "benchmark"]) mode.append(new Option(value || "any mode", value));
        mode.value = current.mode || "";
        const outcome = document.createElement("select");
        for (const value of ["", "success", "failed"]) outcome.append(new Option(value || "any outcome", value));
        outcome.value = current.outcome || "";
        const apply = el("button", "Apply");
        apply.addEventListener("click", () => onApply({
            branch: branch.value.trim(), mode: mode.value, outcome: outcome.value,
        }));
        bar.append(branch, mode, outcome, apply);
        return bar;
    }

    const query = filter => {
        const params = new URLSearchParams();
        if (filter.branch) params.set("branch", filter.branch);
        if (filter.mode) params.set("mode", filter.mode);
        if (filter.outcome) params.set("outcome", filter.outcome);
        return params;
    };

    async function buildsView(filter, offset) {
        const seq = ++renderSeq;
        const params = query(filter);
        params.set("limit", "50");
        params.set("offset", String(offset));
        const builds = await api("/v1/builds?" + params);
        if (seq !== renderSeq) return;

        app.textContent = "";
        app.append(filterControls(filter, next => buildsView(next, 0).catch(fail)));
        const table = el("table");
        const head = el("tr");
        for (const columnName of ["Started", "Outcome", "Duration", "Mode", "Branch", "Hit rate"]) head.append(el("th", columnName));
        table.append(head);
        for (const build of builds) {
            const row = el("tr", null, "row");
            row.append(el("td", when(build.startedAt)));
            row.append(el("td", build.outcome, badgeClass(build.outcome)));
            const durationCell = el("td", ms(build.durationMs), "num");
            row.append(durationCell);
            row.append(el("td", build.mode));
            row.append(el("td", build.branch || ""));
            row.append(el("td", build.hitRate == null ? "" : Math.round(build.hitRate * 100) + "%", "num"));
            row.addEventListener("click", () => { location.hash = "#/build/" + encodeURIComponent(build.buildId); });
            table.append(row);
        }
        app.append(table);
        if (!builds.length) app.append(el("p", "No builds.", "muted"));
        const pager = el("div", null, "filters");
        if (offset > 0) {
            const previous = el("button", "← Newer");
            previous.addEventListener("click", () => buildsView(filter, Math.max(0, offset - 50)).catch(fail));
            pager.append(previous);
        }
        if (builds.length === 50) {
            const next = el("button", "Older →");
            next.addEventListener("click", () => buildsView(filter, offset + 50).catch(fail));
            pager.append(next);
        }
        app.append(pager);
    }

    async function detailView(buildId) {
        const seq = ++renderSeq;
        const build = await api("/v1/builds/" + encodeURIComponent(buildId));
        if (seq !== renderSeq) return;
        app.textContent = "";
        const back = el("a", "← all builds");
        back.href = "#/builds";
        app.append(back);
        app.append(el("h2", (build.projectKey ? build.projectKey + " — " : "") + build.buildId));

        const chips = el("ul", null, "chips");
        const chip = (label, value) => { const item = el("li"); item.append(el("b", label + " ")); item.append(el("span", value)); chips.append(item); };
        chip("outcome", build.outcome);
        chip("duration", ms(build.finishedAt - build.startedAt));
        chip("mode", String(build.mode || "").toUpperCase()); // payload serial names are lowercase; the list shows enum names
        const tasks = build.tasks || [];
        chip("tasks", tasks.length);
        const derived = build.derived || {};
        if (derived.cacheableHitRate != null) chip("hit rate", Math.round(derived.cacheableHitRate * 100) + "%");
        const environment = build.environment || {};
        if (environment.configurationCache) chip("config cache", environment.configurationCache);
        const vcs = build.vcs || {};
        if (vcs.branch) chip("branch", vcs.branch);
        if (vcs.sha) chip("sha", vcs.sha.slice(0, 10));
        app.append(chips);

        // Cache summary: outcome counts double as the "cache summary" v0.
        const counts = {};
        for (const task of tasks) counts[task.outcome] = (counts[task.outcome] || 0) + 1;
        const summary = el("ul", null, "chips");
        for (const outcomeName of Object.keys(counts).sort()) {
            const item = el("li");
            item.append(el("span", outcomeName + " ", "badge " + badgeClass(outcomeName)));
            item.append(el("span", counts[outcomeName]));
            summary.append(item);
        }
        app.append(summary);

        const table = el("table");
        const head = el("tr");
        for (const columnName of ["Task", "Outcome", "Duration", "Incremental"]) head.append(el("th", columnName));
        table.append(head);
        for (const task of [...tasks].sort((a, b) => b.durationMs - a.durationMs)) {
            const row = el("tr");
            row.append(el("td", task.path));
            const outcomeCell = el("td");
            outcomeCell.append(el("span", task.outcome, "badge " + badgeClass(task.outcome)));
            row.append(outcomeCell);
            row.append(el("td", ms(task.durationMs), "num"));
            row.append(el("td", task.incremental ? "yes" : ""));
            table.append(row);
        }
        app.append(table);
    }

    const SVG_NS = "http://www.w3.org/2000/svg";
    const svgEl = (tag, attrs) => {
        const node = document.createElementNS(SVG_NS, tag);
        for (const key of Object.keys(attrs)) node.setAttribute(key, String(attrs[key]));
        return node;
    };

    function trendChart(points, valueOf, color, formatValue) {
        const width = 720, height = 160, pad = 30;
        const svg = svgEl("svg", { viewBox: "0 0 " + width + " " + height });
        const values = points.map(valueOf);
        const max = Math.max(...values.filter(v => v != null), 1);
        const stepX = points.length > 1 ? (width - 2 * pad) / (points.length - 1) : 0;
        const x = i => pad + i * stepX;
        const y = v => height - pad - (v / max) * (height - 2 * pad);
        svg.append(svgEl("line", { x1: pad, y1: height - pad, x2: width - pad, y2: height - pad, stroke: "#8886" }));
        let path = "";
        points.forEach((point, i) => {
            const value = valueOf(point);
            if (value == null) return;
            path += (path ? " L" : "M") + x(i).toFixed(1) + " " + y(value).toFixed(1);
            const dot = svgEl("circle", { cx: x(i).toFixed(1), cy: y(value).toFixed(1), r: 2.5, fill: color });
            const title = document.createElementNS(SVG_NS, "title");
            title.textContent = point.day + ": " + formatValue(value);
            dot.append(title);
            svg.append(dot);
        });
        if (path) svg.append(svgEl("path", { d: path, fill: "none", stroke: color, "stroke-width": 1.5 }));
        return svg;
    }

    async function trendsView(filter, days) {
        const seq = ++renderSeq;
        const params = query(filter);
        params.set("days", String(days));
        const points = await api("/v1/trends?" + params);
        if (seq !== renderSeq) return;

        app.textContent = "";
        app.append(filterControls(filter, next => trendsView(next, days).catch(fail)));
        const rangeBar = el("div", null, "filters");
        for (const range of [30, 90]) {
            const button = el("button", range + " days");
            button.addEventListener("click", () => trendsView(filter, range).catch(fail));
            rangeBar.append(button);
        }
        app.append(rangeBar);

        if (!points.length) { app.append(el("p", "No data in range.", "muted")); return; }
        const totals = points.reduce((acc, point) => acc + point.builds, 0);
        const failures = points.reduce((acc, point) => acc + point.failures, 0);
        const chips = el("ul", null, "chips");
        for (const [label, value] of [["builds", totals], ["failures", failures], ["days", points.length]]) {
            const item = el("li"); item.append(el("b", label + " ")); item.append(el("span", value)); chips.append(item);
        }
        app.append(chips);

        app.append(el("h3", "Average build duration"));
        app.append(trendChart(points, p => p.avgDurationMs, "#3b82f6", ms));
        app.append(el("h3", "Cache hit rate"));
        app.append(trendChart(points, p => p.avgHitRate == null ? null : p.avgHitRate * 100, "#22c55e", v => Math.round(v) + "%"));
        app.append(el("h3", "Builds per day (failures highlighted)"));
        const width = 720, height = 120, pad = 30;
        const bars = svgEl("svg", { viewBox: "0 0 " + width + " " + height });
        const maxBuilds = Math.max(...points.map(p => p.builds), 1);
        const barWidth = Math.max(2, (width - 2 * pad) / points.length - 2);
        points.forEach((point, i) => {
            const barHeight = (point.builds / maxBuilds) * (height - 2 * pad);
            const barX = pad + i * ((width - 2 * pad) / points.length);
            const bar = svgEl("rect", {
                x: barX.toFixed(1), y: (height - pad - barHeight).toFixed(1),
                width: barWidth.toFixed(1), height: barHeight.toFixed(1),
                fill: point.failures > 0 ? "#ef4444" : "#3b82f6",
            });
            const title = document.createElementNS(SVG_NS, "title");
            title.textContent = point.day + ": " + point.builds + " build(s), " + point.failures + " failure(s)";
            bar.append(title);
            bars.append(bar);
        });
        app.append(bars);
    }

    function route() {
        // decodeURIComponent throws synchronously on malformed input (Firefox returns
        // location.hash pre-decoded, so a stored %xx can arrive re-broken) — the try
        // must cover it, not just the promise.
        try {
            const hash = location.hash || "#/builds";
            const detail = hash.match(/^#\/build\/(.+)$/);
            const run = detail
                ? detailView(decodeURIComponent(detail[1]))
                : hash.startsWith("#/trends") ? trendsView({}, 30) : buildsView({}, 0);
            run.catch(fail);
        } catch (err) {
            fail(err);
        }
    }

    window.addEventListener("hashchange", route);
    route();
})();
