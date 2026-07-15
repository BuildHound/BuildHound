#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/../../.." && pwd)
TEST_ROOT=$(mktemp -d)
trap 'rm -rf -- "$TEST_ROOT"' EXIT

RELEASE_ID="sha256:$(printf '7%.0s' {1..64})"

awk '
  /^      - name: Select and verify fresh encrypted backup$/ { found = 1; next }
  found && /^        run: \|$/ { in_run = 1; next }
  in_run && $0 !~ /^          / && $0 !~ /^$/ { exit }
  in_run { sub(/^          /, ""); print }
' "$ROOT/.github/workflows/deploy-release.yml" >"$TEST_ROOT/backup-step.sh"

grep -q 'select-backup.sh' "$TEST_ROOT/backup-step.sh" || {
  printf 'failed to extract the backup selection step from deploy-release.yml\n' >&2
  exit 1
}

mkdir -p "$TEST_ROOT/repo/deploy/dokploy"
cat >"$TEST_ROOT/repo/deploy/dokploy/dokploy.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_CALLS"
case "$1" in
  staging-bootstrap-state)
    case "$FAKE_ANCHOR_STATE" in
      established) printf 'established\n' ;;
      bootstrap) printf 'bootstrap\n' ;;
      *) printf 'no current successful deployment found\n' >&2; exit 1 ;;
    esac
    ;;
  require-manual-current)
    [ "$FAKE_ANCHOR_STATE" != missing ] || {
      printf 'no current successful deployment found\n' >&2
      exit 1
    }
    ;;
  current-release-id)
    printf '%s\n' "$FAKE_RELEASE_ID"
    ;;
  *)
    printf 'unexpected dokploy.sh command: %s\n' "$*" >&2
    exit 1
    ;;
esac
EOF
cat >"$TEST_ROOT/repo/deploy/dokploy/select-backup.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_CALLS"
mode=$1
expected=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --expected-release-id) expected=$2; shift 2 ;;
    *) shift ;;
  esac
done
printf '{"expectedReleaseId":"%s","object":"backups/buildhound-20260715T000000Z.dump.age","versionId":"v1"}\n' "$expected"
EOF
chmod +x "$TEST_ROOT/repo/deploy/dokploy/dokploy.sh" "$TEST_ROOT/repo/deploy/dokploy/select-backup.sh"

run_step() {
  local output=$1
  shift
  : > "$TEST_ROOT/calls.log"
  (
    cd "$TEST_ROOT/repo"
    env "$@" \
      FAKE_CALLS="$TEST_ROOT/calls.log" \
      FAKE_RELEASE_ID="$RELEASE_ID" \
      GITHUB_OUTPUT="$output" \
      COMPOSE_ID=c1 \
      bash "$TEST_ROOT/backup-step.sh"
  )
}

# 1. Automatic staging deploy with an established release history: no
#    bootstrap, backup selected against the current release id.
: > "$TEST_ROOT/out-established"
run_step "$TEST_ROOT/out-established" \
  BOOTSTRAP=false TARGET=staging MODE=automatic BACKUP_OBJECT= \
  FAKE_ANCHOR_STATE=established
grep -Fx 'bootstrap=false' "$TEST_ROOT/out-established" >/dev/null
grep -Fx -- "--latest --expected-release-id $RELEASE_ID" "$TEST_ROOT/calls.log" >/dev/null
if grep -q 'require-manual-current' "$TEST_ROOT/calls.log"; then
  printf 'established staging deploy consulted the manual anchor\n' >&2
  exit 1
fi

# 2. First automatic staging deploy (empty release history, manual anchor
#    present): bootstrap engages and the backup is selected against "manual".
: > "$TEST_ROOT/out-bootstrap"
run_step "$TEST_ROOT/out-bootstrap" \
  BOOTSTRAP=false TARGET=staging MODE=automatic BACKUP_OBJECT= \
  FAKE_ANCHOR_STATE=bootstrap
grep -Fx 'bootstrap=true' "$TEST_ROOT/out-bootstrap" >/dev/null
grep -Fx 'require-manual-current --compose-id c1' "$TEST_ROOT/calls.log" >/dev/null
grep -Fx -- '--latest --expected-release-id manual' "$TEST_ROOT/calls.log" >/dev/null

# 3. Empty history without a manual anchor: the step fails closed before any
#    backup selection.
if run_step "$TEST_ROOT/out-missing" \
  BOOTSTRAP=false TARGET=staging MODE=automatic BACKUP_OBJECT= \
  FAKE_ANCHOR_STATE=missing 2>/dev/null; then
  printf 'anchorless staging compose did not fail closed\n' >&2
  exit 1
fi
if grep -q 'select-backup' "$TEST_ROOT/calls.log"; then
  printf 'anchorless staging deploy still selected a backup\n' >&2
  exit 1
fi

# 4. Explicit dispatch bootstrap keeps its manual-anchor and operator
#    backup-object semantics; the automatic detector must not run.
: > "$TEST_ROOT/out-dispatch"
run_step "$TEST_ROOT/out-dispatch" \
  BOOTSTRAP=true TARGET=staging MODE=bootstrap BACKUP_OBJECT=backups/x.dump.age \
  FAKE_ANCHOR_STATE=bootstrap
grep -Fx 'bootstrap=true' "$TEST_ROOT/out-dispatch" >/dev/null
grep -Fx -- '--object backups/x.dump.age --expected-release-id manual' "$TEST_ROOT/calls.log" >/dev/null
if grep -q 'staging-bootstrap-state' "$TEST_ROOT/calls.log"; then
  printf 'dispatch bootstrap ran the automatic bootstrap detector\n' >&2
  exit 1
fi

# 5. Production dispatch never consults the staging bootstrap detector and
#    keeps requiring the operator-supplied backup object.
: > "$TEST_ROOT/out-production"
run_step "$TEST_ROOT/out-production" \
  BOOTSTRAP=false TARGET=production MODE=manual BACKUP_OBJECT=backups/y.dump.age \
  FAKE_ANCHOR_STATE=established
grep -Fx 'bootstrap=false' "$TEST_ROOT/out-production" >/dev/null
grep -Fx -- "--object backups/y.dump.age --expected-release-id $RELEASE_ID" "$TEST_ROOT/calls.log" >/dev/null
if grep -q 'staging-bootstrap-state' "$TEST_ROOT/calls.log"; then
  printf 'production dispatch ran the staging bootstrap detector\n' >&2
  exit 1
fi

printf 'deploy release backup step bootstraps only anchored first staging deploys\n'
