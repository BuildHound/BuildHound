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
for argument do url=$argument; done
case "$url" in
  https://site.example.test/) printf 'Track every Gradle build\n' ;;
  https://dashboard.example.test/health) printf 'ok\n' ;;
  https://dashboard.example.test/v1/builds) printf '202' ;;
  https://dashboard.example.test/v1/builds/*)
    build_id=${url##*/}
    printf '{"buildId":"%s"}\n' "$build_id"
    ;;
  *) exit 22 ;;
esac
EOF
chmod +x "$bin/curl"

run_verify() (
  export BUILDHOUND_SITE_URL=$1
  export BUILDHOUND_DASHBOARD_URL=$2
  export BUILDHOUND_READ_TOKEN=test-read
  export BUILDHOUND_INGEST_TOKEN=test-ingest
  export CURL_LOG="$curl_log"
  export PATH="$bin:$PATH"
  bash deploy/dokploy/verify-release.sh
)

: > "$curl_log"
run_verify https://site.example.test https://dashboard.example.test
test "$(wc -l < "$curl_log")" -eq 4

# Skip-site mode (owner decision, plan 088): the site probe is skipped, the
# three dashboard checks stay mandatory, URL validation still applies.
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
