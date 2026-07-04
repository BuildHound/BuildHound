// Zero-CDN OpenAPI viewer (plan 042): fetch the spec text, extract the route list with a small
// line parser (no YAML lib, no Swagger-UI CDN — the strict CSP forbids external script/style), and
// render a table. Every cell is set via textContent, so nothing from the spec can inject markup.
'use strict';

(function () {
  const app = document.getElementById('app');

  function parseRoutes(text) {
    const lines = text.split('\n');
    let inPaths = false;
    let curPath = null;
    const rows = [];
    for (const line of lines) {
      if (/^paths:\s*$/.test(line)) { inPaths = true; continue; }
      if (!inPaths) continue;
      // A new top-level key (column 0, non-space) ends the paths block.
      if (/^\S/.test(line)) { inPaths = false; continue; }
      const pathM = line.match(/^ {2}(\/\S*):\s*$/);
      if (pathM) { curPath = pathM[1]; continue; }
      const methM = line.match(/^ {4}(get|post|put|delete|patch):\s*$/);
      if (methM && curPath) { rows.push({ method: methM[1].toUpperCase(), path: curPath, summary: '' }); continue; }
      const sumM = line.match(/^ {6}summary:\s*(.+?)\s*$/);
      if (sumM && rows.length) {
        rows[rows.length - 1].summary = sumM[1].replace(/^["']|["']$/g, '');
      }
    }
    return rows;
  }

  function render(rows) {
    app.textContent = '';
    const table = document.createElement('table');
    const head = document.createElement('tr');
    ['Method', 'Path', 'Summary'].forEach(function (h) {
      const th = document.createElement('th');
      th.textContent = h;
      head.appendChild(th);
    });
    table.appendChild(head);
    rows.forEach(function (r) {
      const tr = document.createElement('tr');
      const m = document.createElement('td');
      m.className = 'method m-' + r.method.toLowerCase();
      m.textContent = r.method;
      const p = document.createElement('td');
      p.className = 'path';
      p.textContent = r.path;
      const s = document.createElement('td');
      s.textContent = r.summary;
      tr.appendChild(m); tr.appendChild(p); tr.appendChild(s);
      table.appendChild(tr);
    });
    app.appendChild(table);
  }

  fetch('/openapi.yaml')
    .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); })
    .then(function (text) {
      const rows = parseRoutes(text);
      if (rows.length === 0) { app.textContent = 'No routes found in the spec.'; return; }
      render(rows);
    })
    .catch(function (e) {
      app.textContent = '';
      const div = document.createElement('div');
      div.className = 'err';
      div.textContent = 'Could not load /openapi.yaml: ' + e.message;
      app.appendChild(div);
    });
})();
