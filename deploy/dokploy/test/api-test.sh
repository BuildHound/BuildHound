#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(unset CDPATH && cd -- "$(dirname -- "$0")" && pwd)
# The path is resolved dynamically so the test works from every CWD.
# shellcheck disable=SC1091
. "$SCRIPT_DIR/../lib/api.sh"

root=$(mktemp -d)
export RUNNER_TEMP=$root
bin="$root/bin"
mkdir -p "$bin"
trap 'rm -rf -- "$root"' EXIT HUP INT TERM

export FAKE_CURL_ARGS="$root/curl-args"
export FAKE_CURL_BODY="$root/curl-body"
export FAKE_CURL_META="$root/curl-meta"
export FAKE_CURL_STATUS=200
export FAKE_CURL_EXIT=0
export FAKE_CURL_RESPONSE='{}'
export EXPECTED_TOKEN='review-token-value'

cat > "$bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$@" > "$FAKE_CURL_ARGS"
: > "$FAKE_CURL_BODY"
: > "$FAKE_CURL_META"

method=''
header_file=''
request_file=''
output_file=''
url=''
read_body=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --disable|--silent)
      shift
      ;;
    --proto|--connect-timeout|--max-time|--max-redirs|--request|--header|--data-binary|--output|--write-out)
      option=$1
      value=${2-}
      [ -n "$value" ] || exit 90
      case "$option" in
        --request) method=$value ;;
        --header) header_file=${value#@} ;;
        --data-binary)
          case "$value" in
            @*) request_file=${value#@}; [ -f "$request_file" ] || exit 91; read_body=true ;;
            *) exit 91 ;;
          esac
          ;;
        --output) output_file=$value ;;
      esac
      shift 2
      ;;
    https://*)
      url=$1
      shift
      ;;
    *)
      exit 92
      ;;
  esac
done

[ -n "$method" ] && [ -n "$header_file" ] && [ -n "$output_file" ] && [ -n "$url" ]
grep -Fqx "x-api-key: $EXPECTED_TOKEN" "$header_file"
grep -Fqx 'Accept: application/json' "$header_file"
grep -Fqx 'Content-Type: application/json' "$header_file"
if [ "$read_body" = true ]; then
  cat -- "$request_file" > "$FAKE_CURL_BODY"
fi
printf 'method=%s\nurl=%s\nheader=file\n' "$method" "$url" > "$FAKE_CURL_META"
printf '%s' "$FAKE_CURL_RESPONSE" > "$output_file"
printf '%s' "$FAKE_CURL_STATUS"
if [ "$FAKE_CURL_EXIT" -ne 0 ]; then
  printf 'fake-curl-sensitive-error-%s\n' "$EXPECTED_TOKEN" >&2
fi
exit "$FAKE_CURL_EXIT"
EOF
chmod +x "$bin/curl"
PATH="$bin:$PATH"
export PATH

DOKPLOY_URL='https://dokploy.example.test/'
DOKPLOY_TOKEN=$EXPECTED_TOKEN
export DOKPLOY_URL DOKPLOY_TOKEN

fail() {
  printf 'api-test: %s\n' "$1" >&2
  exit 1
}

assert_equal() {
  if [ "$1" != "$2" ]; then
    printf 'api-test: expected <%s>, got <%s>\n' "$1" "$2" >&2
    exit 1
  fi
}

assert_file_contains() {
  grep -Fqx -- "$2" "$1" || fail "$1 does not contain expected line: $2"
}

reset_fake() {
  FAKE_CURL_STATUS=$1
  FAKE_CURL_EXIT=$2
  FAKE_CURL_RESPONSE=$3
  export FAKE_CURL_STATUS FAKE_CURL_EXIT FAKE_CURL_RESPONSE
  : > "$FAKE_CURL_ARGS"
  : > "$FAKE_CURL_BODY"
  : > "$FAKE_CURL_META"
}

run_failure() {
  local expected=$1
  shift
  : > "$root/stdout"
  : > "$root/stderr"
  set +e
  "$@" > "$root/stdout" 2> "$root/stderr"
  local actual=$?
  set -e
  assert_equal "$expected" "$actual"
  [ ! -s "$root/stdout" ] || fail 'failed request exposed a response body'
}

# GET: normalized origin, required curl controls, JSON headers, and no token argv.
reset_fake 200 0 '{"composeId":"c1"}'
out=$(api_get 'compose.one?composeId=c1' 2> "$root/stderr")
assert_equal '{"composeId":"c1"}' "$out"
[ ! -s "$root/stderr" ] || fail 'successful GET wrote diagnostics'
assert_file_contains "$FAKE_CURL_META" 'method=GET'
assert_file_contains "$FAKE_CURL_META" 'url=https://dokploy.example.test/api/compose.one?composeId=c1'
assert_file_contains "$FAKE_CURL_ARGS" '--connect-timeout'
assert_file_contains "$FAKE_CURL_ARGS" '10'
assert_file_contains "$FAKE_CURL_ARGS" '--max-time'
assert_file_contains "$FAKE_CURL_ARGS" '30'
assert_file_contains "$FAKE_CURL_ARGS" '--max-redirs'
assert_file_contains "$FAKE_CURL_ARGS" '0'
assert_file_contains "$FAKE_CURL_ARGS" '--disable'
if grep -Fqx -- '--location' "$FAKE_CURL_ARGS"; then
  fail 'curl was configured to follow redirects'
fi
if grep -Fq -- "$DOKPLOY_TOKEN" "$FAKE_CURL_ARGS"; then
  fail 'API token appeared directly in curl argv'
fi

