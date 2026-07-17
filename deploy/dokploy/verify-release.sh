#!/bin/bash
set -euo pipefail
: "${BUILDHOUND_SITE_URL:?}" "${BUILDHOUND_DASHBOARD_URL:?}" "${BUILDHOUND_READ_TOKEN:?}" "${BUILDHOUND_INGEST_TOKEN:?}" "${BUILDHOUND_EXPECTED_ROBOTS_HEADER:?}"
python3 - "$BUILDHOUND_SITE_URL" "$BUILDHOUND_DASHBOARD_URL" <<'PY'
import sys
from urllib.parse import urlsplit

for label, value in zip(("site URL", "dashboard URL"), sys.argv[1:]):
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError:
        raise SystemExit(f"{label} must be an exact HTTPS origin") from None
    invalid = (
        parsed.scheme != "https"
        or parsed.hostname is None
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path != ""
        or parsed.query != ""
        or parsed.fragment != ""
        or any(character.isspace() or ord(character) < 32 for character in value)
        or port == 0
    )
    host = parsed.hostname or ""
    canonical_netloc = f"[{host}]" if ":" in host else host
    if port is not None:
        canonical_netloc += f":{port}"
    if invalid or value != f"https://{canonical_netloc}":
        raise SystemExit(f"{label} must be an exact HTTPS origin")
PY
payload=buildhound-commons/src/jvmTest/resources/golden/build-payload-v1-ci-env.json
request_payload=$(mktemp)
trap 'rm -f "$request_payload"' EXIT
new_build_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
jq --arg build_id "$new_build_id" '.buildId = $build_id' "$payload" > "$request_payload"
build_id=$(jq -er .buildId "$request_payload")
# The smoke runs immediately after deployment convergence; the stop-first
# update strategy leaves a short task-rollout window where routes 404. Retry
# the first contact with each service instead of failing on the race.
retry_ok=false
for _ in $(seq 1 20); do
  if body=$(curl -fsS "$BUILDHOUND_DASHBOARD_URL/health" 2>/dev/null) &&
     jq -e '.status == "ok"' >/dev/null 2>&1 <<<"$body"; then
    retry_ok=true
    break
  fi
  sleep 15
done
# The server's health contract is JSON (Routes.kt: HealthResponse(status="ok")).
test "$retry_ok" = true

# The stack manifests default BUILDHOUND_ROBOTS_HEADER to "all", so a reset
# staging Dokploy environment silently becomes indexable (plan 095). The
# expectation is pinned per environment in deploy.yml, independent of the
# Dokploy environment this audits. GET, not HEAD: the health contract above
# only guarantees GET. A missing or duplicated header also mismatches.
robots_header=$(curl -fsS -D - -o /dev/null "$BUILDHOUND_DASHBOARD_URL/health" \
  | tr -d '\r' | awk 'tolower($0) ~ /^x-robots-tag:/ {sub(/^[^:]*:[ \t]*/, ""); print}')
if [ "$robots_header" != "$BUILDHOUND_EXPECTED_ROBOTS_HEADER" ]; then
  printf 'X-Robots-Tag mismatch: expected "%s", observed "%s"\n' \
    "$BUILDHOUND_EXPECTED_ROBOTS_HEADER" "$robots_header" >&2
  exit 1
fi

# Owner decision (plan 088): staging serves no site yet; the dashboard checks
# stay mandatory. Only the exact value "true" skips.
if [ "${BUILDHOUND_SKIP_SITE_CHECKS-}" = true ]; then
  printf '%s\n' 'site check skipped (BUILDHOUND_SKIP_SITE_CHECKS=true)' >&2
else
  curl -fsS "$BUILDHOUND_SITE_URL/" | grep -q 'Track every Gradle build'
fi

ingest_ok=false
for _ in $(seq 1 5); do
  # -f is intentionally absent: the status code is the evidence; only the
  # numeric code is ever logged.
  code=$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $BUILDHOUND_INGEST_TOKEN" -H 'Content-Type: application/json' --data-binary "@$request_payload" "$BUILDHOUND_DASHBOARD_URL/v1/builds" 2>/dev/null) || code='transport-error'
  if [ "$code" = 202 ]; then
    ingest_ok=true
    break
  fi
  printf 'ingest attempt returned %s\n' "$code" >&2
  sleep 10
done
test "$ingest_ok" = true
curl -fsS -H "Authorization: Bearer $BUILDHOUND_READ_TOKEN" "$BUILDHOUND_DASHBOARD_URL/v1/builds/$build_id" | jq -e --arg id "$build_id" '.buildId == $id' >/dev/null
