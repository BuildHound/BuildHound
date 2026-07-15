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

# Repository digest exactly as review_provider_id derives it.
if command -v sha256sum >/dev/null 2>&1; then
  digest=$(printf '%s' 'buildhound/buildhound' | sha256sum | cut -c1-24)
else
  digest=$(printf '%s' 'buildhound/buildhound' | shasum -a 256 | cut -c1-24)
fi
foreign_digest=$(printf '%s' 'other/repo' | { sha256sum 2>/dev/null || shasum -a 256; } | cut -c1-24)
export FIXTURE_DIGEST="$digest" FIXTURE_FOREIGN_DIGEST="$foreign_digest"

cat > "$bin/docker" <<'EOF'
#!/bin/sh
set -eu
case "$1 $2" in
  'network ls')
    if [ "${FAKE_DOCKER_LS_FAIL:-}" = 1 ]; then
      echo 'Cannot connect to the Docker daemon' >&2
      exit 1
    fi
    # attached review network (mr42, compose deleted), orphaned retired
    # review network (mr43), unattached network of an ACTIVE review (mr45),
    # a foreign repository's review network, and unrelated networks.
    printf 'bh-%s-mr42-Ab12Cd\n' "$FIXTURE_DIGEST"
    printf 'bh-%s-mr43-Cd34Ef\n' "$FIXTURE_DIGEST"
    printf 'bh-%s-mr45-Qq12Rs\n' "$FIXTURE_DIGEST"
    printf 'bh-%s-mr9-Zz99Yy\n' "$FIXTURE_FOREIGN_DIGEST"
    printf 'dokploy-network\n'
    printf 'buildhound-staging_private\n'
    ;;
  'network rm')
    printf '%s\n' "$3" >> "$RM_LOG"
    # mr42 is still attached: docker refuses
    if [ "$3" = "bh-$FIXTURE_DIGEST-mr42-Ab12Cd" ]; then
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
# GNU-date probe and stamp parsing
case "$3" in
  '1970-01-01T00:00:00Z') printf '0\n' ;;
  old) printf '1000000000\n' ;;
  fresh) printf '1999999999\n' ;;
  *) exit 1 ;;
esac
EOF
chmod +x "$bin/docker" "$bin/curl" "$bin/date"

# Environment fixture:
#   mr43 ours, retired, retiredAt old        -> eligible/deleted
#   mr44 ours, retired, retiredAt fresh      -> kept
#   mr45 ours, active                        -> ignored; its network protected
#   mr46 foreign repository, retired + old   -> never touched
#   buildhound-staging: non-review name      -> ignored
environment_json=$("$real_jq" -cn '
  {compose: [
    {name:"mr43", composeId:"c-old", createdAt:"old",
     description:({repository:"BuildHound/BuildHound",pr:43,sha:"a",retired:true,activatedAt:"old",retiredAt:"old"}|tojson)},
    {name:"mr44", composeId:"c-fresh", createdAt:"old",
     description:({repository:"BuildHound/BuildHound",pr:44,sha:"a",retired:true,activatedAt:"old",retiredAt:"fresh"}|tojson)},
    {name:"mr45", composeId:"c-active", createdAt:"old",
     description:({repository:"BuildHound/BuildHound",pr:45,sha:"a",retired:false,activatedAt:"old"}|tojson)},
    {name:"mr46", composeId:"c-foreign", createdAt:"old",
     description:({repository:"Other/Repo",pr:46,sha:"a",retired:true,activatedAt:"old",retiredAt:"old"}|tojson)},
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
    BUILDHOUND_REPOSITORY=BuildHound/BuildHound \
    RETENTION_DAYS=14 \
    ENVIRONMENT_JSON="$environment_json" \
    sh deploy/dokploy/host-gc.sh "$@"
}

fail_test() {
  printf 'host-gc-test: %s\n' "$1" >&2
  exit 1
}

# Dry run: pure report, no mutations; active and foreign networks excluded.
report=$(run_gc)
[ ! -s "$rm_log" ] || fail_test 'dry run removed a network'
[ ! -s "$delete_log" ] || fail_test 'dry run deleted a compose'
printf '%s\n' "$report" | grep -F "networks remaining: bh-$digest-mr42-Ab12Cd bh-$digest-mr43-Cd34Ef" >/dev/null || \
  fail_test "dry run did not report both candidate networks: $report"
printf '%s\n' "$report" | grep -F "networks skipped (active review): bh-$digest-mr45-Qq12Rs" >/dev/null || \
  fail_test "dry run did not protect the active review's network: $report"
printf '%s\n' "$report" | grep -F 'anchors eligible: mr43' >/dev/null || \
  fail_test "dry run did not report the eligible anchor: $report"
printf '%s\n' "$report" | grep -F 'anchors kept (within retention): mr44' >/dev/null || \
  fail_test "dry run did not keep the fresh anchor: $report"
case "$report" in
  *mr45\ *|*"mr45"?*) : ;;
esac
case "$report" in
  *mr46*) fail_test 'dry run considered a foreign repository anchor' ;;
  *"$foreign_digest"*) fail_test 'dry run touched a foreign repository network' ;;
  *c-old*|*c-fresh*) fail_test 'report leaked a Dokploy compose id' ;;
