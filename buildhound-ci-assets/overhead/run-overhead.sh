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
# The Gradle version gradle-profiler drives the fixture with — pinned (and matched to the CI
# setup-gradle version) so the driving Gradle is explicit, not an implicit PATH/no-wrapper fallback.
gradle_version="${BUILDHOUND_OVERHEAD_GRADLE:-9.6.1}"

if ! command -v gradle-profiler >/dev/null 2>&1; then
    echo "gradle-profiler not on PATH — install it (version pinned by the overhead-budget CI job)" >&2
    exit 3
fi

# Loopback upload sink for the no_op_upload cell; stopped on exit.
python3 "$here/bin/loopback-sink.py" "$port" &
sink_pid=$!
trap 'kill "$sink_pid" 2>/dev/null || true' EXIT

# Wait for the sink to accept connections before the first build races its bind (bounded).
i=0
while [ "$i" -lt 50 ]; do
    if curl -fsS -o /dev/null "$BUILDHOUND_OVERHEAD_SINK" 2>/dev/null; then break; fi
    i=$((i + 1))
    sleep 0.1
done

mkdir -p "$out"

run_variant() {
    variant="$1"
    gradle-profiler --benchmark --measure-config-time \
        --gradle-version "$gradle_version" \
        --project-dir "$fixture" \
        --scenario-file "$here/overhead.scenarios" \
        --output-dir "$out/$variant" \
        --gradle-user-home "$out/guh-$variant" \
        -Pbuildhound.overhead.plugin="$variant" \
        no_op incremental cc_hit no_op_upload no_op_ci
}

# Toggle self-test (anti-rot, plan 034 §5): plugin-on must emit telemetry; plugin-off must not.
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

# Verdict (math in buildhound-commons); its non-zero exit on a breach propagates out of this script.
exec "$here/bin/buildhound-overhead" "$out/on/benchmark.csv" "$out/off/benchmark.csv"
