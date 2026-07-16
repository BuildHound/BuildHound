#!/usr/bin/env sh
# Harness for bin/buildhound-publish (plan 094): a stubbed `curl` on PATH captures the request
# so we can assert endpoint/header handling and the soft-fail contract (a dead staging must
# never redden a PR). No network.
# Run: sh buildhound-ci-assets/test/publish-cli-test.sh
set -eu

here="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
cli="$here/../bin/buildhound-publish"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Stub curl: append the args to CURL_CAPTURE_ARGS (one line per invocation), write
# CURL_RESPONSE (default: accepted) to the -o target, print the status from CURL_STATUS
# (default 202).
cat > "$work/curl" <<'STUB'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$CURL_CAPTURE_ARGS"
prev=""
out=""
for a in "$@"; do
    if [ "$prev" = "-o" ]; then out="$a"; fi
    prev="$a"
done
resp="${CURL_RESPONSE:-}"
if [ -z "$resp" ]; then resp='{"status":"accepted"}'; fi
if [ -n "$out" ]; then printf '%s' "$resp" > "$out"; fi
printf '%s' "${CURL_STATUS:-202}"
STUB
chmod +x "$work/curl"
PATH="$work:$PATH"
export CURL_CAPTURE_ARGS="$work/args"

fail() { echo "FAIL: $1" >&2; exit 1; }
contains() { grep -q -- "$2" "$1" || fail "$3"; }

printf '{"buildId":"b-1"}' > "$work/p1.json"
printf '{"buildId":"b-2"}' > "$work/p2.json"

# 1. Success: every file is POSTed to <server>/v1/builds (trailing slash trimmed) with the
#    env token riding an Authorization header; exit 0 and a summary log.
out=$(BUILDHOUND_SERVER_URL="https://staging.example.com/" BUILDHOUND_TOKEN="secret-tok" \
    sh "$cli" "$work/p1.json" "$work/p2.json" 2>&1) || fail "success case exited non-zero"
contains "$work/args" 'https://staging.example.com/v1/builds' "endpoint missing or trailing slash kept"
contains "$work/args" 'Authorization: Bearer secret-tok' "token header missing"
contains "$work/args" "@$work/p1.json" "first payload not posted"
contains "$work/args" "@$work/p2.json" "second payload not posted"
test "$(grep -c '/v1/builds' "$work/args")" = 2 || fail "expected exactly two POSTs"
case "$out" in *"2 published"*) ;; *) fail "success summary missing" ;; esac

# 2. A "duplicate" 2xx response counts as success (ingest dedups on (project_id, build_id);
#    re-POSTs from job re-runs and multi-env replays are safe).
out=$(CURL_RESPONSE='{"status":"duplicate"}' \
    BUILDHOUND_SERVER_URL="https://staging.example.com" BUILDHOUND_TOKEN="t" \
    sh "$cli" "$work/p1.json" 2>&1) || fail "duplicate case exited non-zero"
case "$out" in *duplicate*) ;; *) fail "duplicate log missing" ;; esac
case "$out" in *"1 published"*) ;; *) fail "duplicate must count as published" ;; esac

# 3. 4xx: dropped with a warning, exit 0 (a bad payload will not get better on retry).
out=$(CURL_STATUS=400 BUILDHOUND_SERVER_URL="https://staging.example.com" BUILDHOUND_TOKEN="t" \
    sh "$cli" "$work/p1.json" 2>&1) || fail "4xx must be soft (exit 0)"
case "$out" in *"HTTP 400"*) ;; *) fail "4xx drop warning missing" ;; esac

# 4. Unreachable server (curl transport error, code 000): soft-fail warning, exit 0.
out=$(CURL_STATUS=000 BUILDHOUND_SERVER_URL="https://staging.example.com" BUILDHOUND_TOKEN="t" \
    sh "$cli" "$work/p1.json" 2>&1) || fail "transport error must be soft (exit 0)"
case "$out" in *"could not reach"*) ;; *) fail "transport warning missing" ;; esac

# 5. Missing env (credentials not minted yet) skips cleanly: exit 0, curl never invoked.
: > "$work/args"
out=$(BUILDHOUND_TOKEN="t" sh "$cli" "$work/p1.json" 2>&1) || fail "missing URL must skip with exit 0"
case "$out" in *skipping*) ;; *) fail "missing-URL skip log missing" ;; esac
out=$(BUILDHOUND_SERVER_URL="https://staging.example.com" sh "$cli" "$work/p1.json" 2>&1) \
    || fail "missing token must skip with exit 0"
case "$out" in *skipping*) ;; *) fail "missing-token skip log missing" ;; esac
test ! -s "$work/args" || fail "curl must not be invoked when URL/token are missing"

# 6. The token never appears in the script's own output (the Authorization header on the
#    curl argv is the buildhound-metric-established contract; asserted in case 1), including
#    on error paths.
out=$(CURL_STATUS=503 BUILDHOUND_SERVER_URL="https://staging.example.com" BUILDHOUND_TOKEN="super-secret" \
    sh "$cli" "$work/p1.json" 2>&1) || fail "5xx must be soft (exit 0)"
case "$out" in *super-secret*) fail "token leaked into script output (5xx path)" ;; esac
case "$out" in *"HTTP 503"*) ;; *) fail "5xx warning missing" ;; esac
out=$(BUILDHOUND_SERVER_URL="https://staging.example.com" BUILDHOUND_TOKEN="super-secret" \
    sh "$cli" "$work/p1.json" 2>&1) || fail "success run exited non-zero"
case "$out" in *super-secret*) fail "token leaked into script output (success path)" ;; esac

# 7. A missing payload file is soft (artifacts are best-effort upstream), but no file
#    arguments at all is a hard usage error (a broken invocation is a caller bug).
out=$(BUILDHOUND_SERVER_URL="https://staging.example.com" BUILDHOUND_TOKEN="t" \
    sh "$cli" "$work/absent.json" 2>&1) || fail "missing payload file must be soft (exit 0)"
case "$out" in *"does not exist"*) ;; *) fail "missing-file warning missing" ;; esac
if BUILDHOUND_SERVER_URL="https://staging.example.com" BUILDHOUND_TOKEN="t" sh "$cli" 2>/dev/null; then
    fail "no file arguments must be a hard error"
fi

echo "publish-cli-test OK"
