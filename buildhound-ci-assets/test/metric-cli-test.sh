#!/usr/bin/env sh
# Harness for bin/buildhound-metric (plan 025): a stubbed `curl` on PATH captures the request so we
# can assert correlation mapping, env-only token handling, and soft-fail behaviour. No network.
# Run: sh buildhound-ci-assets/test/metric-cli-test.sh
set -eu

here="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
cli="$here/../bin/buildhound-metric"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Stub curl: write the args + body to files, print the status from CURL_STATUS (default 202).
cat > "$work/curl" <<'STUB'
#!/usr/bin/env sh
printf '%s\n' "$*" > "$CURL_CAPTURE_ARGS"
prev=""
for a in "$@"; do
    if [ "$prev" = "--data" ]; then printf '%s' "$a" > "$CURL_CAPTURE_BODY"; fi
    prev="$a"
done
printf '%s' "${CURL_STATUS:-202}"
STUB
chmod +x "$work/curl"
PATH="$work:$PATH"
export CURL_CAPTURE_ARGS="$work/args" CURL_CAPTURE_BODY="$work/body"

fail() { echo "FAIL: $1" >&2; exit 1; }
contains() { grep -q -- "$2" "$1" || fail "$3"; }

# 1. Azure correlation: provider azure-devops + runId from BUILD_BUILDID; body has scope/name/value.
TF_BUILD=True BUILD_BUILDID=20260704 \
    BUILDHOUND_SERVER_URL="https://bh.example.com" BUILDHOUND_TOKEN="secret-tok" \
    sh "$cli" --name sign.duration --value 42 --unit s || fail "azure run exited non-zero"
contains "$work/body" '"provider":"azure-devops"' "azure provider missing"
contains "$work/body" '"runId":"20260704"' "azure runId missing"
contains "$work/body" '"name":"sign.duration"' "name missing"
contains "$work/body" '"value":42' "value missing"
# The token rides an Authorization header, never a positional/logged flag beyond the header value.
contains "$work/args" 'Authorization: Bearer secret-tok' "token header missing"

# 2. GitHub Actions correlation.
GITHUB_ACTIONS=true GITHUB_RUN_ID=55 \
    BUILDHOUND_SERVER_URL="https://bh.example.com" BUILDHOUND_TOKEN="t" \
    sh "$cli" --name x --value 1 || fail "gha run exited non-zero"
contains "$work/body" '"provider":"github-actions"' "gha provider missing"
contains "$work/body" '"runId":"55"' "gha runId missing"

# 3. Explicit BUILDHOUND_BUILD_ID wins over CI detection.
TF_BUILD=True BUILD_BUILDID=999 BUILDHOUND_BUILD_ID=explicit-1 \
    BUILDHOUND_SERVER_URL="https://bh.example.com" BUILDHOUND_TOKEN="t" \
    sh "$cli" --name x --value 1 || fail "explicit build id run failed"
contains "$work/body" '"buildId":"explicit-1"' "explicit buildId missing"

# 4. Transport error (curl 000) is soft by default (exit 0) but hard with --strict.
CURL_STATUS=000 CI=true BUILDHOUND_SERVER_URL="https://bh.example.com" BUILDHOUND_TOKEN="t" \
    sh "$cli" --name x --value 1 || fail "transport error must be soft (exit 0) by default"
if CURL_STATUS=000 CI=true BUILDHOUND_SERVER_URL="https://bh.example.com" BUILDHOUND_TOKEN="t" \
    sh "$cli" --strict --name x --value 1 2>/dev/null; then
    fail "--strict must propagate a transport error as non-zero"
fi

# 5. Missing token is soft (never sends), missing name is a hard usage error.
CI=true BUILDHOUND_SERVER_URL="https://bh.example.com" sh "$cli" --name x --value 1 \
    || fail "missing token must be soft by default"
if CI=true BUILDHOUND_SERVER_URL="https://bh.example.com" BUILDHOUND_TOKEN="t" sh "$cli" --value 1 2>/dev/null; then
    fail "missing --name must be a hard error"
fi

# 6. A non-numeric --value is a hard error (no JSON injection via the raw-spliced value field).
if CI=true BUILDHOUND_SERVER_URL="https://bh.example.com" BUILDHOUND_TOKEN="t" \
    sh "$cli" --name x --value '1,"unit":"pwned"' 2>/dev/null; then
    fail "non-numeric --value must be rejected"
fi
# A well-formed number (incl. scientific / signed) is accepted.
CI=true BUILDHOUND_SERVER_URL="https://bh.example.com" BUILDHOUND_TOKEN="t" \
    sh "$cli" --name x --value '-4.2e3' || fail "a valid number must be accepted"
contains "$work/body" '"value":-4.2e3' "numeric value missing"

echo "metric-cli-test OK"
