# Self-hosting BuildHound

BuildHound ships as a single OCI image (`buildhound-server`) plus a Postgres/TimescaleDB database. This
guide takes you from nothing to an ingesting, queryable instance an outside team can run (roadmap phase-4
exit). Everything is configured through environment variables — no secret ever lives in an image layer,
a config file, or a log (architecture §6).

## 1. Quick start (Docker Compose)

The repo ships a local stack in [`deploy/compose.yaml`](../deploy/compose.yaml): the server plus a
digest-pinned TimescaleDB. From the repo root:

```bash
docker compose -f deploy/compose.yaml up --build
```

That starts:
- **Postgres/TimescaleDB** with the schema Flyway-migrated on the server's first boot.
- **buildhound-server** on `http://localhost:8080` (`GET /health`, dashboard at `/`, API docs at `/docs`).

The compose file bootstraps a pilot project + token from `BUILDHOUND_BOOTSTRAP_PROJECT` /
`BUILDHOUND_BOOTSTRAP_TOKEN`. Generate a real token with `openssl rand -hex 32` and set it before any
shared use — the checked-in value is for local dev only.

## 2. Generic single-container deployment

For a managed platform (Kubernetes, ECS, Fly, a plain VM), run the published image against an external
managed Postgres 16 with the TimescaleDB extension:

```bash
docker build -f buildhound-server/Dockerfile -t buildhound-server .   # from the repo root
docker run -p 8080:8080 \
  -e BUILDHOUND_DB_URL="jdbc:postgresql://db.internal:5432/buildhound" \
  -e BUILDHOUND_DB_USER="buildhound" \
  -e BUILDHOUND_DB_PASSWORD="$(cat /run/secrets/db-password)" \
  -e BUILDHOUND_BOOTSTRAP_PROJECT="pilot" \
  -e BUILDHOUND_BOOTSTRAP_TOKEN="$(cat /run/secrets/bootstrap-token)" \
  buildhound-server
```

The image is non-root (`uid 10001`), JRE-only, and its base is digest-pinned. Flyway runs the migrations
idempotently on boot, so a rolling deploy against a shared DB is safe.

### Environment contract

| Variable | Default | Meaning |
|---|---|---|
| `BUILDHOUND_PORT` | `8080` | HTTP listen port. |
| `BUILDHOUND_DB_URL` | *(unset → in-memory)* | JDBC Postgres URL. **Unset means in-memory storage — data is lost on restart** (dev/smoke only). |
| `BUILDHOUND_DB_USER` | `buildhound` | DB user. |
| `BUILDHOUND_DB_PASSWORD` | *(required with DB_URL)* | DB password (from a secret). |
| `BUILDHOUND_BOOTSTRAP_PROJECT` | — | Idempotently create this project on boot. |
| `BUILDHOUND_BOOTSTRAP_TOKEN` | — | Attach this token (hashed) to the bootstrap project. Provisions the first `all`-scope token. |
| `BUILDHOUND_DASHBOARD_URL` | — | Public dashboard base URL used in alert deep links. |
| `BUILDHOUND_INGEST_RPM` / `BUILDHOUND_QUERY_RPM` / `BUILDHOUND_HOST_RPM` | `0` (off) | Per-token ingest/query and per-host rate limits (requests/min). Behind a reverse proxy the per-host limiter keys on the direct peer — set it there. |
| `BUILDHOUND_RETENTION_SWEEP_HOURS` | `24` | Retention purge interval; `0` disables it. Instance-local — run the sweep on **one** replica (see §5). |
| `BUILDHOUND_CONNECTOR_{AZURE,GITHUB,GITLAB}_*` | *(unset → inert)* | CI-timeline connector credentials + host allowlists — see [`buildhound-ci-assets/README.md`](../buildhound-ci-assets/README.md). Unset ⇒ the connector never dials out. |

## 3. Token provisioning

Tokens are hashed (SHA-256) before storage — the plaintext never lands in the DB or a log. Scopes gate
what a token can do:

