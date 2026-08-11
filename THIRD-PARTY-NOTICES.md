# Third-party notices

BuildHound is Apache-2.0. This file records third-party code **vendored into this
repository** — source files copied in verbatim and shipped as part of a BuildHound
artifact. Resolved build dependencies (Gradle/Maven coordinates in
`gradle/libs.versions.toml`) are not listed here; they are declared, not vendored.

Every entry records the upstream version, the exact source archive, the integrity
value published by the upstream registry, and the license. A version bump must
update the recorded hashes in the same commit.

## uPlot 1.6.32 — MIT

- Upstream: <https://github.com/leeoniya/uPlot>
- Vendored at: `buildhound-server/src/main/resources/web/uplot.js`, served at `/uplot.js`
- Source archive: <https://registry.npmjs.org/uplot/-/uplot-1.6.32.tgz>, file `dist/uPlot.iife.min.js`
- Archive integrity (npm registry `dist.integrity`, independently re-verified against the
  downloaded bytes):
  `sha512-KIMVnG68zvu5XXUbC4LQEPnhwOxBuLyW1AHtpm6IKTXImkbLgkMy+jabjLgSLMasNuGGzQm/ep3tOkyTxpiQIw==`
- Vendored file content: a BuildHound provenance header (including the full MIT license
  text) followed by the upstream bytes verbatim,
  sha256 `19c8d4c6ad88929a79f4ae49d6f7161566dfd0ba3d15cc495e974f787eb78f1f`
- Used by: the server dashboard's charts (plan 108). It is **not** used by
  `buildhound-report` — the standalone artifact stays dependency-free and network-free
  (`ReportAssetsTest`).

Copyright (c) 2022 Leon Sorokin. Licensed under the MIT License; the full license text is
reproduced in the header of the vendored file.