# POST: the canonical JSON body is read from a private file and absent from curl argv.
payload='{"composeId":"c1","secret":"request-body-secret"}'
reset_fake 201 0 '{"updated":true}'
out=$(api_post 'compose.update' "$payload" 2> "$root/stderr")
assert_equal '{"updated":true}' "$out"
assert_equal '{"composeId":"c1","secret":"request-body-secret"}' "$(cat "$FAKE_CURL_BODY")"
assert_file_contains "$FAKE_CURL_META" 'method=POST'
if grep -Fq -- 'request-body-secret' "$FAKE_CURL_ARGS"; then
  fail 'POST body appeared directly in curl argv'
fi

# A 2xx response still fails closed when it is not JSON, without exposing it.
reset_fake 200 0 'successful-response-secret-not-json'
run_failure 65 api_get 'compose.one?composeId=c1'
assert_file_contains "$root/stderr" 'dokploy_api method=GET path=compose.one status=invalid_json exit=65'
if grep -Fq -- 'successful-response-secret-not-json' "$root/stderr"; then
  fail 'invalid JSON response appeared in diagnostics'
fi
for invalid_success in '' '{}{}'; do
  reset_fake 200 0 "$invalid_success"
  run_failure 65 api_get 'compose.one?composeId=c1'
  assert_file_contains "$root/stderr" 'dokploy_api method=GET path=compose.one status=invalid_json exit=65'
done

# Empty or multi-value JSON requests fail before curl runs.
for invalid_request in '' '{}{}'; do
  reset_fake 200 0 '{}'
  : > "$FAKE_CURL_ARGS"
  run_failure 65 api_post 'compose.update' "$invalid_request"
  assert_file_contains "$root/stderr" 'dokploy_api method=POST path=compose.update status=invalid_json exit=65'
  [ ! -s "$FAKE_CURL_ARGS" ] || fail 'curl ran for an invalid JSON request'
done

# Redirects and HTTP failures are rejected without exposing response content.
reset_fake 302 0 'redirect-response-secret'
run_failure 22 api_get 'compose.one?composeId=c1'
assert_file_contains "$root/stderr" 'dokploy_api method=GET path=compose.one status=302 exit=22'
if grep -Fq -- 'redirect-response-secret' "$root/stderr"; then
  fail 'redirect response body appeared in diagnostics'
fi

reset_fake 403 0 "forbidden-response-secret-$DOKPLOY_TOKEN"
run_failure 22 api_post 'compose.update' "$payload"
assert_file_contains "$root/stderr" 'dokploy_api method=POST path=compose.update status=403 exit=22'
if grep -Fq -- 'forbidden-response-secret' "$root/stderr" || grep -Fq -- "$DOKPLOY_TOKEN" "$root/stderr"; then
  fail 'HTTP error diagnostics exposed response or token material'
fi

# Transport errors preserve curl's exit code and hide any partial body.
reset_fake 000 7 'transport-response-secret'
run_failure 7 api_get 'environment.one?environmentId=e1'
assert_file_contains "$root/stderr" 'dokploy_api method=GET path=environment.one status=000 exit=7'
if grep -Fq -- 'transport-response-secret' "$root/stderr"; then
  fail 'transport diagnostics exposed a partial response body'
fi
if grep -Fq -- 'environmentId=e1' "$root/stderr"; then
  fail 'transport diagnostics exposed an API query string'
fi

# Invalid origins fail before curl. A single trailing slash is the only path accepted.
for invalid_origin in \
  'http://dokploy.example.test' \
  'https://user@dokploy.example.test' \
  'https://dokploy.example.test/path' \
  'https://dokploy.example.test/?query=1' \
  'https://dokploy.example.test/#fragment' \
  'https://dokploy.example.test//' \
  'https://bad..example.test' \
  'https://dokploy.example.test:999999999999999999999999999999' \
  'https://dokploy.example.test:65536'; do
  DOKPLOY_URL=$invalid_origin
  export DOKPLOY_URL
  : > "$FAKE_CURL_ARGS"
  run_failure 2 api_get 'compose.one?composeId=c1'
  assert_file_contains "$root/stderr" 'dokploy_api method=GET path=compose.one status=invalid_origin exit=2'
  [ ! -s "$FAKE_CURL_ARGS" ] || fail 'curl ran for an invalid origin'
done
DOKPLOY_URL='https://dokploy.example.test'
export DOKPLOY_URL

# Empty, CR, and LF-bearing tokens fail without echoing their value.
for invalid_token in '' $'bad\rvalue' $'bad\nvalue'; do
  DOKPLOY_TOKEN=$invalid_token
  export DOKPLOY_TOKEN
  : > "$FAKE_CURL_ARGS"
  run_failure 2 api_get 'compose.one?composeId=c1'
  assert_file_contains "$root/stderr" 'dokploy_api method=GET path=compose.one status=invalid_token exit=2'
  [ ! -s "$FAKE_CURL_ARGS" ] || fail 'curl ran for an invalid token'
done
DOKPLOY_TOKEN=$EXPECTED_TOKEN
export DOKPLOY_TOKEN

# API paths must remain relative, fragment-free, whitespace-free, and normalized.
for invalid_path in \
  '' \
  '/compose.one' \
  'https://attacker.example/api' \
  '../compose.one' \
  'compose/../compose.one' \
  'compose.one#fragment' \
  'compose.one?value=has space' \
  $'compose.one\nsecond-line'; do
  : > "$FAKE_CURL_ARGS"
  run_failure 2 api_get "$invalid_path"
  assert_file_contains "$root/stderr" 'dokploy_api method=GET path=invalid status=invalid_path exit=2'
  [ ! -s "$FAKE_CURL_ARGS" ] || fail 'curl ran for an invalid API path'
done

if find "$root" -maxdepth 1 -type d -name 'buildhound-dokploy-api.*' | grep -q .; then
  fail 'API transport left a private workspace behind'
fi

printf 'Dokploy API transport validated\n'
