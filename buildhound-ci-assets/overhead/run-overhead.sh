#!/usr/bin/env sh
# Plugin-overhead self-benchmark harness (plan 034). Runs gradle-profiler on the fixture twice —
# plugin applied vs not — then evaluates the two benchmark.csv outputs against the overhead budget.
# Exits non-zero on a budget breach (or a broken toggle). Requires gradle-profiler + python3 on PATH.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
fixture="$here/fixture"
out="${OVERHEAD_OUT:-$here/build}"
port="${BUILDHOUND_OVERHEAD_PORT:-8099}"
export BUILDHOUND_OVERHEAD_SINK="http://127.0.0.1:$port"
# Measure the plugin, not the developer's live configuration. `no_op_ci` deliberately runs in CI
# mode with NO server so it can be the upload axis's baseline — but the plugin falls back to
# BUILDHOUND_SERVER_URL/BUILDHOUND_TOKEN from the environment, so a dogfooding shell would both
# distort that baseline and POST 16 synthetic fixture builds at a real ingest.
unset BUILDHOUND_SERVER_URL BUILDHOUND_TOKEN
# The Gradle version gradle-profiler drives the fixture with — pinned (and matched to the CI
# setup-gradle version) so the driving Gradle is explicit, not an implicit PATH/no-wrapper fallback.
gradle_version="${BUILDHOUND_OVERHEAD_GRADLE:-9.6.1}"

if ! command -v gradle-profiler >/dev/null 2>&1; then
    echo "gradle-profiler not on PATH — install it (version pinned by the overhead-budget CI job)" >&2
    exit 3
fi

# Refuse to run against a sink we did not start. A leaked sink from an earlier run answers the
# readiness probe below, so ours could fail to bind and the harness would measure uploads against a
# foreign process — or, worse, one that dies mid-run.
if curl -fsS -o /dev/null "$BUILDHOUND_OVERHEAD_SINK" 2>/dev/null; then
    echo "port $port is already serving — something else (a leaked sink from an earlier run?) holds it." >&2
    echo "Stop it, or set BUILDHOUND_OVERHEAD_PORT to a free port." >&2
    exit 5
fi

# Loopback upload sink for the no_op_upload cell; stopped on exit.
python3 "$here/bin/loopback-sink.py" "$port" &
sink_pid=$!
trap 'kill "$sink_pid" 2>/dev/null || true' EXIT

# Wait for the sink to accept connections before the first build races its bind (bounded).
sink_ready=0
i=0
while [ "$i" -lt 50 ]; do
    if curl -fsS -o /dev/null "$BUILDHOUND_OVERHEAD_SINK" 2>/dev/null; then sink_ready=1; break; fi
    i=$((i + 1))
    sleep 0.1
done

# Assert it, don't assume it. `python3 ... &` is backgrounded, so `set -e` never sees a bind failure,
# and the plugin degrades an unreachable server to a warn rather than failing the build (a hard rule,
# CLAUDE.md). Without this check a dead sink makes no_op_upload return early and the upload axis
# reports a spuriously tiny overhead — a false pass of exactly the kind this harness must not produce.
if [ "$sink_ready" -ne 1 ]; then
    echo "loopback sink never accepted a connection on port $port — refusing to measure the upload" >&2
    echo "axis against a closed port (it would report a false pass)." >&2
    exit 5
fi

mkdir -p "$out"

run_variant() {
    variant="$1"
    # Validate rather than trust: an empty variant would reach the build as a bare
    # `-Pbuildhound.overhead.plugin=`, which the fixture reads as its `off` default — so a nominal
    # plugin-on run would measure no plugin at all. HOCON's mandatory ${VAR} aborts on an *unset*
    # variable but happily substitutes an empty one, so the guard belongs here.
    case "$variant" in
        on | off) ;;
        *) echo "run_variant: expected 'on' or 'off', got '$variant'" >&2; exit 2 ;;
    esac
    # The variant reaches the build through the scenario file's gradle-args, which read
    # BUILDHOUND_OVERHEAD_PLUGIN from the environment: gradle-profiler has no -P (nor
    # --gradle-argument) option of its own, and passing one makes it exit with a usage dump.
    # Scoped to this command, not exported, so the `on` value cannot leak into the `off` run.
    BUILDHOUND_OVERHEAD_PLUGIN="$variant" \
    gradle-profiler --benchmark --measure-config-time \
        --gradle-version "$gradle_version" \
        --project-dir "$fixture" \
        --scenario-file "$here/overhead.scenarios" \
        --output-dir "$out/$variant" \
        --gradle-user-home "$out/guh-$variant" \
        no_op incremental cc_hit no_op_upload no_op_ci
}

# Toggle self-test (anti-rot, plan 034 §5): plugin-on must emit telemetry; plugin-off must not.
# Pre-clean first, or a leftover directory from an earlier run would let plugin-on pass the check
# on someone else's output — the self-test has to observe THIS run.
rm -rf "$fixture/build/buildhound"
run_variant on
if [ ! -d "$fixture/build/buildhound" ]; then
    echo "self-test FAILED: plugin-on produced no build/buildhound output — the toggle is broken" >&2
    exit 4
fi
rm -rf "$fixture/build/buildhound"

run_variant off
if [ -d "$fixture/build/buildhound" ]; then
    echo "self-test FAILED: plugin-off produced telemetry — the toggle is broken" >&2
    exit 4
fi

# Verdict (math in buildhound-commons); its non-zero exit on a breach propagates out of this script
# via `set -e`. Deliberately NOT `exec`: exec replaces this shell, so the EXIT trap above never runs
# and the sink is orphaned — it then keeps the port bound, and the next run's readiness probe
# succeeds against that stale sink while its own fails to bind, hiding the failure (plan 106).
"$here/bin/buildhound-overhead" "$out/on/benchmark.csv" "$out/off/benchmark.csv"