| Scope | Grants |
|---|---|
| `ingest` | `POST /v1/builds`, `/v1/metrics`, connector hooks, the shard-plan endpoint. |
| `read` | The query API (`GET /v1/builds`, `/v1/trends`, `/v1/rollups/*`, …) — what the dashboard and MCP server use. |
| `addon` | The `/v1/addons/*` namespace. |
| `admin` | `/v1/admin/*` (retention config). |
| `all` | Everything. |

The bootstrap token is `all`-scope. Issue narrower tokens per use — a CI job gets an `ingest` token, a
dashboard user a `read` token, an operator an `admin` token. (Per-token minting is a follow-up admin
plan; today, seed additional tokens directly in the `api_tokens` table with the hash of
`openssl rand -hex 32`.)

## 4. Retention

BuildHound keeps raw per-build rows for `retention_raw_days` (default **90**), build-level history for
`retention_build_days` (default **395** ≈ 13 months), and daily aggregates indefinitely. A nightly sweep
(§`BUILDHOUND_RETENTION_SWEEP_HOURS`) purges rows past their window; daily aggregates are never purged.

Set per-project windows via the admin API (or the dashboard **Admin** page) with an `admin`-scope token:

```bash
curl -X PUT https://buildhound.example.com/v1/admin/retention \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"rawDays": 60, "buildDays": 365}'
```

Windows are validated to `[1, 3650]` days with `buildDays >= rawDays`. **Take a backup before shrinking
a window** — the purge is irreversible.

### TimescaleDB sizing

Storage scales with build volume × retention. As a rough guide, a build payload is a few KB of jsonb
plus one `task_executions` row per task; multiply by builds/day × `retention_raw_days` for the raw tier,
and by builds/day × `retention_build_days` for the (payload-only) build tier. Start with the defaults,
watch the DB size, and lower the windows (or raise the DB volume) as the fleet grows. Continuous
aggregates / hypertable conversion are deferred until volume demands them (the retention sweep purges
raw rows on a schedule and does not require hypertables).

## 5. Backup, restore, and replicas

- **Backup:** `pg_dump` the BuildHound database on a schedule (or snapshot the volume). Do this **before**
  enabling aggressive retention windows.
  ```bash
  pg_dump --format=custom --file=buildhound-$(date +%F).dump "$DATABASE_URL"
  ```
- **Restore:** `pg_restore --clean --if-exists --dbname="$DATABASE_URL" buildhound-YYYY-MM-DD.dump`.
- **Multiple replicas:** the retention sweep is **instance-local** (architecture §5). Running N replicas
  makes each run the sweep — the deletes are idempotent but wasteful. Run the sweep on **one** instance
  (set `BUILDHOUND_RETENTION_SWEEP_HOURS=0` on the others), or wait for the advisory-lock guard (a noted
  follow-up). Ingest and queries are stateless and scale horizontally.

## 6. Observability & docs

- `GET /health` — liveness.
- `GET /docs` + `GET /openapi.yaml` — the versioned API reference (zero external requests; the OpenAPI
  spec is kept in lockstep with the live routes by a contract test).
- The dashboard (`/`) needs only a `read` token, entered in the browser tab and kept in `sessionStorage`.

## 7. Optional: MCP query server

An opt-in, read-only [MCP](https://modelcontextprotocol.io) server ([`buildhound-mcp`](../buildhound-mcp/README.md))
exposes the query API to an agent/LLM client over stdio. It is a **separate artifact** — never bundled
into this ingest image — and holds only a `read` token.

## 8. Azure DevOps Marketplace extension — decision (deferred)

**Status: deferred.** The roadmap listed a Marketplace extension "if adoption warrants". BuildHound already
integrates with Azure Pipelines through the reusable YAML template
([`buildhound-ci-assets/azure-pipelines/`](../buildhound-ci-assets/README.md)) and the server-side Azure
connector (plan 028), which cover the pilot's needs without a Marketplace listing. A Marketplace extension
adds a publishing/versioning/support surface (VS Marketplace account, extension manifest, review cadence)
that is not justified until there is external Azure-DevOps demand.

**Revisit when:** ≥ 2 external teams ask for a one-click Azure DevOps install, or the YAML-template
onboarding proves to be a real adoption blocker. Until then, the template + connector are the supported
Azure path. Recorded in the architecture decision log.
