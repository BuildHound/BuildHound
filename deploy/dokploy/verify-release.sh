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
header_dump=$(mktemp)
site_headers=$(mktemp)
site_body=$(mktemp)
robots_headers=$(mktemp)
robots_body=$(mktemp)
trap 'rm -f "$request_payload" "$header_dump" "$site_headers" "$site_body" "$robots_headers" "$robots_body"' EXIT
new_build_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
jq --arg build_id "$new_build_id" '.buildId = $build_id' "$payload" > "$request_payload"
build_id=$(jq -er .buildId "$request_payload")
# The smoke runs immediately after deployment convergence; the stop-first
# update strategy leaves a short task-rollout window where routes 404. Retry
# the first contact with each service instead of failing on the race.
retry_ok=false
for _ in $(seq 1 20); do
  if body=$(curl -fsS -D "$header_dump" "$BUILDHOUND_DASHBOARD_URL/health" 2>/dev/null) &&
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
# Dokploy environment this audits. The headers were captured (-D) from the
# same request that proved health, inheriting its rollout-race retry; -D
# receives headers only, so the response body can never forge an
# x-robots-tag line into this parse. The value comparison is byte-exact on
# purpose: a missing, duplicated, or re-cased header value all mismatch.
robots_header=$(tr -d '\r' < "$header_dump" \
  | awk 'tolower($0) ~ /^x-robots-tag:/ {sub(/^[^:]*:[ \t]*/, ""); print}')
if [ "$robots_header" != "$BUILDHOUND_EXPECTED_ROBOTS_HEADER" ]; then
  printf 'X-Robots-Tag mismatch: expected "%s", observed "%s"\n' \
    "$BUILDHOUND_EXPECTED_ROBOTS_HEADER" "$robots_header" >&2
  exit 1
fi

# The optional compatibility/emergency skip leaves dashboard checks mandatory.
# Only the exact value "true" skips the site probe.
if [ "${BUILDHOUND_SKIP_SITE_CHECKS-}" = true ]; then
  printf '%s\n' 'site check skipped (BUILDHOUND_SKIP_SITE_CHECKS=true)' >&2
else
  if [ "${BUILDHOUND_EXPECT_NOINDEX-}" = true ]; then
    verify_staging_site() {
      local site_status robots_status
      site_status=$(curl -sS --dump-header "$site_headers" --output "$site_body" \
        --connect-timeout 10 --max-time 20 --write-out '%{http_code}' \
        "$BUILDHOUND_SITE_URL/" 2>/dev/null) || return 1
      test "$site_status" = 200 || return 1
      grep -q 'Track every Gradle build' "$site_body" || return 1
      python3 - "$site_body" "$BUILDHOUND_DASHBOARD_URL" <<'PY' || return 1
import sys
from html.parser import HTMLParser

class DashboardLink(HTMLParser):
    def __init__(self):
        super().__init__()
        self.matches = 0

    def handle_starttag(self, tag, attrs):
        if tag != "a":
            return
        attributes = dict(attrs)
        if "primary" in attributes.get("class", "").split() and attributes.get("href") == sys.argv[2]:
            self.matches += 1

parser = DashboardLink()
with open(sys.argv[1], encoding="utf-8") as page:
    parser.feed(page.read())
if parser.matches != 1:
    raise SystemExit("site dashboard link does not match the configured dashboard origin")
PY
      # Exactly one total X-Robots-Tag field is accepted; a duplicate or a
      # different value can be interpreted differently by intermediaries.
      test "$(tr -d '\r' < "$site_headers" | awk 'BEGIN { count=0; exact=0 } tolower($0) ~ /^x-robots-tag:/ { count++; value=$0; sub(/^[^:]*:[ \t]*/, "", value); if (value == "noindex, nofollow") exact++ } END { print count ":" exact }')" = '1:1' || return 1
      robots_status=$(curl -sS --dump-header "$robots_headers" --output "$robots_body" \
        --connect-timeout 10 --max-time 20 --write-out '%{http_code}' \
        "$BUILDHOUND_SITE_URL/robots.txt" 2>/dev/null) || return 1
      test "$robots_status" = 200 || return 1
      printf 'User-agent: *\nDisallow: /\n' | cmp -s - "$robots_body" || return 1
    }

    # Stop-first rollout can briefly expose an old route or partial pair.
    # Retry the entire tuple so no assertion from one attempt is combined
    # with another, and never reach ingest until one complete tuple passes.
    site_retry_ok=false
    for _ in $(seq 1 20); do
      if verify_staging_site; then
        site_retry_ok=true
        break
      fi
      sleep 15
    done
    test "$site_retry_ok" = true
  else
    # Preserve production's established, intentionally narrow page proof.
    curl -fsS "$BUILDHOUND_SITE_URL/" | grep -q 'Track every Gradle build'
  fi
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
