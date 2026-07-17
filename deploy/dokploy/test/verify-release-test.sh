#!/usr/bin/env bash
set -euo pipefail

root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
bin="$root/bin"
mkdir -p "$bin"
curl_log="$root/curl.log"

cat > "$bin/curl" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$CURL_LOG"
output=
headers=
write_out=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output|-o) output=$2; shift 2 ;;
    --dump-header|-D) headers=$2; shift 2 ;;
    --write-out|-w) write_out=$2; shift 2 ;;
    --header|-H|--data-binary|--request|-X|--connect-timeout|--max-time) shift 2 ;;
    *) url=$1; shift ;;
  esac
done
case "$url" in
  https://site.example.test/) body=${TEST_SITE_BODY-'<a class="primary" href="https://dashboard.example.test">Open dashboard</a> Track every Gradle build'}; code=200; headers_value=${TEST_SITE_HEADERS-'X-Robots-Tag: noindex, nofollow'} ;;
  https://site.example.test/robots.txt)
    count=$(sed -n '1p' "$TEST_ROBOTS_COUNT_FILE"); count=${count:-0}; count=$((count + 1))
    printf '%s\n' "$count" > "$TEST_ROBOTS_COUNT_FILE"
    body='User-agent: *
Disallow: /'; headers_value='X-Robots-Tag: noindex, nofollow'
    if [ "$count" -le "${TEST_ROBOTS_FAIL_FIRST-0}" ]; then code=503; else code=200; fi
    ;;
  https://dashboard.example.test/health) body='{"status":"ok"}'; code=200; headers_value='' ;;
  https://dashboard.example.test/v1/builds) body=''; code=202; headers_value='' ;;
  https://dashboard.example.test/v1/builds/*)
    build_id=${url##*/}
    body=$(printf '{"buildId":"%s"}' "$build_id"); code=200; headers_value=''
    ;;
  *) exit 22 ;;
