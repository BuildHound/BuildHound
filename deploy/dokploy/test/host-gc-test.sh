#!/bin/sh
set -eu

root=$(mktemp -d)
bin="$root/bin"
mkdir -p "$bin"
trap 'rm -rf "$root"' EXIT
rm_log="$root/network-rm"
delete_log="$root/compose-delete"
curl_argv_log="$root/curl-argv"
real_jq=$(command -v jq)
export RM_LOG="$rm_log" DELETE_LOG="$delete_log" CURL_ARGV_LOG="$curl_argv_log" REAL_JQ="$real_jq"

cat > "$bin/docker" <<'EOF'
#!/bin/sh
set -eu
case "$1 $2" in
  'network ls')
    # one attached review network, one orphaned, one foreign, one staging-ish
    printf 'bh-2e4f87f1c5b0890580d952d3-mr42-Ab12Cd\n'
    printf 'bh-2e4f87f1c5b0890580d952d3-mr43-Cd34Ef\n'
    printf 'dokploy-network\n'
    printf 'buildhound-staging_private\n'
    ;;
  'network rm')
    printf '%s\n' "$3" >> "$RM_LOG"
    # mr42 is still attached: docker refuses
    if [ "$3" = bh-2e4f87f1c5b0890580d952d3-mr42-Ab12Cd ]; then
      exit 1
    fi
    ;;
  *)
    exit 2
    ;;
esac
EOF

cat > "$bin/curl" <<'EOF'
#!/bin/sh
set -eu
printf '%s\n' "$*" >> "$CURL_ARGV_LOG"
url=
data=
for argument do
  case "$argument" in
    http*://*) url=$argument ;;
  esac
done
previous=
for argument do
  if [ "$previous" = --data ]; then data=$argument; fi
  previous=$argument
done
case "$url" in
  *environment.one*)
    printf '%s\n' "$ENVIRONMENT_JSON"
    ;;
  *compose.delete*)
    printf '%s\n' "$data" | "$REAL_JQ" -er .composeId >> "$DELETE_LOG"
    printf '{}\n'
    ;;
  *)
    exit 22
    ;;
esac
EOF

cat > "$bin/date" <<'EOF'
#!/bin/sh
set -eu
if [ "$2" = '+%s' ]; then
  printf '2000000000\n'
  exit 0
fi
case "$3" in
  old) printf '1000000000\n' ;;
  fresh) printf '1999999999\n' ;;
  *) exit 1 ;;
esac
EOF
chmod +x "$bin/docker" "$bin/curl" "$bin/date"

# Environment fixture: retired+old (eligible), retired+fresh (kept),
# active (ignored), retired+old but non-review name (ignored).
environment_json=$("$real_jq" -cn '
  {compose: [
    {name:"mr43", composeId:"c-old", createdAt:"old",
     description:({repository:"BuildHound/BuildHound",pr:43,sha:"a",retired:true,activatedAt:"old"}|tojson)},
    {name:"mr44", composeId:"c-fresh", createdAt:"fresh",
     description:({repository:"BuildHound/BuildHound",pr:44,sha:"a",retired:true,activatedAt:"fresh"}|tojson)},
    {name:"mr45", composeId:"c-active", createdAt:"old",
     description:({repository:"BuildHound/BuildHound",pr:45,sha:"a",retired:false,activatedAt:"old"}|tojson)},
    {name:"buildhound-staging", composeId:"c-staging", createdAt:"old",
     description:({retired:true,activatedAt:"old"}|tojson)}
  ]}
')

run_gc() {
  : > "$rm_log"
  : > "$delete_log"
  : > "$curl_argv_log"
  PATH="$bin:$PATH" \
    DOKPLOY_URL=http://127.0.0.1:3000 \
    DOKPLOY_TOKEN=host-gc-secret-token \
    ENVIRONMENT_ID=review-env \
    RETENTION_DAYS=14 \
    ENVIRONMENT_JSON="$environment_json" \
    sh deploy/dokploy/host-gc.sh "$@"
}

fail_test() {
  printf 'host-gc-test: %s\n' "$1" >&2
  exit 1
}

# Dry run: pure report, no mutations, orphans and eligible anchors listed.
report=$(run_gc)
[ ! -s "$rm_log" ] || fail_test 'dry run removed a network'
[ ! -s "$delete_log" ] || fail_test 'dry run deleted a compose'
printf '%s\n' "$report" | grep -F 'networks remaining: bh-2e4f87f1c5b0890580d952d3-mr42-Ab12Cd bh-2e4f87f1c5b0890580d952d3-mr43-Cd34Ef' >/dev/null || \
  fail_test "dry run did not report both candidate networks: $report"
printf '%s\n' "$report" | grep -F 'anchors eligible: mr43' >/dev/null || \
  fail_test "dry run did not report the eligible anchor: $report"
printf '%s\n' "$report" | grep -F 'anchors kept (within retention): mr44' >/dev/null || \
  fail_test "dry run did not keep the fresh anchor: $report"
case "$report" in
  *mr45*) fail_test 'dry run considered an active review an anchor' ;;
  *c-old*|*c-fresh*) fail_test 'report leaked a Dokploy compose id' ;;
esac

# Apply: removes only matching orphaned networks (in-use ones are retried
# later), deletes only retired anchors past retention.
report=$(run_gc --apply)
if grep -Fx dokploy-network "$rm_log" >/dev/null || grep -Fx buildhound-staging_private "$rm_log" >/dev/null; then
  fail_test 'apply touched a non-review network'
fi
grep -Fx bh-2e4f87f1c5b0890580d952d3-mr43-Cd34Ef "$rm_log" >/dev/null || \
  fail_test 'apply did not remove the orphaned review network'
printf '%s\n' "$report" | grep -F 'networks removed: bh-2e4f87f1c5b0890580d952d3-mr43-Cd34Ef' >/dev/null || \
  fail_test "apply report missing removed network: $report"
printf '%s\n' "$report" | grep -F 'networks remaining: bh-2e4f87f1c5b0890580d952d3-mr42-Ab12Cd' >/dev/null || \
  fail_test "apply report missing in-use network: $report"
deleted=$(cat "$delete_log")
[ "$deleted" = c-old ] || fail_test "apply deleted the wrong composes: $deleted"
printf '%s\n' "$report" | grep -F 'anchors deleted: mr43' >/dev/null || \
  fail_test "apply report missing deleted anchor: $report"

# The token must never appear on curl argv (header file only).
if grep -F 'host-gc-secret-token' "$curl_argv_log" >/dev/null; then
  fail_test 'Dokploy token leaked onto curl argv'
fi

# Missing configuration fails closed.
if PATH="$bin:$PATH" DOKPLOY_URL='' DOKPLOY_TOKEN=x ENVIRONMENT_ID=y sh deploy/dokploy/host-gc.sh >/dev/null 2>&1; then
  fail_test 'missing DOKPLOY_URL was accepted'
fi
if run_gc --bogus >/dev/null 2>&1; then
  fail_test 'unknown flag was accepted'
fi

printf 'host GC validated\n'