esac

# Apply: removes only this repository's orphaned, non-active networks;
# deletes only this repository's retired anchors past retention.
report=$(run_gc --apply)
if grep -Fx dokploy-network "$rm_log" >/dev/null || grep -Fx buildhound-staging_private "$rm_log" >/dev/null; then
  fail_test 'apply touched a non-review network'
fi
if grep -F "$foreign_digest" "$rm_log" >/dev/null; then
  fail_test 'apply touched a foreign repository network'
fi
if grep -F "mr45" "$rm_log" >/dev/null; then
  fail_test 'apply removed an active review network'
fi
grep -Fx "bh-$digest-mr43-Cd34Ef" "$rm_log" >/dev/null || \
  fail_test 'apply did not remove the orphaned review network'
printf '%s\n' "$report" | grep -F "networks removed: bh-$digest-mr43-Cd34Ef" >/dev/null || \
  fail_test "apply report missing removed network: $report"
printf '%s\n' "$report" | grep -F "networks remaining: bh-$digest-mr42-Ab12Cd" >/dev/null || \
  fail_test "apply report missing in-use network: $report"
deleted=$(cat "$delete_log")
[ "$deleted" = c-old ] || fail_test "apply deleted the wrong composes: $deleted"
printf '%s\n' "$report" | grep -F 'anchors deleted: mr43' >/dev/null || \
  fail_test "apply report missing deleted anchor: $report"

# The token must never appear on curl argv (header file only).
if grep -F 'host-gc-secret-token' "$curl_argv_log" >/dev/null; then
  fail_test 'Dokploy token leaked onto curl argv'
fi

# A docker listing failure must fail the run, never read as "no orphans".
if FAKE_DOCKER_LS_FAIL=1 run_gc >/dev/null 2>&1; then
  fail_test 'docker listing failure produced a clean report'
fi

# Missing or malformed configuration fails closed.
if PATH="$bin:$PATH" DOKPLOY_URL='' DOKPLOY_TOKEN=x ENVIRONMENT_ID=y BUILDHOUND_REPOSITORY=a/b sh deploy/dokploy/host-gc.sh >/dev/null 2>&1; then
  fail_test 'missing DOKPLOY_URL was accepted'
fi
if PATH="$bin:$PATH" DOKPLOY_URL=http://127.0.0.1:3000 DOKPLOY_TOKEN=x ENVIRONMENT_ID=y BUILDHOUND_REPOSITORY=a/b RETENTION_DAYS=99999 ENVIRONMENT_JSON="$environment_json" sh deploy/dokploy/host-gc.sh >/dev/null 2>&1; then
  fail_test 'out-of-bounds RETENTION_DAYS was accepted'
fi
if run_gc --bogus >/dev/null 2>&1; then
  fail_test 'unknown flag was accepted'
fi

printf 'host GC validated\n'
