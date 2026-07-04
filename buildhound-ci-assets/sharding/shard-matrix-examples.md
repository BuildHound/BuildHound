# Test sharding — CI matrix examples (plan 040)

The `dev.buildhound.test-sharding` addon splits a build's `Test` tasks across N parallel CI jobs.
Each job runs the **same** Gradle build but sets three env vars; the addon fetches a
server-balanced plan (LPT over 30-day p90 CI class timings) and filters each `Test` task to that
shard's classes. **No `BUILDHOUND_SHARD_INDEX` ⇒ the addon is inert** (a normal, un-sharded build).

## The interface (env, read via providers — CC-safe)

| Variable | Meaning |
|---|---|
| `BUILDHOUND_SHARD_INDEX` | This shard, **1-based** (`1`…`BUILDHOUND_SHARD_TOTAL`). |
| `BUILDHOUND_SHARD_TOTAL` | Total shard count for this run. |
| `BUILDHOUND_SHARD_REFERENCE` | Keys the idempotent plan so every shard of one run reads the *same* plan. Defaults to `BUILDHOUND_CI_RUN_ID` when unset. |
| `BUILDHOUND_SERVER_URL` | BuildHound server (the addon `POST`s `/v1/addons/test-sharding/plan`). |
| `BUILDHOUND_TOKEN` | An **ingest**-scoped token (secret). Sent only as a `Bearer` header, never logged. |

All shards **must** share one `BUILDHOUND_SHARD_REFERENCE` (the run id) — that is what makes the
plan stable across the parallel jobs. Every failure (no server / timeout / non-2xx / no timings)
degrades to **running all tests on that shard** — slower, never wrong, never red.

Apply the addon alongside core in `settings.gradle.kts`:

```kotlin
plugins {
    id("dev.buildhound")               // core (telemetry) — optional here, enables the feedback block
    id("dev.buildhound.test-sharding") // the sharding addon
}
```

## GitHub Actions (native dynamic matrix)

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        shard: [1, 2, 3, 4]        # BUILDHOUND_SHARD_TOTAL = length of this list
    env:
      BUILDHOUND_SHARD_INDEX: ${{ matrix.shard }}
      BUILDHOUND_SHARD_TOTAL: 4
      BUILDHOUND_SHARD_REFERENCE: ${{ github.run_id }}
      BUILDHOUND_SERVER_URL: https://buildhound.example.com
      BUILDHOUND_TOKEN: ${{ secrets.BUILDHOUND_TOKEN }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - run: ./gradlew test
```

## Azure DevOps (static shard matrix)

Azure has no dynamic matrix, so pick a fixed shard count. `System.JobPositionInPhase` /
`System.TotalJobsInPhase` are **not** used — set the count explicitly so `_TOTAL` matches the matrix.

```yaml
jobs:
  - job: test
    strategy:
      matrix:
        shard1: { SHARD_INDEX: '1' }
        shard2: { SHARD_INDEX: '2' }
        shard3: { SHARD_INDEX: '3' }
        shard4: { SHARD_INDEX: '4' }
    variables:
      BUILDHOUND_SHARD_INDEX: $(SHARD_INDEX)
      BUILDHOUND_SHARD_TOTAL: '4'
      BUILDHOUND_SHARD_REFERENCE: $(Build.BuildId)
      BUILDHOUND_SERVER_URL: 'https://buildhound.example.com'
      BUILDHOUND_TOKEN: $(buildhoundToken)   # a secret variable; mapped to env, never echoed
    steps:
      - task: JavaToolInstaller@0
        inputs: { versionSpec: '21', jdkArchitectureOption: x64, jdkSourceOption: PreInstalled }
      - script: ./gradlew test
        env:
          BUILDHOUND_TOKEN: $(BUILDHOUND_TOKEN)   # secrets must be mapped into env explicitly on Azure
```

## Notes

- **Balance improves over time.** The first run for a project has no timings, so the plan is a
  5-second-per-suite round-robin; after CI runs accumulate, the server balances on 30-day p90.
- **Deterministic discovery.** Each shard discovers its own compiled test classes; the last shard
  also runs anything the plan didn't assign (catch-all), so the class lists union to the full set.
- **Retries** re-use the same `BUILDHOUND_SHARD_REFERENCE` ⇒ the same plan (idempotent).