esac
if [ -n "$headers" ]; then
  {
    printf 'HTTP/2 %s\r\n' "$code"
    if [ "$url" = https://dashboard.example.test/health ] && [ "${MOCK_ROBOTS_OMIT-}" != true ]; then
      printf 'x-robots-tag: %s\r\n' "${MOCK_ROBOTS_VALUE-noindex, nofollow}"
      if [ "${MOCK_ROBOTS_DUPLICATE-}" = true ]; then
        printf 'x-robots-tag: %s\r\n' "${MOCK_ROBOTS_VALUE-noindex, nofollow}"
      fi
    fi
    printf '%s\r\n' "$headers_value"
    printf '\r\n'
  } > "$headers"
fi
if [ -n "$output" ] && [ "$output" != /dev/null ]; then printf '%b\n' "$body" > "$output"; elif [ -z "$output" ]; then printf '%b\n' "$body"; fi
if [ -n "$write_out" ]; then printf '%s' "$code"; fi
EOF
chmod +x "$bin/curl"
cat > "$bin/sleep" <<'EOF'
#!/usr/bin/env sh
exit 0
EOF
chmod +x "$bin/sleep"

run_verify() (
  export BUILDHOUND_SITE_URL=$1
  export BUILDHOUND_DASHBOARD_URL=$2
  export BUILDHOUND_READ_TOKEN=test-read
  export BUILDHOUND_INGEST_TOKEN=test-ingest
  if [ "${EXPECTED_ROBOTS_UNSET-}" != true ]; then
    export BUILDHOUND_EXPECTED_ROBOTS_HEADER="${EXPECTED_ROBOTS-noindex, nofollow}"
  fi
  export BUILDHOUND_EXPECT_NOINDEX=true
  export CURL_LOG="$curl_log"
  export TEST_ROBOTS_COUNT_FILE="$root/robots-count"
  export TEST_ROBOTS_FAIL_FIRST="${TEST_ROBOTS_FAIL_FIRST-0}"
  export PATH="$bin:$PATH"
  : > "$TEST_ROBOTS_COUNT_FILE"
  bash deploy/dokploy/verify-release.sh
)

assert_staging_probe_timeouts() {
  local url=$1
  awk -v url="$url" '
    $NF == url {
      saw = 1
      connect_timeout = 0
      max_time = 0
      for (i = 1; i < NF; i++) {
        if ($i == "--connect-timeout" && $(i + 1) == "10") connect_timeout = 1
        if ($i == "--max-time" && $(i + 1) == "20") max_time = 1
      }
      if (!connect_timeout || !max_time) invalid = 1
    }
    END { exit !saw || invalid }
  ' "$curl_log"
}

: > "$curl_log"
run_verify https://site.example.test https://dashboard.example.test
test "$(wc -l < "$curl_log")" -eq 5
assert_staging_probe_timeouts https://site.example.test/
assert_staging_probe_timeouts https://site.example.test/robots.txt

# A partial tuple is discarded: after robots fails, the retry fetches and
# revalidates the page before trying robots again, then ingest follows.
: > "$curl_log"
TEST_ROBOTS_FAIL_FIRST=1 run_verify https://site.example.test https://dashboard.example.test
test "$(wc -l < "$curl_log")" -eq 7
test "$(awk '$NF == "https://site.example.test/" { count++ } END { print count + 0 }' "$curl_log")" -eq 2
test "$(awk '$NF == "https://site.example.test/robots.txt" { count++ } END { print count + 0 }' "$curl_log")" -eq 2

# The rollout retry remains bounded and never reaches ingest without a full
# site tuple. The fake sleep keeps this exhaustion test fast.
: > "$curl_log"
set +e
TEST_ROBOTS_FAIL_FIRST=20 run_verify https://site.example.test https://dashboard.example.test >/dev/null 2>&1
status=$?
set -e
test "$status" -ne 0
test "$(wc -l < "$curl_log")" -eq 41
if grep -q 'dashboard.example.test/v1/builds' "$curl_log"; then
  printf 'ingest ran without a complete staging site tuple\n' >&2
  exit 1
fi

# Site proof is an ingress gate: a successful-looking page with the wrong
# dashboard origin must stop before ingest/read attestation could run.
: > "$curl_log"
set +e
TEST_SITE_BODY='<a class="primary" href="https://other-dashboard.example.test">Open dashboard</a> Track every Gradle build' run_verify https://site.example.test https://dashboard.example.test >/dev/null 2>&1
status=$?
set -e
test "$status" -ne 0
test "$(wc -l < "$curl_log")" -eq 21

: > "$curl_log"
set +e
TEST_SITE_HEADERS='X-Robots-Tag: noindex, nofollow
X-Robots-Tag: noindex, nofollow' run_verify https://site.example.test https://dashboard.example.test >/dev/null 2>&1
status=$?
set -e
test "$status" -ne 0
test "$(wc -l < "$curl_log")" -eq 21

# Staging's noindex header is exact, not merely present.
: > "$curl_log"
set +e
TEST_SITE_HEADERS='X-Robots-Tag: noindex' run_verify https://site.example.test https://dashboard.example.test >/dev/null 2>&1
status=$?
set -e
test "$status" -ne 0
test "$(wc -l < "$curl_log")" -eq 21

# Robots-header gate (plan 095): the expectation is value-agnostic (production
# pins "all"); a wrong, missing, or duplicated served header fails right after
# the health gate (1 call); an unset expectation fails before any curl.
: > "$curl_log"
EXPECTED_ROBOTS=all MOCK_ROBOTS_VALUE=all run_verify https://site.example.test https://dashboard.example.test
test "$(wc -l < "$curl_log")" -eq 5
: > "$curl_log"
set +e
MOCK_ROBOTS_VALUE=all run_verify https://site.example.test https://dashboard.example.test >/dev/null 2>&1
status=$?
set -e
test "$status" -ne 0
test "$(wc -l < "$curl_log")" -eq 1
: > "$curl_log"
set +e
MOCK_ROBOTS_OMIT=true run_verify https://site.example.test https://dashboard.example.test >/dev/null 2>&1
status=$?
set -e
test "$status" -ne 0
test "$(wc -l < "$curl_log")" -eq 1
: > "$curl_log"
set +e
MOCK_ROBOTS_DUPLICATE=true run_verify https://site.example.test https://dashboard.example.test >/dev/null 2>&1
status=$?
set -e
test "$status" -ne 0
test "$(wc -l < "$curl_log")" -eq 1
: > "$curl_log"
set +e
EXPECTED_ROBOTS_UNSET=true run_verify https://site.example.test https://dashboard.example.test >/dev/null 2>&1
status=$?
set -e
test "$status" -ne 0
test ! -s "$curl_log"

# Compatibility/emergency skip mode: the site probe is skipped, the three
# dashboard checks stay mandatory, and URL validation still applies.
: > "$curl_log"
BUILDHOUND_SKIP_SITE_CHECKS=true run_verify https://site.example.test https://dashboard.example.test
test "$(wc -l < "$curl_log")" -eq 3
if grep -q 'site.example.test' "$curl_log"; then
  printf 'skip-site smoke still probed the site\n' >&2
  exit 1
fi
: > "$curl_log"
set +e
BUILDHOUND_SKIP_SITE_CHECKS=true run_verify 'https://site.example.test/path' https://dashboard.example.test >/dev/null 2>&1
status=$?
set -e
test "$status" -ne 0
test ! -s "$curl_log"

for invalid_site in \
  http://site.example.test \
  https://user@site.example.test \
  https://site.example.test/ \
  https://site.example.test/path \
  'https://site.example.test?query' \
  'https://site.example.test#fragment'
do
  : > "$curl_log"
  set +e
  run_verify "$invalid_site" https://dashboard.example.test >/dev/null 2>&1
  status=$?
  set -e
  test "$status" -ne 0
  test ! -s "$curl_log"
done

: > "$curl_log"
set +e
run_verify https://site.example.test https://dashboard.example.test/private >/dev/null 2>&1
status=$?
set -e
test "$status" -ne 0
test ! -s "$curl_log"

printf 'release URL validation verified\n'
