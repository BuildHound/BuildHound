#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/../../.." && pwd)
TEST_ROOT=$(mktemp -d)
trap 'rm -rf -- "$TEST_ROOT"' EXIT

RELEASE_ID="sha256:$(printf '7%.0s' {1..64})"

# deploy.yml carries one backup-selection step per deploy job; extract each
# verbatim (first = staging, second = production).
awk -v out="$TEST_ROOT" '
  /^      - name: Select and verify fresh encrypted backup$/ { count += 1; found = 1; next }
  found && /^        run: \|$/ { in_run = 1; next }
  in_run && $0 !~ /^          / && $0 !~ /^$/ { in_run = 0; found = 0 }
  in_run { sub(/^          /, ""); print > (out "/backup-step-" count ".sh") }
' "$ROOT/.github/workflows/deploy.yml"

test -s "$TEST_ROOT/backup-step-1.sh"
test -s "$TEST_ROOT/backup-step-2.sh"
grep -q 'staging-bootstrap-state' "$TEST_ROOT/backup-step-1.sh"
if grep -q 'staging-bootstrap-state' "$TEST_ROOT/backup-step-2.sh"; then
  printf 'production backup step must not run the staging bootstrap detector\n' >&2
  exit 1
fi

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
  local script=$1 output=$2
  shift 2
  : > "$TEST_ROOT/calls.log"
  (
    cd "$TEST_ROOT/repo"
    env "$@" \
      FAKE_CALLS="$TEST_ROOT/calls.log" \
      FAKE_RELEASE_ID="$RELEASE_ID" \
      GITHUB_OUTPUT="$output" \
      COMPOSE_ID=c1 \
      bash "$TEST_ROOT/$script"
  )
}

# --- staging job -------------------------------------------------------------

# 1. Automatic push with established history: no bootstrap, latest backup
#    against the current release id.
: > "$TEST_ROOT/out-established"
run_step backup-step-1.sh "$TEST_ROOT/out-established" \
  BOOTSTRAP=false MODE=automatic DISPATCH_TARGET= BACKUP_OBJECT= \
  FAKE_ANCHOR_STATE=established
grep -Fx 'bootstrap=false' "$TEST_ROOT/out-established" >/dev/null
grep -Fx -- "--latest --expected-release-id $RELEASE_ID" "$TEST_ROOT/calls.log" >/dev/null
if grep -q 'require-manual-current' "$TEST_ROOT/calls.log"; then
  printf 'established staging deploy consulted the manual anchor\n' >&2
  exit 1
fi

# 2. First automatic staging deploy: bootstrap engages against the anchor.
: > "$TEST_ROOT/out-bootstrap"
run_step backup-step-1.sh "$TEST_ROOT/out-bootstrap" \
  BOOTSTRAP=false MODE=automatic DISPATCH_TARGET= BACKUP_OBJECT= \
  FAKE_ANCHOR_STATE=bootstrap
grep -Fx 'bootstrap=true' "$TEST_ROOT/out-bootstrap" >/dev/null
grep -Fx 'require-manual-current --compose-id c1' "$TEST_ROOT/calls.log" >/dev/null
grep -Fx -- '--latest --expected-release-id manual' "$TEST_ROOT/calls.log" >/dev/null

# 3. Anchorless compose fails closed before any backup selection.
if run_step backup-step-1.sh "$TEST_ROOT/out-missing" \
  BOOTSTRAP=false MODE=automatic DISPATCH_TARGET= BACKUP_OBJECT= \
  FAKE_ANCHOR_STATE=missing 2>/dev/null; then
  printf 'anchorless staging compose did not fail closed\n' >&2
  exit 1
fi
if grep -q 'select-backup' "$TEST_ROOT/calls.log"; then
  printf 'anchorless staging deploy still selected a backup\n' >&2
  exit 1
fi

# 4. Dispatch staging (redeploy/rollback or bootstrap): operator-chosen
#    backup object; the automatic detector must not run.
: > "$TEST_ROOT/out-dispatch"
run_step backup-step-1.sh "$TEST_ROOT/out-dispatch" \
  BOOTSTRAP=true MODE=dispatch DISPATCH_TARGET=staging BACKUP_OBJECT=backups/x.dump.age \
  FAKE_ANCHOR_STATE=bootstrap
grep -Fx 'bootstrap=true' "$TEST_ROOT/out-dispatch" >/dev/null
grep -Fx -- '--object backups/x.dump.age --expected-release-id manual' "$TEST_ROOT/calls.log" >/dev/null
if grep -q 'staging-bootstrap-state' "$TEST_ROOT/calls.log"; then
  printf 'dispatch staging deploy ran the automatic bootstrap detector\n' >&2
  exit 1
fi

# 4b. Production-targeted dispatch: the staging safety leg selects staging's
#     own latest backup (the operator object belongs to production).
: > "$TEST_ROOT/out-dispatch-prod-leg"
run_step backup-step-1.sh "$TEST_ROOT/out-dispatch-prod-leg" \
  BOOTSTRAP=false MODE=dispatch DISPATCH_TARGET=production BACKUP_OBJECT=backups/y.dump.age \
  FAKE_ANCHOR_STATE=established
grep -Fx -- "--latest --expected-release-id $RELEASE_ID" "$TEST_ROOT/calls.log" >/dev/null
if grep -q -- '--object' "$TEST_ROOT/calls.log"; then
  printf 'production-targeted dispatch used the production backup object on staging\n' >&2
  exit 1
fi

# --- production job ----------------------------------------------------------

# 5. Push-run production: latest backup against the current release id.
: > "$TEST_ROOT/out-prod-push"
run_step backup-step-2.sh "$TEST_ROOT/out-prod-push" \
  GITHUB_EVENT_NAME=push BOOTSTRAP=false BACKUP_OBJECT= \
  FAKE_ANCHOR_STATE=established
grep -Fx 'bootstrap=false' "$TEST_ROOT/out-prod-push" >/dev/null
grep -Fx -- "--latest --expected-release-id $RELEASE_ID" "$TEST_ROOT/calls.log" >/dev/null

# 6. Dispatch production bootstrap: manual anchor + operator backup object.
: > "$TEST_ROOT/out-prod-bootstrap"
run_step backup-step-2.sh "$TEST_ROOT/out-prod-bootstrap" \
  GITHUB_EVENT_NAME=workflow_dispatch BOOTSTRAP=true BACKUP_OBJECT=backups/y.dump.age \
  FAKE_ANCHOR_STATE=bootstrap
grep -Fx 'bootstrap=true' "$TEST_ROOT/out-prod-bootstrap" >/dev/null
grep -Fx 'require-manual-current --compose-id c1' "$TEST_ROOT/calls.log" >/dev/null
grep -Fx -- '--object backups/y.dump.age --expected-release-id manual' "$TEST_ROOT/calls.log" >/dev/null

# 7. Dispatch production without a backup object fails closed.
if run_step backup-step-2.sh "$TEST_ROOT/out-prod-noobj" \
  GITHUB_EVENT_NAME=workflow_dispatch BOOTSTRAP=false BACKUP_OBJECT= \
  FAKE_ANCHOR_STATE=established 2>/dev/null; then
  printf 'production dispatch without a backup object was accepted\n' >&2
  exit 1
fi

printf 'deploy backup steps bind bootstrap and backup selection per role\n'
