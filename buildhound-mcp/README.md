# buildhound-mcp

An **opt-in, read-only** [Model Context Protocol](https://modelcontextprotocol.io) server that exposes
the BuildHound query API to an agent/LLM client over stdio (plan 042, spec §5: "the query API doubles
as the MCP tool surface").

It is a **separate artifact** — deliberately **not** bundled into the `buildhound-server` ingest image.
That image is a hardened, non-root, network-facing service; a local stdio agent tool has no place in it.
The MCP server holds only a `read`-scoped token and issues only `GET` requests, so a compromised agent
can read this tenant's build history but can never ingest, change retention, or reach another tenant.

## Tools (all read-only)

| Tool | Query |
|---|---|
| `list_builds` | `GET /v1/builds` (filter by `branch`/`mode`/`outcome`, paginate with `limit`/`offset`) |
| `get_build` | `GET /v1/builds/{buildId}` |
| `trends` | `GET /v1/trends?days=N` |
| `project_cost` | `GET /v1/rollups/project-cost?days=N` |
| `task_duration` | `GET /v1/rollups/task-duration?days=N` |
| `negative_avoidance` | `GET /v1/rollups/negative-avoidance?days=N` |

There is **no** write or admin tool by construction — a test (`ToolsTest`) fails if one is ever added.

## Configuration (env-only)

| Variable | Meaning |
|---|---|
| `BUILDHOUND_URL` | The BuildHound server base URL, e.g. `https://buildhound.example.com` (required). |
| `BUILDHOUND_TOKEN` | A **read-scoped** API token. Only ever sent as an `Authorization: Bearer` header, never logged. |

The token comes from the environment (architecture §6) — never a flag or a config literal.

## Run it

```bash
./gradlew :buildhound-mcp:installDist
BUILDHOUND_URL=https://buildhound.example.com \
BUILDHOUND_TOKEN=… \
  buildhound-mcp/build/install/buildhound-mcp/bin/buildhound-mcp
```

Register it with an MCP-capable agent (Claude Desktop / Claude Code shown; adapt for others):

```jsonc
{
  "mcpServers": {
    "buildhound": {
      "command": "/path/to/buildhound-mcp/build/install/buildhound-mcp/bin/buildhound-mcp",
      "env": {
        "BUILDHOUND_URL": "https://buildhound.example.com",
        "BUILDHOUND_TOKEN": "<a read-scoped token>"
      }
    }
  }
}
```

Diagnostics go to **stderr** so they never corrupt the JSON-RPC stream on stdout.

## Why hand-rolled JSON-RPC (not the MCP SDK)

The official [`io.modelcontextprotocol:kotlin-sdk`](https://github.com/modelcontextprotocol/kotlin-sdk)
exists (0.14.0 at the time of writing) but is still pre-1.0 and pulls a Ktor/coroutines/kotlinx-io
stack. For a surface this small — six read-only `GET` proxies — a minimal JSON-RPC-2.0-over-newline-
delimited-stdio server (`McpServer`) is cheaper, has no 0.x API-churn coupling, and depends only on
`kotlinx-serialization-json` (already in the version catalog) plus the JDK `HttpClient`. If the tool
surface grows or the SDK reaches 1.0, revisit. This choice is recorded in the architecture decision log.
