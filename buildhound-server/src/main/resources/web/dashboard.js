// BuildHound dashboard v0 (plan 012). Zero dependencies; every payload-derived string
// reaches the DOM via textContent only — payload data is untrusted in an HTML context.
"use strict";

(function () {
    const app = document.getElementById("app");
    const tokenBar = document.getElementById("token-bar");

    const token = () => sessionStorage.getItem("buildhound.token") || "";

    // Payload projectKey selector (plan 077): a per-tenant filter over which repo's builds
    // to show, separate from the auth token. "" means "All projects" — every query-building
    // helper treats an empty string the same as absent, so unset selections stay byte-identical
    // to pre-077 URLs.
    const PROJECT_KEY_STORAGE = "buildhound.projectKey";
    const projectKey = () => sessionStorage.getItem(PROJECT_KEY_STORAGE) || "";
    const projectSelect = document.getElementById("project-select");

    // The single mutation point for the project selection (plan 079): storage, the visible
    // select, AND cross-view state that is scoped to one project's data. A tag cohort picked
    // under one project may not exist in another project's tag set, so any selection change
    // resets the trends page's "split by tag" state.
    function setProjectSelection(value) {
        sessionStorage.setItem(PROJECT_KEY_STORAGE, value);
        if (projectSelect) projectSelect.value = value;
        selectedTagKey = ""; // declared below beside renderSeq; safe — only ever called from handlers, after script evaluation
    }

    function resetProjectSelection() { setProjectSelection(""); }

    // Only the most recently started populate call may touch the selector — mirrors renderSeq.
    let projectSelectSeq = 0;

    // Populates the header selector from /v1/project-keys. Best-effort (plan 077): a 401/403,
    // network error, or fewer than two distinct keys all just leave the selector hidden — this
    // must never turn into a page-breaking error, only the read views 401 loudly.
    async function populateProjectSelect() {
        const seq = ++projectSelectSeq;
        if (!projectSelect) return;
        let keys;
        try {
            keys = await api("/v1/project-keys");
        } catch (e) {
            if (seq === projectSelectSeq) projectSelect.hidden = true;
            return;
        }
        if (seq !== projectSelectSeq) return;
        if (!Array.isArray(keys) || keys.length < 2) {
            projectSelect.hidden = true;
            // <2 keys removes the selector UI: a lingering stored selection would keep every view
            // invisibly filtered with nothing to clear it. Even when the one remaining key IS the
            // stored key, filtering is not "all projects" — it hides pre-077 null-projectKey builds.
            if (projectKey()) { resetProjectSelection(); route(); }
            return;
        }
        const current = projectKey();
        projectSelect.textContent = "";
        projectSelect.append(new Option("All projects", ""));
        for (const k of keys) {
            const count = k.builds + (k.builds === 1 ? " build" : " builds");
            projectSelect.append(new Option(k.projectKey + " (" + count + ")", k.projectKey));
        }
        // If the previously-selected key vanished from a fresh enumeration, fall back to "All
        // projects" in BOTH the visible select and sessionStorage — query-building helpers read
        // sessionStorage directly, so leaving it stale would filter data the UI no longer shows —
        // then re-route so the visible data matches. The changed-only condition is load-bearing:
        // an unchanged selection must not re-route (route() already ran alongside this populate;
        // renderSeq makes an extra render safe, but it would double every startup fetch).
        const resolved = keys.some(k => k.projectKey === current) ? current : "";
        projectSelect.value = resolved;
        projectSelect.hidden = false;
        if (resolved !== current) { setProjectSelection(resolved); route(); }
    }

    if (projectSelect) {
        projectSelect.addEventListener("change", () => {
            setProjectSelection(projectSelect.value);
            route();
        });
    }

    document.getElementById("token-save").addEventListener("click", () => {
        const input = document.getElementById("token-input");
        sessionStorage.setItem("buildhound.token", input.value.trim());
        input.value = ""; // don't leave the token sitting in the live DOM
        tokenBar.hidden = true;
        populateProjectSelect().catch(() => {});
        route();
    });

    // Outcome strings come from the server but are untrusted in principle; only
    // allowlisted values may become CSS class names (everything else renders unstyled).
    const BADGE_CLASSES = ["SUCCESS", "FAILED", "INTERRUPTED", "EXECUTED", "UP_TO_DATE", "FROM_CACHE", "SKIPPED", "NO_SOURCE"];
    const badgeClass = outcome => BADGE_CLASSES.includes(outcome) ? outcome : "";

    // Views are async; only the most recently started render may touch the DOM.
    let renderSeq = 0;

    // The trends page's "split by tag" picker (plan 057) persists across re-renders of that page
    // (range toggle, filter apply) the same way the token/filter state does — reset by reloading
    // the page or by a project-selection change (setProjectSelection, plan 079), never by an
    // ordinary trendsView() call.
    let selectedTagKey = "";

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

    const filterIsActive = filter => !!(filter.branch || filter.mode || filter.outcome || projectKey());

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
        for (const value of ["", "success", "failed", "interrupted"]) outcome.append(new Option(value || "any outcome", value));
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
        const pk = projectKey();
        if (pk) params.set("projectKey", pk);
        return params;
    };

    // Appends projectKey= to a hardcoded path (rollup/flaky/benchmark/bottleneck/delivery views
    // and the bare /v1/tags path, plans 077/079) when a project is selected; byte-identical to
    // the input otherwise so unfiltered requests never change shape. encodeURIComponent yields
    // %20 for spaces where URLSearchParams yields "+" — the difference is accepted, Ktor decodes
    // both identically.
    function withProjectKey(path) {
        const pk = projectKey();
        return pk ? path + (path.includes("?") ? "&" : "?") + "projectKey=" + encodeURIComponent(pk) : path;
    }

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
                    lines: ["Try a different branch, mode, or outcome, or a different project."],
                    onClear: () => { resetProjectSelection(); buildsView({}, 0).catch(fail); },
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
        for (const columnName of ["Started", "Outcome", "Duration", "Mode", "Branch", "Project", "Hit rate"]) head.append(el("th", columnName));
        table.append(head);
        for (const build of builds) {
            const row = el("tr", null, "row");
            row.append(el("td", when(build.startedAt)));
            row.append(el("td", build.outcome, badgeClass(build.outcome)));
            const durationCell = el("td", ms(build.durationMs), "num");
            row.append(durationCell);
            row.append(el("td", build.mode));
            row.append(el("td", build.branch || ""));
            row.append(el("td", build.projectKey || "—"));
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
    // `testTelemetry` (plan 053, research F3) carries the honest degraded-state note for executed
    // Test tasks whose JUnit XML was disabled — rendered even when `tests` itself is empty, so an
    // empty/partial block reads as "collection turned off," not "no tests ran".
    function testsPanel(tests, testTelemetry) {
        const box = document.createDocumentFragment();
        box.append(el("h3", "Tests"));

        const xmlDisabledTasks = (testTelemetry && testTelemetry.xmlDisabledTasks) || [];
        if (xmlDisabledTasks.length) {
            box.append(el("p", "Test telemetry unavailable — JUnit XML disabled on " + xmlDisabledTasks.join(", ") + ".", "notice-warn"));
        }
        if (!tests.length) return box;

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
        const { items: builds } = await apiList(withProjectKey("/v1/builds?limit=50&offset=0"));
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
            const xmlDisabledTasks = (build.testTelemetry && build.testTelemetry.xmlDisabledTasks) || [];
            if ((Array.isArray(build.tests) && build.tests.length) || xmlDisabledTasks.length) {
                holder.append(testsPanel(build.tests || [], build.testTelemetry));
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

    // Allowlisted role labels — a role from the payload never becomes markup, only a table cell.
    const PROCESS_ROLE_LABELS = { GRADLE_DAEMON: "Gradle daemon", KOTLIN_DAEMON: "Kotlin daemon", GRADLE_WORKER: "Gradle worker" };
    const memMb = n => n == null ? "" : (n >= 1024 ? (n / 1024).toFixed(1) + " GB" : n + " MB");

    // Process snapshot (plan 029): per-JVM configured-vs-used memory with a native <progress> bar
    // (same as the HTML artifact; CSP-safe and doesn't collide with the timeline's svg count), GC, RSS.
    function processPanel(processes) {
        const box = document.createDocumentFragment();
        box.append(el("h3", "Process snapshot"));
        const table = el("table");
        const head = el("tr");
        for (const columnName of ["Process", "Heap used", "Configured -Xmx", "Used vs -Xmx", "GC time", "RSS", "Uptime"]) {
            head.append(el("th", columnName));
        }
        table.append(head);
        for (const p of processes) {
            const row = el("tr");
            row.append(el("td", PROCESS_ROLE_LABELS[p.role] || p.role));
            row.append(el("td", memMb(p.heapUsedMb), "num"));
            row.append(el("td", memMb(p.configuredXmxMb), "num"));
            row.append(usedVsXmxCell(p.heapUsedMb, p.configuredXmxMb));
            row.append(el("td", p.gcTimeMs == null ? "" : ms(p.gcTimeMs), "num"));
            row.append(el("td", memMb(p.rssMb), "num"));
            row.append(el("td", p.uptimeS == null ? "" : ms(p.uptimeS * 1000), "num"));
            table.append(row);
        }
        box.append(table);
        return box;
    }

    function usedVsXmxCell(usedMb, xmxMb) {
        const cell = el("td");
        if (usedMb == null || !xmxMb) return cell;
        const bar = document.createElement("progress");
        bar.max = xmxMb;
        bar.value = usedMb;
        cell.append(bar);
        cell.append(el("span", " " + Math.round(Math.max(0, Math.min(1, usedMb / xmxMb)) * 100) + "%", "muted"));
        return cell;
    }

    // Daemon-tuning candidates (plan 065): client-side mirror of the server's DaemonTuningCandidates
    // primary-input rules (lifetime GC fraction only — the pid-delta refinement needs the prior
    // build and lives server-side in /diagnosis). Thresholds are pinned in DaemonTuning.kt; every
    // card is advisory ("investigate/consider"), never auto-applied. All strings are composed from
    // allowlisted labels + numbers — no payload text is echoed.
    function tuningCandidates(processes, toolchain) {
        const HEAP_KNOB = { GRADLE_DAEMON: "org.gradle.jvmargs", KOTLIN_DAEMON: "kotlin.daemon.jvmargs" };
        const gcFraction = p => (p.gcTimeMs != null && p.uptimeS > 0) ? p.gcTimeMs / (p.uptimeS * 1000) : null;
        const pct = f => Math.round(f * 100) + " %";
        const label = p => PROCESS_ROLE_LABELS[p.role] || p.role;
        // parseInt is a prefix parser (stops at the first non-digit), unlike the Kotlin producer's
        // all-digit jdkMajor extraction — they only diverge on a shape like "24ea" that the plugin
        // never emits, so this is a documented divergence, not a bug.
        const jdkMajor = parseInt(String((toolchain && toolchain.jdk) || "").split(/[.\-_+]/)[0], 10);
        const cards = [];
        for (const p of processes.filter(p => gcFraction(p) != null && gcFraction(p) >= 0.15)) {
            if (HEAP_KNOB[p.role]) {
                cards.push("Investigate high GC time (" + pct(gcFraction(p)) + " of " + label(p)
                    + " JVM time) — consider raising the heap via " + HEAP_KNOB[p.role] + ".");
            }
            if (p.gcCollector === "G1") {
                cards.push("Throughput-bound? The " + label(p) + " runs G1 with high GC time — a ParallelGC trial"
                    + " (-XX:+UseParallelGC) may trade pause time for throughput.");
            }
        }
        for (const p of processes) {
            if (p.role === "KOTLIN_DAEMON" && p.heapUsedMb != null && p.configuredXmxMb > 0
                && p.heapUsedMb / p.configuredXmxMb >= 0.9) {
                cards.push("The Kotlin daemon heap sits at " + pct(p.heapUsedMb / p.configuredXmxMb)
                    + " of its configured -Xmx (" + p.configuredXmxMb + " MB) — consider raising kotlin.daemon.jvmargs.");
            }
            if (jdkMajor >= 24 && p.rssMb >= 2048 && p.compactObjectHeaders !== true) {
                cards.push("The " + label(p) + " uses " + p.rssMb + " MB RSS on JDK " + jdkMajor
                    + " without compact object headers — consider enabling -XX:+UseCompactObjectHeaders (~22 % heap, JEP 519).");
            }
        }
        if (!cards.length) return null;
        const box = document.createDocumentFragment();
        box.append(el("p", "Tuning candidates (advisory — investigate, nothing is auto-applied):", "muted"));
        const list = el("ul", null, "warnings");
        for (const card of cards) list.append(el("li", card));
        box.append(list);
        return box;
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

        // Lost build (plan 033): a marker-only INTERRUPTED build never finalized, so it carries no
        // tasks. Say so honestly rather than showing an all-zero ledger read as "a build that did
        // nothing". A finalized build never reaches this state (empty tasks + INTERRUPTED only).
        if (build.outcome === "INTERRUPTED" && tasks.length === 0) {
            app.append(el("p", "This build did not finish — the daemon died before telemetry was written, so no task detail was captured.", "notice-warn"));
            return;
        }

        // Build failure (plan 044/045): exception class + scrubbed message and the scrubbed stacktrace
        // (the 8 KiB wire copy — the HTML artifact keeps a fuller one). Gated on the failure object's
        // presence, not outcome === "FAILED": a config-phase failure is FAILED with no failure detail
        // (plan-044 extraction is execution-phase). All strings via el()/textContent — never innerHTML.
        if (build.failure) {
            const failure = build.failure;
            const header = failure.exceptionClass || "Build failed";
            app.append(el("h3", "Failure"));
            app.append(el("p", failure.message ? header + ": " + failure.message : header, "failure-summary"));
            if (failure.stackTrace) app.append(el("pre", failure.stackTrace, "failure-trace"));
        }

        // Work-avoidance ledger replaces the v0 outcome-count chips: every category with
        // explicit zeros, share-of-all-tasks percentages, summed task time.
        app.append(el("h3", "Work avoidance"));
        app.append(ledgerTable(tasks));

        // Process snapshot (plan 029): daemon/Kotlin/worker JVM memory. Hidden when the probe
        // collected nothing (disabled or JDK tools absent) — the panel renders only with data.
        // Daemon-tuning candidate cards (plan 065) render under the table when a rule fires.
        if (Array.isArray(build.processes) && build.processes.length) {
            app.append(processPanel(build.processes));
            const candidates = tuningCandidates(build.processes, build.toolchain);
            if (candidates) app.append(candidates);
        }

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

        // Test results (plan 024), plus the honest degraded-state note (plan 053, research F3) when
        // JUnit XML was disabled on an executed Test task — shown even when `tests` itself is empty,
        // so an empty/partial block reads as "collection turned off," not "no tests ran".
        const xmlDisabledTasks = (build.testTelemetry && build.testTelemetry.xmlDisabledTasks) || [];
        if ((Array.isArray(build.tests) && build.tests.length) || xmlDisabledTasks.length) {
            app.append(testsPanel(build.tests || [], build.testTelemetry));
        }

        // Build warnings (plan 044/045): opt-in deprecation + logger.warn capture, carried in the
        // independently-versioned extensions.internalAdapters block. Absent for every build without
        // the opt-in internal-adapters module, so the whole section is hidden unless something was
        // captured. All strings reach the DOM via el()/textContent — never innerHTML.
        const warnings = warningsPanel(build.extensions && build.extensions.internalAdapters);
        if (warnings) app.append(warnings);

        // CI span tree (plan 028): the connector-enriched pipeline timeline, queue time, and Gradle
        // share. Best-effort and honest — a 404 (no run) or a non-OK status renders an amber notice
        // rather than a hidden section (UX honesty rule); a fetch error just omits it.
        try {
            const ci = await apiMaybe("/v1/builds/" + encodeURIComponent(buildId) + "/ci-run");
            if (seq !== renderSeq) return;
            app.append(ciRunSection(ci));
        } catch (e) { /* keep the rest of the detail page */ }
    }

    // Build-warnings panel (plan 044/045): the deprecations + logger.warn lists from the opt-in
    // internal-adapters block, or null when nothing was captured (no module, toggles off, or a
    // clean build). A flat fragment mirrors the detail page's other sections; every warning string
    // reaches the DOM via el()/textContent, so an injected markup string lands as inert text.
    function warningsPanel(internalAdapters) {
        const deprecations = (internalAdapters && internalAdapters.deprecations) || [];
        const logWarnings = (internalAdapters && internalAdapters.logWarnings) || [];
        const droppedWarnings = (internalAdapters && internalAdapters.droppedWarnings) || 0;
        if (!deprecations.length && !logWarnings.length && !droppedWarnings) return null;
        const fragment = document.createDocumentFragment();
        fragment.append(el("h3", "Warnings"));
        const warnList = (label, items) => {
            if (!items.length) return;
            fragment.append(el("p", label + " (" + items.length + ")", "muted"));
            const list = el("ul", null, "warnings");
            for (const warning of items) list.append(el("li", warning));
            fragment.append(list);
        };
        warnList("Deprecations", deprecations);
        warnList("Log warnings", logWarnings);
        if (droppedWarnings) fragment.append(el("p", droppedWarnings + (droppedWarnings === 1 ? " more warning" : " more warnings") + " dropped past the cap", "muted"));
        return fragment;
    }

    // Like api() but returns null on 404 instead of throwing — for optional sub-resources.
    async function apiMaybe(path) {
        const response = await fetch(path, { headers: { Authorization: "Bearer " + token() } });
        if (response.status === 404) return null;
        if (response.status === 401 || response.status === 403) {
            tokenBar.hidden = false;
            throw new Error(response.status === 401 ? "token required" : "token lacks read scope");
        }
        if (!response.ok) throw new Error("request failed: " + response.status);
        return response.json();
    }

    // Span results reuse the allowlisted badge classes (no new CSS class from untrusted data):
    // SUCCEEDED→SUCCESS, FAILED→FAILED, SKIPPED→SKIPPED; CANCELED/UNKNOWN render unstyled.
    const SPAN_BADGE = { SUCCEEDED: "SUCCESS", FAILED: "FAILED", SKIPPED: "SKIPPED", CANCELED: "", UNKNOWN: "" };
    const spanBadgeClass = result => SPAN_BADGE[result] || "";

    function ciRunSection(ci) {
        const section = document.createDocumentFragment();
        section.append(el("h3", "CI pipeline"));
        if (!ci || ci.status !== "OK") {
            const reason = !ci ? "no connector run for this build"
                : ci.status === "UNCONFIGURED" ? "connector not configured"
                : ci.status === "PENDING" ? "timeline pending — the pipeline had not finished"
                : "timeline fetch failed";
            section.append(el("p", "CI timeline not available (" + reason + ")", "notice-warn"));
            return section;
        }
        const chips = el("ul", null, "chips");
        if (ci.queuedMs != null) chips.append(chipItem("queue time", ms(ci.queuedMs)));
        if (ci.gradleSharePct != null) chips.append(chipItem("gradle share", Math.round(ci.gradleSharePct * 100) + "%"));
        if (chips.children.length) section.append(chips);
        section.append(spanTree(ci.spans || []));
        return section;
    }

    // Nested stage→job→step list built from parentId. A `seen` set + depth cap keep a corrupt or
    // cyclic parent chain from looping (Azure timelines are trees; this is defensive).
    function spanTree(spans) {
        const ids = new Set(spans.map(s => s.id));
        const byParent = new Map();
        for (const s of spans) {
            const key = (s.parentId != null && ids.has(s.parentId)) ? s.parentId : "__root__";
            if (!byParent.has(key)) byParent.set(key, []);
            byParent.get(key).push(s);
        }
        const seen = new Set();
        function build(key, depth) {
            if (depth > 12) return null;
            const kids = (byParent.get(key) || []).filter(s => !seen.has(s.id))
                .sort((a, b) => (a.startMs || 0) - (b.startMs || 0));
            if (!kids.length) return null;
            const ul = el("ul", null, "span-tree");
            for (const s of kids) {
                seen.add(s.id);
                const li = el("li");
                li.append(el("span", s.kind || "", "muted span-kind"));
                li.append(el("span", s.name || ""));
                if (s.result) li.append(el("span", " " + s.result, "badge " + spanBadgeClass(s.result)));
                if (s.startMs != null && s.finishMs != null) li.append(el("span", " " + ms(s.finishMs - s.startMs), "muted"));
                const sub = build(s.id, depth + 1);
                if (sub) li.append(sub);
                ul.append(li);
            }
            return ul;
        }
        return build("__root__", 0) || el("p", "no spans", "muted");
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

    // Tag-cohort comparison (plan 057, research F7): a fixed color cycle for the multi-series chart
    // + legend, reused by both (an SVG `fill` attribute, not a CSS class — the same literal-hex
    // pattern trendChart/bottlenecksView already use for series colors).
    const COHORT_COLORS = ["#3b82f6", "#22c55e", "#f97316", "#a855f7", "#ef4444", "#06b6d4"];

    function legendSwatch(color) {
        const svg = svgEl("svg", { viewBox: "0 0 10 10", width: "10", height: "10" });
        svg.append(svgEl("circle", { cx: 5, cy: 5, r: 5, fill: color }));
        return svg;
    }

    function cohortLegend(cohorts) {
        const ul = el("ul", null, "chips");
        cohorts.forEach((cohort, i) => {
            const li = el("li");
            li.append(legendSwatch(COHORT_COLORS[i % COHORT_COLORS.length]));
            li.append(el("span", " " + cohort.value + " (n=" + cohort.sampleCount + ")"));
            ul.append(li);
        });
        return ul;
    }

    // Generalizes trendChart to overlay one line per cohort (plan 057) — same axis/tooltip
    // conventions, a distinct color per series from COHORT_COLORS.
    function cohortChart(cohorts) {
        const width = 720, height = 200, pad = 30;
        const svg = svgEl("svg", { viewBox: "0 0 " + width + " " + height });
        const values = cohorts.flatMap(c => c.points.map(p => p.avgDurationMs)).filter(v => v != null);
        const max = Math.max(...values, 1);
        svg.append(svgEl("line", { x1: pad, y1: height - pad, x2: width - pad, y2: height - pad, stroke: "#8886" }));
        cohorts.forEach((cohort, ci) => {
            const color = COHORT_COLORS[ci % COHORT_COLORS.length];
            const points = cohort.points;
            const stepX = points.length > 1 ? (width - 2 * pad) / (points.length - 1) : 0;
            const x = i => pad + i * stepX;
            const y = v => height - pad - (v / max) * (height - 2 * pad);
            let path = "";
            points.forEach((point, i) => {
                const value = point.avgDurationMs;
                if (value == null) return;
                path += (path ? " L" : "M") + x(i).toFixed(1) + " " + y(value).toFixed(1);
                const dot = svgEl("circle", { cx: x(i).toFixed(1), cy: y(value).toFixed(1), r: 2.5, fill: color });
                const title = document.createElementNS(SVG_NS, "title");
                title.textContent = cohort.value + " · " + point.day + ": " + ms(value);
                dot.append(title);
                svg.append(dot);
            });
            if (path) svg.append(svgEl("path", { d: path, fill: "none", stroke: color, "stroke-width": 1.5 }));
        });
        return svg;
    }

    // Per-cohort delta table (plan 057): the reference row first (labelled, no delta against
    // itself), then every other cohort's median/Δ/%change with the existing semantic-goodness
    // delta chip (duration: a rise is bad, upIsGood=false) plus the honest status label — a
    // DISTINGUISHABLE signal is a candidate to investigate, never a claimed "N% faster".
    function cohortDeltaTable(cohorts, delta) {
        const byValue = new Map(cohorts.map(c => [c.value, c]));
        const table = el("table");
        const head = el("tr");
        for (const columnName of ["Cohort", "n", "Median", "Δ vs " + delta.referenceValue, "% change", "Signal"]) head.append(el("th", columnName));
        table.append(head);
        const reference = byValue.get(delta.referenceValue);
        if (reference) {
            const row = el("tr");
            row.append(el("td", reference.value + " (reference)"));
            row.append(el("td", reference.sampleCount, "num"));
            row.append(el("td", ms(reference.medianDurationMs), "num"));
            row.append(el("td", "—", "num"));
            row.append(el("td", "—", "num"));
            row.append(el("td", "—"));
            table.append(row);
        }
        for (const c of delta.comparisons) {
            const cohort = byValue.get(c.value);
            const row = el("tr");
            row.append(el("td", c.value));
            row.append(el("td", cohort ? cohort.sampleCount : "", "num"));
            row.append(el("td", cohort ? ms(cohort.medianDurationMs) : "", "num"));
            row.append(el("td", signedMs(c.medianDeltaMs), "num"));
            row.append(el("td", c.pctChange == null ? "—" : (c.pctChange > 0 ? "+" : "") + Math.round(c.pctChange * 100) + "%", "num"));
            const signal = el("td");
            signal.append(deltaChip(c.pctChange, false));
            signal.append(el("span", " " + c.status.toLowerCase().replace(/_/g, " "), "muted"));
            row.append(signal);
            table.append(row);
        }
        return table;
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
                onClear: filterIsActive(filter) ? (() => { resetProjectSelection(); trendsView({}, days).catch(fail); }) : undefined,
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

        // Tag-cohort comparison (plan 057, research F7): a "split by tag" picker populated from
        // /v1/tags; when a key is selected, fetch the per-cohort series + delta and render a
        // multi-series chart + delta table. Best-effort like the artifact panel below: a fetch
        // error just omits the section, never blanks the rest of the trends page.
        try {
            const tagKeys = await api(withProjectKey("/v1/tags"));
            if (seq !== renderSeq) return;
            if (tagKeys.length) {
                app.append(el("h3", "Split by tag"));
                const picker = el("div", null, "filters");
                const select = document.createElement("select");
                select.append(new Option("no split", ""));
                for (const summary of tagKeys) select.append(new Option(summary.key, summary.key));
                select.value = selectedTagKey;
                select.addEventListener("change", () => {
                    selectedTagKey = select.value;
                    trendsView(filter, days).catch(fail);
                });
                picker.append(select);
                app.append(picker);

                if (selectedTagKey) {
                    const cohortParams = query(filter);
                    cohortParams.set("days", String(days));
                    cohortParams.set("tag", selectedTagKey);
                    const comparison = await api("/v1/trends/cohorts?" + cohortParams);
                    if (seq !== renderSeq) return;
                    if (!comparison.cohorts.length) {
                        app.append(el("p", "No builds carry the \"" + selectedTagKey + "\" tag in this range.", "muted"));
                    } else {
                        app.append(cohortLegend(comparison.cohorts));
                        app.append(cohortChart(comparison.cohorts));
                        if (comparison.delta) app.append(cohortDeltaTable(comparison.cohorts, comparison.delta));
                    }
                }
            }
        } catch (e) { /* keep the rest of the trends page */ }

        // Artifact sizes (plan 031): one line per (module, variant, type), reusing trendChart with a
        // bytes→MB formatter. Best-effort — a fetch error just omits the panel, never blanks the page.
        try {
            const artifacts = await api("/v1/artifacts/trends?" + params);
            if (seq !== renderSeq) return;
            app.append(el("h3", "Artifact sizes"));
            if (!artifacts.length) {
                app.append(el("p", "No Android artifacts in the last " + days + " days", "muted"));
            } else {
                const mb = bytes => (bytes / (1024 * 1024)).toFixed(1) + " MB";
                const bySeries = new Map();
                for (const p of artifacts) {
                    const label = (p.module || "") + " · " + p.variant + " · " + p.type;
                    if (!bySeries.has(label)) bySeries.set(label, []);
                    bySeries.get(label).push(p);
                }
                for (const [label, series] of bySeries) {
                    app.append(el("p", label, "muted"));
                    app.append(trendChart(series, p => p.avgSizeBytes, "#a855f7", mb));
                }
            }
        } catch (e) { /* keep the rest of the trends page */ }
    }

    // Comparisons (plan 022): pick two builds, then explain B's cache misses vs A by input diff.
    async function compareView() {
        const seq = ++renderSeq;
        const { items: builds } = await apiList(withProjectKey("/v1/builds?limit=50&offset=0"));
        if (seq !== renderSeq) return;

        app.textContent = "";
        app.append(el("p", "Compare two builds", "summary-sentence"));
        if (!builds.length) {
            app.append(emptyState({ title: "No builds to compare", lines: ["Send builds first, then pick two here."] }));
            return;
        }
        // Under "All projects" each option is suffixed with the build's projectKey (plan 079
        // amendment) so a mixed list stays readable; with a project selected every row is that
        // project already, so no suffix. Option labels are textContent-safe by construction.
        const showProject = !projectKey();
        const label = b => when(b.startedAt) + " · " + b.outcome + " · " + (b.branch || "no branch") + " · " + b.mode
            + (showProject && b.projectKey ? " · " + b.projectKey : "");
        const selectA = document.createElement("select");
        const selectB = document.createElement("select");
        for (const build of builds) selectA.append(new Option(label(build), build.buildId));
        selectA.value = builds[0].buildId;
        // Same-project constraint (plan 079 amendment): once A is a build with a known project,
        // B only offers that project's builds — plus pre-077 builds with no key (project unknown,
        // comparable with anything). Rebuilt from the already-fetched rows on every A change;
        // B's choice is kept when it survives the narrowing, else falls back like the original
        // second-newest default. `compatible` is never empty: A itself always qualifies.
        const rebuildB = preferred => {
            const a = builds.find(b => b.buildId === selectA.value);
            const compatible = (a && a.projectKey != null)
                ? builds.filter(b => b.projectKey == null || b.projectKey === a.projectKey)
                : builds;
            selectB.textContent = "";
            for (const build of compatible) selectB.append(new Option(label(build), build.buildId));
            const fallback = compatible[compatible.length > 1 ? 1 : 0].buildId;
            selectB.value = compatible.some(b => b.buildId === preferred) ? preferred : fallback;
        };
        selectA.addEventListener("change", () => rebuildB(selectB.value));
        rebuildB(builds.length > 1 ? builds[1].buildId : builds[0].buildId);
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
        // Same-project guard (plan 079 amendment): when both refs carry non-null, differing
        // projectKeys the comparison is meaningless — refuse to render the tables (the API stays
        // permissive; this is UI-level honesty). Null on either side (pre-077 build, project
        // unknown) compares normally.
        if (result.a.projectKey != null && result.b.projectKey != null && result.a.projectKey !== result.b.projectKey) {
            app.append(el("p", "These builds belong to different projects (" + result.a.projectKey + " vs " + result.b.projectKey + ") — comparisons are per-project. Pick two builds from the same project.", "notice-warn"));
            return;
        }
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

    // Tasks explorer (plan 026): three server rollups over the last 30 days. textContent-only;
    // module/name/type are the same untrusted class as task paths already rendered elsewhere.
    async function tasksRollupView() {
        const seq = ++renderSeq;
        const cost = await api(withProjectKey("/v1/rollups/project-cost?days=30"));
        const blast = await api(withProjectKey("/v1/rollups/change-blast-radius?days=30"));
        const duration = await api(withProjectKey("/v1/rollups/task-duration?days=30"));
        const negative = await api(withProjectKey("/v1/rollups/negative-avoidance?days=30"));
        if (seq !== renderSeq) return;

        app.textContent = "";
        app.append(el("p", "Tasks explorer — last 30 days", "summary-sentence"));

        // Project cost by module.
        app.append(el("h3", "Project cost by module"));
        if (!cost.length) {
            app.append(emptyState({
                title: "No task data yet",
                lines: ["Send builds with task telemetry to see per-module cost."],
            }));
        } else {
            const table = el("table");
            const head = el("tr");
            for (const columnName of ["Module", "Builds", "Executed", "Impacted users", "Serial task time", "Avg build", "Cost scalar"]) head.append(el("th", columnName));
            table.append(head);
            for (const r of cost) {
                const row = el("tr");
                row.append(el("td", r.module || "(root)"));
                row.append(el("td", r.builds, "num"));
                row.append(el("td", r.executedBuilds, "num"));
                row.append(el("td", r.buildImpactedUsers, "num"));
                row.append(el("td", ms(r.serialTaskMs), "num"));
                row.append(el("td", ms(r.buildAvgDurationMs), "num"));
                row.append(el("td", r.buildCostScalar, "num"));
                table.append(row);
            }
            app.append(table);
        }

        // Costliest modules to change (plan 063): beside project cost, ranks a module by the cost it
        // inflicts on OTHERS when changed (median downstream executed time × change frequency). Honest
        // empty state when no build in the window carried a changedModules block (no resolvable diff
        // base — CI without a fetched base ref, or a first local build with no last-built-sha yet).
        app.append(el("h3", "Costliest modules to change"));
        if (!blast.length) {
            app.append(emptyState({
                title: "No change blast-radius data yet",
                lines: [
                    "This ranks a module by the downstream work its changes cause in other modules.",
                    "It needs builds carrying a changedModules block — a resolvable diff base (a CI PR base ref, or a recorded previous-build HEAD).",
                ],
            }));
        } else {
            const table = el("table");
            const head = el("tr");
            for (const columnName of ["Module", "Changes", "Median downstream", "Blast score"]) head.append(el("th", columnName));
            table.append(head);
            for (const r of blast) {
                const row = el("tr");
                // r.module is a non-nullable ChangeBlastRadiusRow field; the root project ships as the
                // truthy ":" sentinel (never null/empty), so a `||` fallback here is dead — map it
                // explicitly to match the "(root)" convention the other module columns use.
                row.append(el("td", r.module === ":" ? "(root)" : r.module));
                row.append(el("td", r.changeCount, "num"));
                row.append(el("td", ms(r.medianDownstreamMs), "num"));
                row.append(el("td", r.blastScore, "num"));
                table.append(row);
            }
            app.append(table);
        }

        // Task duration with a name/type/plugin toggle. "By plugin" (plan 058) is fetched lazily —
        // only once the button is first clicked — since it's a fourth rollup the other two groupings
        // don't need; pluginCost caches the response so re-clicking never re-fetches.
        app.append(el("h3", "Task duration"));
        const bar = el("div", null, "filters");
        const durationHolder = el("div");
        let mode = "name"; // "name" | "type" | "plugin"
        let pluginCost = null;
        let pluginSeq = 0; // guards two "By plugin" clicks racing past the same in-flight fetch (renderSeq idiom)
        const renderDuration = async () => {
            durationHolder.textContent = "";
            if (mode === "type" && !duration.byTypeAvailable) {
                durationHolder.append(emptyState({
                    title: "Task types not populated yet",
                    lines: ["By-type rankings appear once the plugin's task-type capture (plan 016) is deployed."],
                }));
                return;
            }
            if (mode === "plugin") {
                const seq = ++pluginSeq;
                if (!pluginCost) pluginCost = await api(withProjectKey("/v1/rollups/plugin-cost?days=30"));
                // The fetch above is the only await in this function; if the user clicked a different
                // toggle button while it was in flight, mode has since changed; if the user clicked
                // "By plugin" again, a newer call now owns pluginSeq — either way that later click's
                // own render already ran and resuming here must not append a second, stale table on
                // top of it.
                if (mode !== "plugin" || seq !== pluginSeq) return;
                if (!pluginCost.available) {
                    durationHolder.append(emptyState({
                        title: "Task types not populated yet",
                        lines: ["Plugin attribution needs task types — deploy the plugin's task-type capture (plan 016)."],
                    }));
                    return;
                }
                if (!pluginCost.plugins.length) { durationHolder.append(el("p", "No task durations in this window.", "muted")); return; }
                durationHolder.append(rankedTable(["Plugin", "Total", "Share", "Runs"], pluginCost.plugins, r =>
                    [el("td", r.plugin), el("td", ms(r.totalMs), "num"), el("td", pctFmt(r.sharePct), "num"), el("td", r.count, "num")]));
                return;
            }
            const rows = mode === "type" ? duration.byType : duration.byName;
            if (!rows.length) { durationHolder.append(el("p", "No task durations in this window.", "muted")); return; }
            const table = el("table");
            const head = el("tr");
            for (const columnName of [mode === "type" ? "Type" : "Name", "Count", "Total", "Avg", "Min", "Max"]) head.append(el("th", columnName));
            table.append(head);
            for (const r of rows) {
                const row = el("tr");
                row.append(el("td", r.key));
                row.append(el("td", r.count, "num"));
                row.append(el("td", ms(r.totalMs), "num"));
                row.append(el("td", ms(r.avgMs), "num"));
                row.append(el("td", ms(r.minMs), "num"));
                row.append(el("td", ms(r.maxMs), "num"));
                table.append(row);
            }
            durationHolder.append(table);
        };
        const byName = el("button", "By name");
        const byType = el("button", "By type");
        const byPlugin = el("button", "By plugin");
        byName.addEventListener("click", () => { mode = "name"; renderDuration().catch(fail); });
        byType.addEventListener("click", () => { mode = "type"; renderDuration().catch(fail); });
        byPlugin.addEventListener("click", () => { mode = "plugin"; renderDuration().catch(fail); });
        bar.append(byName, byType, byPlugin);
        app.append(bar, durationHolder);
        await renderDuration();

        // Negative avoidance.
        app.append(el("h3", "Negative avoidance (avoiding cost more than doing)"));
        if (!negative.length) {
            app.append(el("p", "No negative-avoidance signal in this window — avoidance is paying off.", "muted"));
        } else {
            const table = el("table");
            const head = el("tr");
            for (const columnName of ["Group", "Count", "Total excess", "Worst excess"]) head.append(el("th", columnName));
            table.append(head);
            for (const r of negative) {
                const row = el("tr");
                row.append(el("td", r.key));
                row.append(el("td", r.count, "num"));
                row.append(el("td", ms(r.totalExcessMs), "num"));
                row.append(el("td", ms(r.worstExcessMs), "num"));
                table.append(row);
            }
            app.append(table);
        }
    }

    // Benchmark series (plan 030): per-scenario percentile chips + a durationMs line chart, with an
    // isolation-mode selector. Benchmark builds are excluded from fleet trends, so this is their home.
    async function benchmarkView() {
        const seq = ++renderSeq;
        const series = await api(withProjectKey("/v1/benchmark/series?days=90"));
        if (seq !== renderSeq) return;

        app.textContent = "";
        app.append(el("h2", "Benchmark series"));
        if (!series.length) {
            app.append(emptyState({
                title: "No benchmark builds yet",
                lines: [
                    "Run the scheduled gradle-profiler pipeline against the pilot to populate this view.",
                    "See buildhound-ci-assets/profiler-pipeline and the benchmark-and-experiments recipe.",
                ],
            }));
            return;
        }

        const byScenario = new Map();
        for (const s of series) {
            if (!byScenario.has(s.scenario)) byScenario.set(s.scenario, []);
            byScenario.get(s.scenario).push(s);
        }
        for (const [scenario, groups] of byScenario) {
            const section = el("section");
            section.append(el("h3", "Scenario: " + scenario));

            const select = el("select");
            for (const g of groups) select.append(new Option(g.isolationMode || "(default)", g.isolationMode || ""));

            const holder = el("div");
            const renderGroup = iso => {
                holder.textContent = "";
                const g = groups.find(x => (x.isolationMode || "") === iso) || groups[0];
                const chips = el("ul", null, "chips");
                chips.append(chipItem("p50", ms(g.summary.p50)));
                chips.append(chipItem("p90", ms(g.summary.p90)));
                chips.append(chipItem("min", ms(g.summary.min)));
                chips.append(chipItem("runs", g.summary.count));
                holder.append(chips);
                // Reuse the trend chart: map each benchmark point to {day,durationMs} for its tooltip.
                const points = g.points.map(p => ({ day: when(p.startedAt), durationMs: p.durationMs }));
                holder.append(trendChart(points, p => p.durationMs, "#2563eb", ms));
            };
            select.addEventListener("change", () => renderGroup(select.value));
            if (groups.length > 1) section.append(select);
            section.append(holder);
            app.append(section);
            renderGroup(groups[0].isolationMode || "");
        }
    }

    // Bottlenecks landing page (plan 032): "what got worse this window". Headline KPIs with semantic
    // delta chips, four ranked families, and toolchain adoption. textContent-only (task keys, versions,
    // and module names are the same untrusted class as task paths rendered elsewhere).
    const pctFmt = v => Math.round(v * 100) + "%";
    const signedMs = d => (d > 0 ? "+" : d < 0 ? "−" : "") + ms(Math.abs(d));

    // A delta whose COLOUR encodes goodness, not sign (plan 032): for "up is good" KPIs (success,
    // hit rate) a rise is green; for "up is bad" (duration) a rise is red; neutral KPIs stay grey.
    // A null percent (no prior window, or a brand-new group) shows an em dash, never ∞ or −100 %.
    function deltaChip(deltaPct, upIsGood) {
        if (deltaPct == null) return el("span", "—", "delta delta-flat");
        const pct = Math.round(deltaPct * 100);
        const good = (pct === 0 || upIsGood == null) ? "delta-flat"
            : (upIsGood ? pct > 0 : pct < 0) ? "delta-good" : "delta-bad";
        return el("span", (pct > 0 ? "+" : "") + pct + "%", "delta " + good);
    }

    function kpiCard(label, kpi, format, upIsGood) {
        const card = el("div", null, "kpi");
        card.append(el("div", label, "kpi-label"));
        card.append(el("div", kpi.current == null ? "—" : format(kpi.current), "kpi-value"));
        card.append(deltaChip(kpi.deltaPct, upIsGood));
        card.append(el("div", kpi.prior == null ? "no prior window" : "prev " + format(kpi.prior), "muted"));
        return card;
    }

    // One toolchain dimension: an honest "not collected yet" notice when unavailable (never an empty
    // table read as consensus), else a distribution table with the behind-the-latest rows highlighted.
    function toolchainPanel(label, dim) {
        const box = document.createDocumentFragment();
        box.append(el("h3", label));
        if (!dim || !dim.available) {
            box.append(el("p", "Not collected yet — this dimension populates once the plugin reports it.", "notice-warn"));
            return box;
        }
        const behindSet = new Set(dim.behind.map(v => v.version));
        const table = el("table");
        const head = el("tr");
        for (const columnName of ["Version", "Builds", "Share", "Distinct users", "Last seen"]) head.append(el("th", columnName));
        table.append(head);
        for (const v of dim.versions) {
            const row = el("tr", null, behindSet.has(v.version) ? "behind" : null);
            row.append(el("td", v.version));
            row.append(el("td", v.builds, "num"));
            row.append(el("td", pctFmt(v.sharePct), "num"));
            row.append(el("td", v.distinctUsers, "num"));
            row.append(el("td", when(v.lastSeenMs), "num"));
            table.append(row);
        }
        box.append(table);
        if (dim.behind.length) {
            box.append(el("p", dim.behind.length + " version(s) behind the latest: " + dim.behind.map(v => v.version).join(", "), "muted"));
        }
        return box;
    }

    // Warning taxonomy (plan 060): a fixed server enum (ALWAYS_RUN|NON_INCREMENTAL_AP|
    // DYNAMIC_DEBUG_VALUES) mapped to allowlisted CSS classes — mirrors FLAKY_SIGNAL_CLASS below.
    // Copy is modeled on the official Build Analyzer / profile-your-build wording, phrased as
    // candidates ("likely/investigate"), never a confirmed diagnosis.
    const CATEGORY_LABEL = {
        ALWAYS_RUN: "Always runs",
        NON_INCREMENTAL_AP: "Non-incremental annotation processing",
        DYNAMIC_DEBUG_VALUES: "Dynamic debug values",
    };
    const CATEGORY_COPY = {
        ALWAYS_RUN: "Likely runs on every build (no declared outputs, or upToDateWhen is always false) — investigate whether it can declare outputs or a real up-to-date check.",
        NON_INCREMENTAL_AP: "Likely a non-incremental annotation processor or Java-compile step (can't name the specific processor) — investigate incremental annotation-processing support.",
        DYNAMIC_DEBUG_VALUES: "Never gets a cache hit — likely a dynamic debug value (e.g. a timestamp in buildConfigField/resValue) — investigate whether the value needs to change every build.",
    };
    const CATEGORY_CLASS = { ALWAYS_RUN: "warn-always-run", NON_INCREMENTAL_AP: "warn-non-incremental-ap", DYNAMIC_DEBUG_VALUES: "warn-dynamic-debug" };
    const warningCategoryClass = category => CATEGORY_CLASS[category] || "";

    function rankedTable(columns, rows, cellsOf) {
        const table = el("table");
        const head = el("tr");
        for (const columnName of columns) head.append(el("th", columnName));
        table.append(head);
        for (const r of rows) {
            const row = el("tr");
            for (const cell of cellsOf(r)) row.append(cell);
            table.append(row);
        }
        return table;
    }

    // A task/module label cell: the group key with the owning module (when unambiguous) in muted text.
    function keyCell(row) {
        const cell = el("td");
        cell.append(el("span", row.key));
        if (row.module) cell.append(el("span", " " + row.module, "muted"));
        return cell;
    }

    async function bottlenecksView(period) {
        const seq = ++renderSeq;
        const p = period || 7;
        const b = await api(withProjectKey("/v1/rollups/bottlenecks?period=" + p));
        // Toolchain and Warnings are both best-effort: a failure omits the section rather than
        // blanking the whole landing page (the same artifact-panel pattern).
        let toolchain = null;
        try { toolchain = await api(withProjectKey("/v1/rollups/toolchain?days=30")); } catch (e) { /* omit the section */ }
        let warnings = null;
        try { warnings = await api(withProjectKey("/v1/rollups/warnings?period=" + p)); } catch (e) { /* omit the section */ }
        // Remote-cache ROI (plan 067, research F17): best-effort like toolchain/warnings above.
        let cacheRoi = null;
        try { cacheRoi = await api(withProjectKey("/v1/rollups/cache-roi?days=30")); } catch (e) { /* omit the section */ }
        if (seq !== renderSeq) return;

        app.textContent = "";
        app.append(el("p", "What got worse — the last " + p + " days vs the previous " + p, "summary-sentence"));

        const bar = el("div", null, "filters");
        for (const range of [7, 14, 30]) {
            const button = el("button", range + " days");
            if (range === p) button.disabled = true;
            button.addEventListener("click", () => bottlenecksView(range).catch(fail));
            bar.append(button);
        }
        app.append(bar);

        const strip = el("div", null, "kpi-strip");
        strip.append(kpiCard("Builds", b.buildCount, n => String(Math.round(n)), null));
        strip.append(kpiCard("Success rate", b.successRate, pctFmt, true));
        strip.append(kpiCard("Avg duration", b.avgDurationMs, ms, false));
        strip.append(kpiCard("Cache hit rate", b.hitRate, pctFmt, true));
        app.append(strip);

        // Verdict rollup (plan 025): only rendered when a verdict store is wired — null omits the card.
        if (b.budgetBreaches != null || b.trendRegressions != null) {
            const chips = el("ul", null, "chips");
            if (b.budgetBreaches != null) chips.append(chipItem("budget breaches", b.budgetBreaches));
            if (b.trendRegressions != null) chips.append(chipItem("trend regressions", b.trendRegressions));
            app.append(chips);
        }

        app.append(el("h3", "Regressed tasks (slower to execute than the previous window)"));
        if (!b.regressedTasks.length) {
            app.append(el("p", "Nothing regressed — execution held steady or improved.", "muted"));
        } else {
            app.append(rankedTable(["Task", "Now", "Before", "Δ", "Change"], b.regressedTasks, r => {
                const changeCell = el("td");
                if (r.isNew) changeCell.append(el("span", "new", "delta delta-flat"));
                else if (r.isVanished) changeCell.append(el("span", "gone", "delta delta-flat"));
                else changeCell.append(deltaChip(r.deltaPct, false));
                return [
                    keyCell(r),
                    el("td", r.isVanished ? "—" : ms(r.currentMs), "num"),
                    el("td", r.isNew ? "—" : ms(r.priorMs), "num"),
                    el("td", signedMs(r.deltaMs), "num"),
                    changeCell,
                ];
            }));
        }

        app.append(el("h3", "Slowest work (most total time this window)"));
        if (!b.slowestWork.length) {
            app.append(el("p", "No task work in this window.", "muted"));
        } else {
            app.append(rankedTable(["Task", "Total time", "Runs"], b.slowestWork, r =>
                [keyCell(r), el("td", ms(r.currentMs), "num"), el("td", r.count, "num")]));
        }

        // Top plugins by time (plan 058, research F8 Layer 1): mirrors the cache-miss hotspots
        // convention below — topPluginsAvailable is false only when no task in the window carries a
        // type at all (isolated-projects degradation, plan 016), in which case topPlugins would
        // otherwise be a non-empty "(unattributed)" fold rendered as if it were real data.
        app.append(el("h3", "Top plugins by time"));
        if (!b.topPluginsAvailable) {
            app.append(el("p", "Task types not collected yet — deploy the plan-016 plugin to surface plugin cost attribution.", "notice-warn"));
        } else if (!b.topPlugins.length) {
            app.append(el("p", "No task work in this window.", "muted"));
        } else {
            app.append(rankedTable(["Plugin", "Total time", "Runs"], b.topPlugins, r =>
                [el("td", r.key), el("td", ms(r.currentMs), "num"), el("td", r.count, "num")]));
        }

        app.append(el("h3", "Negative avoidance (avoiding cost more than doing)"));
        if (!b.negativeAvoidance.length) {
            app.append(el("p", "No negative-avoidance signal — avoidance is paying off.", "muted"));
        } else {
            app.append(rankedTable(["Group", "Total excess", "Count"], b.negativeAvoidance, r =>
                [keyCell(r), el("td", ms(r.currentMs), "num"), el("td", r.count, "num")]));
        }

        app.append(el("h3", "Cache-miss hotspots (cacheable work that still executed)"));
        if (!b.cacheDataAvailable) {
            app.append(el("p", "Cacheability not collected yet — deploy the plan-016 plugin to surface cache-miss hotspots.", "notice-warn"));
        } else if (!b.cacheMissHotspots.length) {
            app.append(el("p", "No cacheable task executed this window — the cache is doing its job.", "muted"));
        } else {
            app.append(rankedTable(["Task", "Executed time", "Misses"], b.cacheMissHotspots, r =>
                [keyCell(r), el("td", ms(r.currentMs), "num"), el("td", r.count, "num")]));
        }

        // Remote-cache ROI (plan 067, research F17): the config-snapshot summary (always available once
        // the plan-067 plugin ships) distinguishes "no remote configured" from "configured but cold",
        // and — with the opt-in internal-adapters origin — the per-mode remote-hit rate. Two-tier: when
        // remoteHitRateAvailable is false, only the config summary renders (never a synthesized rate).
        if (cacheRoi) {
            app.append(el("h3", "Remote-cache ROI (last 30 days)"));
            if (!cacheRoi.buildsWithConfig) {
                app.append(el("p", "Build-cache config not collected yet — deploy the plan-067 plugin to see whether a remote cache is even configured.", "notice-warn"));
            } else {
                app.append(el("p", "Remote cache configured-and-enabled on " + pctFmt(cacheRoi.remoteConfiguredShare)
                    + " of " + cacheRoi.buildsWithConfig + " builds carrying the config snapshot.", "summary-sentence"));
            }
            if (!cacheRoi.remoteHitRateAvailable) {
                app.append(el("p", "Remote-hit rate needs the opt-in cache-origin capture — set buildhound { internalAdapters { collectCacheOrigins = true } } (no rate is ever guessed from the local/remote-blind hit rate).", "notice-warn"));
            } else if (cacheRoi.perMode.length) {
                app.append(rankedTable(["Mode", "Remote-hit rate", "Local-hit rate", "Task executions"], cacheRoi.perMode, r =>
                    [el("td", r.mode), el("td", pctFmt(r.remoteHitRate), "num"), el("td", pctFmt(r.localHitRate), "num"), el("td", r.consideredExecutions, "num")]));
            }
            // Ranked near-zero-CI-reuse candidate — an investigate-prompt, never a verdict (cold CI is legit).
            if (cacheRoi.ciReuseCandidate) {
                app.append(el("p", cacheRoi.ciReuseCandidate.note, "notice-warn"));
            }
        }

        // Build-Analyzer-style warning taxonomy (plan 060, research F10): rule-based candidates, each
        // carrying its own evidence — never a confirmed fix, framed as "likely/investigate" throughout.
        // Category is a fixed server enum mapped to an allowlisted CSS class (plan 012 discipline),
        // same convention as the flaky-signal badge above.
        app.append(el("h2", "Warnings — candidates to investigate"));
        if (!warnings) {
            app.append(el("p", "Warnings data unavailable.", "muted"));
        } else if (!warnings.warnings.length) {
            app.append(el("p", "No warning candidates in this window.", "muted"));
        } else {
            if (!warnings.typeDataAvailable) {
                app.append(el("p", "Task types not collected — classification is name-only (isolated projects).", "notice-warn"));
            }
            app.append(rankedTable(["Category", "Task", "Share", "Time", "Likely cause", "Evidence"], warnings.warnings, w => {
                const catCell = el("td");
                catCell.append(el("span", CATEGORY_LABEL[w.category] || w.category, "badge " + warningCategoryClass(w.category)));
                const evidenceCell = el("td");
                evidenceCell.append(el("span", w.buildsAffected + "/" + w.buildsObserved + " builds"));
                if (w.evidenceReason) evidenceCell.append(el("div", w.evidenceReason, "muted"));
                return [
                    catCell,
                    keyCell(w),
                    el("td", pctFmt(w.share), "num"),
                    el("td", ms(w.totalMs), "num"),
                    el("td", CATEGORY_COPY[w.category] || "", "muted"),
                    evidenceCell,
                ];
            }));
        }

        app.append(el("h2", "Toolchain adoption — last 30 days"));
        if (!toolchain) {
            app.append(el("p", "Toolchain data unavailable.", "muted"));
        } else {
            for (const [label, dim] of [
                ["Gradle", toolchain.gradle], ["JDK", toolchain.jdk],
                ["Android Gradle Plugin", toolchain.agp], ["Kotlin Gradle Plugin", toolchain.kgp], ["KSP", toolchain.ksp],
                ["Spring Boot", toolchain.springBoot],
            ]) {
                app.append(toolchainPanel(label, dim));
            }
        }
    }

    // Flaky tests (plan 036): the server's two-signal detection over the last 30 days. Signal is a
    // fixed server enum (RETRY|CROSS_RUN|BOTH) mapped to an allowlisted CSS class — never a class name
    // from untrusted data. All text via textContent.
    const FLAKY_SIGNAL_CLASS = { RETRY: "flaky-retry", CROSS_RUN: "flaky-cross", BOTH: "flaky-both" };
    const flakySignalClass = signal => FLAKY_SIGNAL_CLASS[signal] || "";

    async function flakyView() {
        const seq = ++renderSeq;
        const records = await api(withProjectKey("/v1/flaky?days=30"));
        if (seq !== renderSeq) return;

        app.textContent = "";
        app.append(el("h2", "Flaky tests — last 30 days"));
        if (!records.length) {
            app.append(emptyState({
                title: "No flaky tests detected",
                lines: [
                    "A class is flagged when a case fails-then-passes on retry (intra-run), or the same class",
                    "passes and fails at the same commit across runs (cross-run) — a same-sha requirement keeps",
                    "a real regression from reading as flaky.",
                ],
            }));
            return;
        }
        app.append(el("p", records.length + (records.length === 1 ? " flaky class" : " flaky classes"), "summary-sentence"));
        const table = el("table");
        const head = el("tr");
        for (const columnName of ["Module", "Class", "Flake rate", "Signal", "Samples", "First seen", "Last seen"]) {
            head.append(el("th", columnName));
        }
        table.append(head);
        for (const r of records) {
            const row = el("tr");
            row.append(el("td", r.module || "(root)"));
            row.append(el("td", r.caseName ? r.className + " · " + r.caseName : r.className));
            row.append(el("td", Math.round(r.flakeRate * 100) + "%", "num"));
            const signalCell = el("td");
            signalCell.append(el("span", r.signal, "badge " + flakySignalClass(r.signal)));
            row.append(signalCell);
            row.append(el("td", r.sampleCount, "num"));
            row.append(el("td", when(r.firstSeenMs)));
            row.append(el("td", when(r.lastSeenMs)));
            table.append(row);
        }
        app.append(table);
    }

    // Delivery-health page (plan 059, research F9): DORA-style PROXIES over already-ingested build
    // telemetry — labeled honestly throughout (CI failure share, CI recovery, build/pipeline lead-time
    // contribution, retry tax), never claimed as real DORA (no deployment or incident data). Every
    // payload-derived string reaches the DOM via el()/textContent; degraded states are honest notices,
    // never zeros read as data.
    async function deliveryHealthView() {
        const seq = ++renderSeq;
        const d = await api(withProjectKey("/v1/rollups/delivery-health?days=30"));
        if (seq !== renderSeq) return;

        app.textContent = "";
        app.append(el("p", "Delivery health — the last " + d.period + " days", "summary-sentence"));
        app.append(el("p", "DORA-style proxies computed from build telemetry BuildHound already ingests — not real DORA metrics (no deployment or incident data is collected).", "muted"));

        app.append(el("h3", "Change-failure rate (CI failure share per branch & pipeline — a proxy, not deploy failures)"));
        if (!d.changeFailureRate.length) {
            app.append(el("p", "Not enough finished builds per branch/pipeline yet — a cohort needs a few finished builds before a rate is claimed.", "muted"));
        } else {
            app.append(rankedTable(["Branch", "Pipeline", "Failed", "Succeeded", "CFR"], d.changeFailureRate, r => {
                const cfrCell = el("td", null, "num");
                cfrCell.append(el("span", pctFmt(r.changeFailureRate), "delta " + (r.failed === 0 ? "delta-good" : "delta-bad")));
                return [
                    el("td", r.branch || "—"),
                    el("td", r.pipelineName || "—"),
                    el("td", r.failed, "num"),
                    el("td", r.succeeded, "num"),
                    cfrCell,
                ];
            }));
        }

        app.append(el("h3", "Time to green (CI recovery, not production MTTR)"));
        if (!d.timeToGreen.length) {
            app.append(el("p", "No failure-to-recovery episodes in this window.", "muted"));
        } else {
            app.append(rankedTable(["Branch", "Pipeline", "Recoveries", "Median", "P90", "Status"], d.timeToGreen, r => {
                const statusCell = el("td");
                if (r.openEpisode) statusCell.append(el("span", "still red", "badge FAILED"));
                else statusCell.append(el("span", "green", "delta delta-good"));
                return [
                    el("td", r.branch || "—"),
                    el("td", r.pipelineName || "—"),
                    el("td", r.recoveries, "num"),
                    el("td", r.medianRecoveryMs == null ? "—" : ms(r.medianRecoveryMs), "num"),
                    el("td", r.p90RecoveryMs == null ? "—" : ms(r.p90RecoveryMs), "num"),
                    statusCell,
                ];
            }));
        }

        app.append(el("h3", "Lead-time contribution (time a change spends in the build & pipeline)"));
        if (!d.connectorDataAvailable) {
            app.append(el("p", "Queue time and Gradle share need a CI connector — connect one (Azure DevOps, GitHub Actions or GitLab) to populate those columns. Build durations always render.", "notice-warn"));
        }
        if (!d.leadTime.length) {
            app.append(el("p", "No finished builds in this window.", "muted"));
        } else {
            app.append(rankedTable(["Branch", "Pipeline", "Builds", "Median build time", "Median queue", "Gradle share of pipeline"], d.leadTime, r => [
                el("td", r.branch || "—"),
                el("td", r.pipelineName || "—"),
                el("td", r.buildCount, "num"),
                el("td", ms(r.medianDurationMs), "num"),
                el("td", r.medianQueuedMs == null ? "—" : ms(r.medianQueuedMs), "num"),
                el("td", r.medianGradleSharePct == null ? "—" : pctFmt(r.medianGradleSharePct), "num"),
            ]));
        }

        app.append(el("h3", "Retry tax (rerun chains priced in CI minutes)"));
        const tax = d.retryTax;
        if (!tax.chainCount) {
            app.append(el("p", "No rerun chains detected in this window.", "muted"));
        } else {
            const chips = el("ul", null, "chips");
            chips.append(chipItem("rerun chains", tax.chainCount));
            chips.append(chipItem("wasted CI minutes", tax.wastedCiMinutesLowerBound + " (lower bound)"));
            chips.append(chipItem("via runAttempt", tax.runAttemptReruns));
            chips.append(chipItem("same-key candidates", tax.sameKeyCandidates));
            app.append(chips);
            app.append(el("p", d.connectorDataAvailable
                ? "Connector-enriched reruns are priced at pipeline wall-clock; the rest at Gradle wall-clock only — still a lower bound (checkout/setup excluded). Same-key reruns are heuristic candidates, not confirmed reruns."
                : "Priced at Gradle wall-clock only (checkout/setup excluded) — a lower bound. Same-key reruns are heuristic candidates, not confirmed reruns.", "muted"));
        }
        if (d.flakyRerunTax.length) {
            app.append(el("h3", "Flaky-rerun candidates (reruns that hit a known flaky class)"));
            app.append(rankedTable(["Module", "Class", "Rerun builds", "Wasted CI minutes"], d.flakyRerunTax, c => [
                el("td", c.module || "(root)"),
                el("td", c.className),
                el("td", c.rerunBuildCount, "num"),
                el("td", c.wastedCiMinutesLowerBound + " (lower bound)", "num"),
            ]));
            const note = el("p", "Ranked candidates, not confirmed causes — not every rerun is flakiness. ", "muted");
            const link = el("a", "See the flaky-tests page");
            link.href = "#/flaky";
            note.append(link);
            app.append(note);
        }
    }

    // Admin / retention page (plan 042). Uses its OWN admin-scoped token in a separate sessionStorage
    // slot — a read token can't reach /v1/admin (403). Every string reaches the DOM via textContent.
    async function adminView() {
        const mySeq = ++renderSeq; // only the most recent render may touch the DOM (sibling-view pattern)
        tokenBar.hidden = true; // the read-token bar is irrelevant here; admin has its own field
        app.textContent = "";
        app.append(el("p", "Retention settings", "summary-sentence"));

        const adminTokenKey = "buildhound.adminToken";
        const adminToken = () => sessionStorage.getItem(adminTokenKey) || "";

        const tokenRow = el("div", null, "admin-token-row");
        tokenRow.append(el("label", "Admin token: "));
        const tokenInput = el("input");
        tokenInput.type = "password";
        tokenInput.setAttribute("autocomplete", "off");
        tokenRow.append(tokenInput);
        const useBtn = el("button", "Use");
        tokenRow.append(useBtn);
        app.append(tokenRow);

        const status = el("p", "", "muted");
        app.append(status);
        const form = el("div", null, "retention-form");
        app.append(form);

        function adminFetch(method, body) {
            const opts = { method: method, headers: { Authorization: "Bearer " + adminToken() } };
            if (body) { opts.headers["Content-Type"] = "application/json"; opts.body = body; }
            return fetch("/v1/admin/retention", opts);
        }

        function say(text, cls) { status.textContent = text; status.className = cls || "muted"; }

        function renderForm(cfg) {
            form.textContent = "";
            const rawInput = el("input"); rawInput.type = "number"; rawInput.value = String(cfg.rawDays);
            const buildInput = el("input"); buildInput.type = "number"; buildInput.value = String(cfg.buildDays);
            const rawLabel = el("label", "Raw per-build rows kept (days): "); rawLabel.append(rawInput);
            const buildLabel = el("label", "Build history kept (days): "); buildLabel.append(buildInput);
            form.append(rawLabel, buildLabel);
            const save = el("button", "Save");
            save.addEventListener("click", async () => {
                say("Saving…");
                try {
                    const res = await adminFetch("PUT", JSON.stringify({ rawDays: Number(rawInput.value), buildDays: Number(buildInput.value) }));
                    if (mySeq !== renderSeq) return; // navigated away mid-save; don't paint a stale status
                    if (res.status === 401 || res.status === 403) return say("An admin-scoped token is required.", "error");
                    if (res.status === 400) { const b = await res.json(); return say("Rejected: " + (b.error || "invalid windows"), "error"); }
                    if (!res.ok) return say("Save failed: " + res.status, "error");
                    say("Saved.");
                } catch (e) { say("Save failed: " + (e && e.message || e), "error"); }
            });
            form.append(save);
        }

        async function load() {
            form.textContent = "";
            if (!adminToken()) return say("Enter an admin-scoped token to view and edit retention windows.");
            say("Loading…");
            try {
                const res = await adminFetch("GET");
                if (mySeq !== renderSeq) return; // a newer view started while we awaited
                if (res.status === 401 || res.status === 403) return say("An admin-scoped token is required.", "error");
                if (!res.ok) return say("Could not load retention: " + res.status, "error");
                const cfg = await res.json();
                if (mySeq !== renderSeq) return;
                say("Daily aggregates are always kept; these windows purge raw rows and build history.");
                renderForm(cfg);
            } catch (e) { say("Could not load retention: " + (e && e.message || e), "error"); }
        }

        useBtn.addEventListener("click", () => {
            sessionStorage.setItem(adminTokenKey, tokenInput.value.trim());
            tokenInput.value = ""; // never leave the token in the live DOM
            load();
        });

        await load();
    }

    function route() {
        // decodeURIComponent throws synchronously on malformed input (Firefox returns
        // location.hash pre-decoded, so a stored %xx can arrive re-broken) — the try
        // must cover it, not just the promise.
        try {
            // Admin/retention (plan 042) uses its own admin token, not the read token — reachable even
            // without a read token, so it's handled before the read-token first-run gate.
            if ((location.hash || "").startsWith("#/admin")) { adminView().catch(fail); return; }
            // No token yet → a friendly first-run panel instead of a request that 401s
            // and paints red error text as the first thing a pilot user sees (plan 018).
            if (!token()) { firstRunView(); return; }
            // Bottlenecks is the landing page (plan 032): a bare "#/", "#", or empty hash lands there.
            let hash = location.hash || "#/bottlenecks";
            if (hash === "#/" || hash === "#") hash = "#/bottlenecks";
            const detail = hash.match(/^#\/build\/(.+)$/);
            const compare = hash.match(/^#\/compare\/([^/]+)\/(.+)$/);
            const run = detail ? detailView(decodeURIComponent(detail[1]))
                : compare ? comparisonView(decodeURIComponent(compare[1]), decodeURIComponent(compare[2]))
                : hash.startsWith("#/compare") ? compareView()
                : hash.startsWith("#/builds") ? buildsView({}, 0)
                : hash.startsWith("#/trends") ? trendsView({}, 30)
                : hash.startsWith("#/tasks") ? tasksRollupView()
                : hash.startsWith("#/tests") ? testsView()
                : hash.startsWith("#/flaky") ? flakyView()
                : hash.startsWith("#/delivery") ? deliveryHealthView()
                : hash.startsWith("#/benchmark") ? benchmarkView()
                : hash.startsWith("#/bottlenecks") ? bottlenecksView(7)
                : bottlenecksView(7);
            run.catch(fail);
        } catch (err) {
            fail(err);
        }
    }

    window.addEventListener("hashchange", route);
    // A token may already be in sessionStorage from an earlier page load; populate the project
    // selector without blocking the first route() (plan 077 — best-effort, never gates routing).
    if (token()) populateProjectSelect().catch(() => {});
    route();
})();
