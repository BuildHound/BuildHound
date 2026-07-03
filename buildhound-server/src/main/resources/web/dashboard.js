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
    const chipItem = (label, value) => { const li = el("li"); li.append(el("b", label + " ")); li.append(el("span", value)); return li; };

    // Single place for the bearer header + auth-error handling, shared by api()/apiList().
    async function authedFetch(path) {
        const response = await fetch(path, { headers: { Authorization: "Bearer " + token() } });
        if (response.status === 401 || response.status === 403) {
            tokenBar.hidden = false;
            throw new Error(response.status === 401 ? "token required" : "token lacks read scope");
        }
        if (!response.ok) throw new Error("request failed: " + response.status);
        return response;
    }

    async function api(path) {
        return (await authedFetch(path)).json();
    }

    function fail(err) {
        app.textContent = "";
        app.append(el("p", String(err && err.message || err), "error"));
    }

    // Like api() but also reads the filter-aware total from X-Total-Count (plan 018).
    // Tolerates a missing/non-numeric header (older server, or a stub) → total null, so
    // callers degrade to the pre-018 page-length behaviour.
    async function apiList(path) {
        const response = await authedFetch(path);
        const items = await response.json();
        const raw = response.headers && response.headers.get ? response.headers.get("X-Total-Count") : null;
        const parsed = raw == null ? NaN : Number(raw);
        return { items: items, total: Number.isFinite(parsed) ? parsed : null };
    }

    const filterIsActive = filter => !!(filter.branch || filter.mode || filter.outcome);

    // Env-var provider only — never a literal token (architecture §6); uses this server's
    // own origin so the copy-paste URL is correct wherever the dashboard is hosted.
    function getStartedSnippet() {
        return [
            'plugins {',
            '    id("dev.buildhound") version "…"',
            '}',
            '',
            'buildhound {',
            '    server {',
            '        url = "' + location.origin + '"',
            '        token = providers.environmentVariable("BUILDHOUND_TOKEN")',
            '    }',
            '}',
        ].join("\n");
    }

    // Shared contextual empty state (Tuist pattern): a filtered miss offers clear-filters;
    // an unfiltered miss is the get-started state with the plugin snippet + docs link. All
    // content is static copy or location.origin via textContent — the no-innerHTML rule holds.
    function emptyState(opts) {
        const box = el("div", null, "empty");
        box.append(el("h3", opts.title));
        for (const line of opts.lines) box.append(el("p", line, "muted"));
        if (opts.snippet) {
            box.append(el("pre", getStartedSnippet(), "snippet"));
            const link = el("a", "Read the docs at buildhound.dev");
            link.href = "https://buildhound.dev/docs";
            box.append(link);
        }
        if (opts.onClear) {
            const clear = el("button", "Clear filters");
            clear.addEventListener("click", opts.onClear);
            box.append(clear);
        }
        return box;
    }

    // No stored token yet: a friendly first-run panel, not a failed request + red error.
    function firstRunView() {
        tokenBar.hidden = false;
        app.textContent = "";
        app.append(el("p", "Welcome to BuildHound", "summary-sentence"));
        app.append(emptyState({
            title: "Add a read token to get started",
            lines: [
                "Paste an API token with read scope above to view builds and trends.",
                "It is kept in this browser tab only (sessionStorage).",
            ],
        }));
    }

    // Work-avoidance ledger (Develocity pattern): every outcome category rendered with an
    // explicit zero so layouts stay comparable across builds. Percentages are share-of-all
    // -tasks — deliberately distinct from the derived hit-rate chip's cacheable-only one.
    function avoidanceLedger(tasks) {
        const total = tasks.length;
        const dur = list => list.reduce((sum, t) => sum + (t.durationMs || 0), 0);
        const of = pred => tasks.filter(pred);
        const fromCache = of(t => t.outcome === "FROM_CACHE");
        const upToDate = of(t => t.outcome === "UP_TO_DATE");
        const executed = of(t => t.outcome === "EXECUTED");
        const rows = [
            { label: "All tasks", list: tasks },
            { label: "Avoided", list: fromCache.concat(upToDate) },
            { label: "From cache", list: fromCache, badge: "FROM_CACHE", child: true },
            { label: "Up to date", list: upToDate, badge: "UP_TO_DATE", child: true },
            { label: "Executed", list: executed, badge: "EXECUTED" },
            { label: "Cacheable", list: executed.filter(t => t.cacheable === true), child: true },
            { label: "Not cacheable", list: executed.filter(t => t.cacheable === false), child: true },
            { label: "Unknown cacheability", list: executed.filter(t => t.cacheable == null), child: true },
            { label: "Failed", list: of(t => t.outcome === "FAILED"), badge: "FAILED" },
            { label: "Skipped", list: of(t => t.outcome === "SKIPPED"), badge: "SKIPPED" },
            { label: "No source", list: of(t => t.outcome === "NO_SOURCE"), badge: "NO_SOURCE" },
        ];
        return rows.map(r => ({
            label: r.label, badge: r.badge, child: r.child || false,
            count: r.list.length, durationMs: dur(r.list),
            pct: total === 0 ? 0 : (r.list.length / total) * 100,
        }));
    }

    function ledgerTable(tasks) {
        const table = el("table", null, "ledger");
        const head = el("tr");
        for (const columnName of ["Category", "Count", "Share", "Task time"]) head.append(el("th", columnName));
        table.append(head);
        for (const r of avoidanceLedger(tasks)) {
            const row = el("tr", null, r.child ? "child" : null);
            const labelCell = el("td");
            labelCell.append(r.badge ? el("span", r.label, "badge " + badgeClass(r.badge)) : el("span", r.label));
            row.append(labelCell);
            row.append(el("td", r.count, "num"));
            row.append(el("td", r.pct.toFixed(1) + "%", "num"));
            row.append(el("td", ms(r.durationMs), "num"));
            table.append(row);
        }
        return table;
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

    function buildsSentence(filter, total, pageCount) {
        const n = total != null ? total : pageCount;
        let text = n + (n === 1 ? " build" : " builds");
        if (filter.branch) text += " on " + filter.branch;
        const quals = [];
        if (filter.mode) quals.push(filter.mode);
        if (filter.outcome) quals.push(filter.outcome);
        if (quals.length) text += " (" + quals.join(", ") + ")";
        return el("p", text, "summary-sentence");
    }

    async function buildsView(filter, offset) {
        const seq = ++renderSeq;
        const params = query(filter);
        params.set("limit", "50");
        params.set("offset", String(offset));
        const listResult = await apiList("/v1/builds?" + params);
        const builds = listResult.items;
        const total = listResult.total;
        if (seq !== renderSeq) return;

        app.textContent = "";

        // Offset-0 empty: a contextual empty state, not a bare "No builds." line.
        if (!builds.length && offset === 0) {
            if (filterIsActive(filter)) {
                app.append(filterControls(filter, next => buildsView(next, 0).catch(fail)));
                app.append(emptyState({
                    title: "No builds match this filter",
                    lines: ["Try a different branch, mode, or outcome."],
                    onClear: () => buildsView({}, 0).catch(fail),
                }));
            } else {
                app.append(emptyState({
                    title: "Send your first build",
                    lines: ["Apply the settings plugin and point it at this server:"],
                    snippet: true,
                }));
            }
            return;
        }

        app.append(buildsSentence(filter, total, builds.length));
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
        const pager = el("div", null, "filters");
        if (offset > 0) {
            const previous = el("button", "← Newer");
            previous.addEventListener("click", () => buildsView(filter, Math.max(0, offset - 50)).catch(fail));
            pager.append(previous);
        }
        // With a known total, only offer "Older" when more rows exist — removes plan 012's
        // dead click on exact multiples of 50; fall back to the page-full heuristic otherwise.
        const hasMore = total != null ? offset + builds.length < total : builds.length === 50;
        if (hasMore) {
            const next = el("button", "Older →");
            next.addEventListener("click", () => buildsView(filter, offset + 50).catch(fail));
            pager.append(next);
        }
        app.append(pager);
    }

    // Kotlin build-report panel (plan 023): compiler metrics bundled from the KGP json report.
    // Rendered only when at least one compilation record is present; every value reaches the DOM
    // via el()'s textContent, keeping the no-innerHTML rule (report free-text is untrusted).
    function kotlinPanel(kotlin) {
        const box = document.createDocumentFragment();
        const perTask = Array.isArray(kotlin.perTask) ? kotlin.perTask : [];
        box.append(el("h3", "Kotlin compilation"));

        const chips = el("ul", null, "chips");
        chips.append(chipItem("compilations", perTask.length));
        const known = perTask.filter(t => t.incremental != null);
        const incremental = known.filter(t => t.incremental === true).length;
        chips.append(chipItem("incremental", known.length ? incremental + " / " + known.length : "unknown"));
        // Effectiveness by time, not just count: share of known compile time that ran incrementally.
        const timed = known.filter(t => t.durationMs != null);
        const totalMs = timed.reduce((sum, t) => sum + t.durationMs, 0);
        const incrementalMs = timed.filter(t => t.incremental === true).reduce((sum, t) => sum + t.durationMs, 0);
        chips.append(chipItem("incremental time", totalMs ? Math.round((incrementalMs / totalMs) * 100) + "%" : "unknown"));
        if (kotlin.reportSchema) chips.append(chipItem("report", kotlin.reportSchema));
        if (kotlin.truncatedTasks) chips.append(chipItem("not shown", "+" + kotlin.truncatedTasks));
        box.append(chips);

        // Slowest compilations, with the incremental verdict and (when non-incremental) why.
        const slowest = [...perTask].sort((a, b) => (b.durationMs || 0) - (a.durationMs || 0));
        const shown = slowest.slice(0, 15);
        const table = el("table");
        const head = el("tr");
        for (const columnName of ["Compilation", "Duration", "Incremental", "Lines", "Why not incremental"]) head.append(el("th", columnName));
        table.append(head);
        for (const t of shown) {
            const row = el("tr");
            row.append(el("td", t.taskPath));
            row.append(el("td", t.durationMs == null ? "" : ms(t.durationMs), "num"));
            row.append(el("td", t.incremental == null ? "" : (t.incremental ? "yes" : "no")));
            row.append(el("td", t.linesOfCode == null ? "" : String(t.linesOfCode), "num"));
            row.append(el("td", (t.nonIncrementalReasons || []).join(", ")));
            table.append(row);
        }
        box.append(table);
        if (slowest.length > shown.length) {
            box.append(el("p", (slowest.length - shown.length) + " more compilations not shown", "muted"));
        }

        // Compiler phase time summed across every compilation in this build.
        const totals = {};
        for (const t of perTask) {
            const phases = t.compilerTimesMs || {};
            for (const name of Object.keys(phases)) totals[name] = (totals[name] || 0) + (phases[name] || 0);
        }
        const phaseNames = Object.keys(totals).sort((a, b) => totals[b] - totals[a]);
        if (phaseNames.length) {
            box.append(el("h4", "Compiler phase time (all compilations)"));
            const phaseTable = el("table");
            const phaseHead = el("tr");
            phaseHead.append(el("th", "Phase"));
            phaseHead.append(el("th", "Time"));
            phaseTable.append(phaseHead);
            for (const name of phaseNames) {
                const row = el("tr");
                row.append(el("td", name));
                row.append(el("td", ms(totals[name]), "num"));
                phaseTable.append(row);
            }
            box.append(phaseTable);
        }
        return box;
    }

    // Test-results panel (plan 024): summary sentence, failures/retries table, slowest classes.
    // Shared by the build detail and the Tests page. textContent-only (message text is untrusted).
    function testsPanel(tests) {
        const box = document.createDocumentFragment();
        box.append(el("h3", "Tests"));

        let cases = 0, classCount = 0, failed = 0, skipped = 0;
        const allClasses = [];
        const failures = [];
        for (const tt of tests) {
            for (const c of tt.classes || []) {
                classCount++;
                cases += (c.passed || 0) + (c.failed || 0) + (c.skipped || 0);
                failed += (c.failed || 0);
                skipped += (c.skipped || 0);
                allClasses.push(c);
            }
            for (const f of tt.failedOrRetried || []) failures.push(f);
        }
        box.append(el("p",
            cases + (cases === 1 ? " test" : " tests") + " in " + classCount + (classCount === 1 ? " class" : " classes")
            + " across " + tests.length + (tests.length === 1 ? " test task" : " test tasks")
            + " — " + failed + " failed, " + skipped + " skipped",
            "summary-sentence"));

        if (failures.length) {
            box.append(el("h4", "Failures & retries"));
            const table = el("table");
            const head = el("tr");
            for (const columnName of ["Class", "Test", "Outcome", "Duration", "Message"]) head.append(el("th", columnName));
            table.append(head);
            for (const f of failures) {
                const row = el("tr");
                row.append(el("td", f.className));
                row.append(el("td", f.name));
                row.append(el("td", (f.outcomes || []).join(" → ")));
                row.append(el("td", ms(f.durationMs || 0), "num"));
                row.append(el("td", f.message || ""));
                table.append(row);
            }
            box.append(table);
        }

        box.append(el("h4", "Slowest classes"));
        const table = el("table");
        const head = el("tr");
        for (const columnName of ["Class", "Passed", "Failed", "Skipped", "Duration"]) head.append(el("th", columnName));
        table.append(head);
        for (const c of [...allClasses].sort((a, b) => (b.durationMs || 0) - (a.durationMs || 0)).slice(0, 20)) {
            const row = el("tr");
            row.append(el("td", c.className));
            row.append(el("td", c.passed || 0, "num"));
            row.append(el("td", c.failed || 0, "num"));
            row.append(el("td", c.skipped || 0, "num"));
            row.append(el("td", ms(c.durationMs || 0), "num"));
            table.append(row);
        }
        box.append(table);
        return box;
    }

    // Tests page (plan 024): a per-build-scoped view — pick a build, show its tests. Fleet-wide
    // slowest/flaky trends are plan 026/036. No new server route; reads the existing query API.
    async function testsView() {
        const seq = ++renderSeq;
        const { items: builds } = await apiList("/v1/builds?limit=50&offset=0");
        if (seq !== renderSeq) return;

        app.textContent = "";
        app.append(el("p", "Test results", "summary-sentence"));
        if (!builds.length) {
            app.append(emptyState({
                title: "No test results ingested yet",
                lines: ["Send a build that runs tests to see per-class results and failures here."],
            }));
            return;
        }
        const label = b => when(b.startedAt) + " · " + b.outcome + " · " + (b.branch || "no branch") + " · " + b.mode;
        const select = document.createElement("select");
        for (const build of builds) select.append(new Option(label(build), build.buildId));
        const bar = el("div", null, "filters");
        bar.append(el("span", "Build: "), select);
        app.append(bar);
        const holder = el("div");
        app.append(holder);

        async function show(buildId) {
            const mySeq = ++renderSeq;
            const build = await api("/v1/builds/" + encodeURIComponent(buildId));
            if (mySeq !== renderSeq) return;
            holder.textContent = "";
            if (Array.isArray(build.tests) && build.tests.length) {
                holder.append(testsPanel(build.tests));
            } else {
                holder.append(emptyState({
                    title: "No test results in this build",
                    lines: ["Pick another build, or run a build that executes tests."],
                }));
            }
        }
        select.addEventListener("change", () => show(select.value).catch(fail));
        await show(builds[0].buildId);
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

        // Count-summary sentence (task time, since parallel sums exceed wall clock).
        const moduleCount = new Set(tasks.map(t => t.module).filter(m => m)).size;
        const taskTime = tasks.reduce((sum, t) => sum + (t.durationMs || 0), 0);
        app.append(el("p",
            tasks.length + (tasks.length === 1 ? " task" : " tasks")
            + " in " + moduleCount + (moduleCount === 1 ? " module" : " modules")
            + " — " + ms(taskTime) + " total task time",
            "summary-sentence"));

        // Work-avoidance ledger replaces the v0 outcome-count chips: every category with
        // explicit zeros, share-of-all-tasks percentages, summed task time.
        app.append(el("h3", "Work avoidance"));
        app.append(ledgerTable(tasks));

        // Task timeline (plan 017): the same renderer the HTML artifact inlines, served at
        // /timeline.js. Best-effort — a renderer defect or a missing global degrades to no
        // section, never a blank detail page (client-side analogue of the never-fail rule).
        try {
            const timeline = typeof buildhoundTimeline === "function" ? buildhoundTimeline(tasks) : null;
            if (timeline) {
                app.append(el("h3", "Timeline"));
                app.append(el("p", "max parallel " + timeline.lanes, "muted"));
                app.append(timeline.svg);
            }
        } catch (e) { /* keep the rest of the detail page */ }

        // Kotlin compiler metrics, when the KGP json report was bundled for this build.
        const kotlin = build.kotlin;
        if (kotlin && Array.isArray(kotlin.perTask) && kotlin.perTask.length) {
            app.append(kotlinPanel(kotlin));
        }

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

        // Test results, when any test task's JUnit XML was collected for this build.
        if (Array.isArray(build.tests) && build.tests.length) app.append(testsPanel(build.tests));
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

        if (!points.length) {
            app.append(emptyState({
                title: "No builds in the last " + days + " days",
                lines: [filterIsActive(filter)
                    ? "No matching builds in this range."
                    : "Send builds to see duration and cache-hit trends here."],
                onClear: filterIsActive(filter) ? (() => trendsView({}, days).catch(fail)) : undefined,
            }));
            return;
        }
        const totalBuilds = points.reduce((acc, point) => acc + point.builds, 0);
        const failures = points.reduce((acc, point) => acc + point.failures, 0);
        app.append(el("p",
            totalBuilds + (totalBuilds === 1 ? " build" : " builds")
            + " with " + failures + (failures === 1 ? " failure" : " failures")
            + " across " + points.length + (points.length === 1 ? " active day" : " active days")
            + " in the last " + days + " days",
            "summary-sentence"));

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

    // Comparisons (plan 022): pick two builds, then explain B's cache misses vs A by input diff.
    async function compareView() {
        const seq = ++renderSeq;
        const { items: builds } = await apiList("/v1/builds?limit=50&offset=0");
        if (seq !== renderSeq) return;

        app.textContent = "";
        app.append(el("p", "Compare two builds", "summary-sentence"));
        if (!builds.length) {
            app.append(emptyState({ title: "No builds to compare", lines: ["Send builds first, then pick two here."] }));
            return;
        }
        const label = b => when(b.startedAt) + " · " + b.outcome + " · " + (b.branch || "no branch") + " · " + b.mode;
        const selectA = document.createElement("select");
        const selectB = document.createElement("select");
        for (const build of builds) {
            selectA.append(new Option(label(build), build.buildId));
            selectB.append(new Option(label(build), build.buildId));
        }
        if (builds.length > 1) selectB.value = builds[1].buildId;
        const bar = el("div", null, "filters");
        bar.append(el("span", "A (fast / cached): "), selectA, el("span", "  B (missed): "), selectB);
        const go = el("button", "Compare");
        go.addEventListener("click", () => {
            if (selectA.value && selectB.value) {
                location.hash = "#/compare/" + encodeURIComponent(selectA.value) + "/" + encodeURIComponent(selectB.value);
            }
        });
        bar.append(go);
        app.append(bar);
    }

    function refChips(ref) {
        const ul = el("ul", null, "chips");
        ul.append(chipItem("build", ref.buildId.slice(0, 12)));
        ul.append(chipItem("started", when(ref.startedAt)));
        ul.append(chipItem("outcome", ref.outcome));
        ul.append(chipItem("mode", ref.mode));
        if (ref.branch) ul.append(chipItem("branch", ref.branch));
        if (ref.sha) ul.append(chipItem("sha", ref.sha.slice(0, 10)));
        return ul;
    }

    async function comparisonView(idA, idB) {
        const seq = ++renderSeq;
        const result = await api("/v1/builds/" + encodeURIComponent(idA) + "/compare/" + encodeURIComponent(idB));
        if (seq !== renderSeq) return;

        app.textContent = "";
        const back = el("a", "← pick builds");
        back.href = "#/compare";
        app.append(back);
        app.append(el("h2", "Comparison"));
        app.append(el("h3", "A (fast / cached)"));
        app.append(refChips(result.a));
        app.append(el("h3", "B (missed)"));
        app.append(refChips(result.b));
        if (!result.requestedTasksMatch) {
            app.append(el("p", "⚠ These builds ran different requested tasks — the comparison may be misleading.", "error"));
        }

        app.append(el("h3", "Changed inputs"));
        if (!result.diffs.length) {
            app.append(emptyState({
                title: "No differing inputs captured",
                lines: ["Enable fingerprints {} in the plugin to explain cache misses by input value."],
            }));
        } else {
            const table = el("table");
            const head = el("tr");
            for (const columnName of ["Input", "Scope", "Coverage", "A", "B", "Why"]) head.append(el("th", columnName));
            table.append(head);
            for (const diff of result.diffs) {
                const row = el("tr");
                row.append(el("td", diff.key));
                row.append(el("td", diff.scope));
                row.append(el("td", Math.round((diff.coverage || 0) * 100) + "%", "num"));
                row.append(el("td", diff.valueA == null ? "—" : diff.valueA));
                row.append(el("td", diff.valueB == null ? "—" : diff.valueB));
                row.append(el("td", diff.note || ""));
                table.append(row);
            }
            app.append(table);
        }

        app.append(el("h3", "Cache misses in B"));
        if (!result.missesToExplain.length) {
            app.append(el("p", "No cache misses to explain — B avoided the same work as A.", "muted"));
        } else {
            const list = el("ul");
            for (const path of result.missesToExplain) list.append(el("li", path));
            app.append(list);
        }
    }

    function route() {
        // decodeURIComponent throws synchronously on malformed input (Firefox returns
        // location.hash pre-decoded, so a stored %xx can arrive re-broken) — the try
        // must cover it, not just the promise.
        try {
            // No token yet → a friendly first-run panel instead of a request that 401s
            // and paints red error text as the first thing a pilot user sees (plan 018).
            if (!token()) { firstRunView(); return; }
            const hash = location.hash || "#/builds";
            const detail = hash.match(/^#\/build\/(.+)$/);
            const compare = hash.match(/^#\/compare\/([^/]+)\/(.+)$/);
            const run = detail ? detailView(decodeURIComponent(detail[1]))
                : compare ? comparisonView(decodeURIComponent(compare[1]), decodeURIComponent(compare[2]))
                : hash.startsWith("#/compare") ? compareView()
                : hash.startsWith("#/trends") ? trendsView({}, 30)
                : hash.startsWith("#/tests") ? testsView()
                : buildsView({}, 0);
            run.catch(fail);
        } catch (err) {
            fail(err);
        }
    }

    window.addEventListener("hashchange", route);
    route();
})();
