#!/usr/bin/env bash
set -euo pipefail

root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
mkdir -p "$root/bin"

cat > "$root/bin/aws" <<'EOF'
#!/bin/sh
set -eu
case "$*" in
  *" get-bucket-versioning "*)
    if [ "${VERSIONING_DISABLED:-false}" = true ]; then
      printf '%s\n' '{}'
    else
      printf '%s\n' '{"Status":"Enabled"}'
    fi
    ;;
  *" list-objects-v2 "*)
    if [ "${LATEST_INVALID:-false}" = true ]; then
      printf '%s\n' '{"Contents":[{"Key":"backups/buildhound-20260713T120000Z.dump.age","LastModified":"new"},{"Key":"backups/buildhound-20260713T110000Z.dump.age","LastModified":"old"}]}'
    else
      printf '%s\n' '{"Contents":[{"Key":"backups/buildhound-20260713T110000Z.dump.age","LastModified":"new"},{"Key":"backups/buildhound-20260713T100000Z.dump.age.partial","LastModified":"older"}]}'
    fi
    ;;
  *" head-object "*"buildhound-20260713T120000Z.dump.age")
    printf '%s\n' '{"ContentLength":10,"VersionId":"v2","LastModified":"valid-modified","Metadata":{"buildhound-db-instance":"staging","age-recipient-sha256":"recipient-hash","buildhound-release-id":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","buildhound-backup-complete":"true","started-at":"valid-started","completed-at":"valid-completed"}}'
    ;;
  *" head-object "*"buildhound-20260713T110000Z.dump.age")
    if [ "${NULL_VERSION:-false}" = true ]; then
      printf '%s\n' '{"ContentLength":10,"VersionId":"null","LastModified":"valid-modified","Metadata":{"buildhound-db-instance":"staging","age-recipient-sha256":"recipient-hash","buildhound-release-id":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","buildhound-backup-complete":"true","started-at":"valid-started","completed-at":"valid-completed"}}'
    else
      printf '%s\n' '{"ContentLength":10,"VersionId":"v1","LastModified":"valid-modified","Metadata":{"buildhound-db-instance":"staging","age-recipient-sha256":"recipient-hash","buildhound-release-id":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","buildhound-backup-complete":"true","started-at":"valid-started","completed-at":"valid-completed"}}'
    fi
    ;;
  *) printf 'unexpected aws call: %s\n' "$*" >&2; exit 90 ;;
esac
EOF

cat > "$root/bin/date" <<'EOF'
#!/bin/sh
set -eu
case "$*" in
  "-u +%s") printf '2000000000\n' ;;
  *valid-started*) printf '1999999800\n' ;;
  *valid-completed*) printf '1999999850\n' ;;
  *valid-modified*) printf '1999999900\n' ;;
  *) exit 1 ;;
esac
EOF

cat > "$root/bin/sha256sum" <<'EOF'
#!/bin/sh
cat >/dev/null
printf 'recipient-hash  -\n'
EOF
chmod +x "$root/bin/aws" "$root/bin/date" "$root/bin/sha256sum"

export PATH="$root/bin:$PATH"
export BACKUP_S3_ENDPOINT=https://objects.example.test
export BACKUP_S3_BUCKET=buildhound-staging
export BUILDHOUND_DB_INSTANCE=staging
export BUILDHOUND_AGE_RECIPIENT=age1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
export BACKUP_SELECTION_ATTEMPTS=1
release=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

selected=$(deploy/dokploy/select-backup.sh --latest --expected-release-id "$release")
test "$(jq -r .object <<<"$selected")" = backups/buildhound-20260713T110000Z.dump.age
test "$(jq -r .versionId <<<"$selected")" = v1
test "$(jq -r .expectedReleaseId <<<"$selected")" = "$release"

selected=$(deploy/dokploy/select-backup.sh \
  --object backups/buildhound-20260713T110000Z.dump.age --expected-release-id "$release")
test "$(jq -r .object <<<"$selected")" = backups/buildhound-20260713T110000Z.dump.age
test "$(jq -r .versionId <<<"$selected")" = v1
test "$(jq -r .expectedReleaseId <<<"$selected")" = "$release"

if LATEST_INVALID=true deploy/dokploy/select-backup.sh --latest \
    --expected-release-id "$release" >/dev/null 2>&1; then
  echo 'older backup was used after the newest final backup failed validation' >&2
  exit 1
fi

if deploy/dokploy/select-backup.sh --object backups/buildhound-20260713T100000Z.dump.age.partial \
    --expected-release-id "$release" >/dev/null 2>&1; then
  echo 'partial backup was accepted' >&2
  exit 1
fi
if BACKUP_S3_ENDPOINT=objects.example.test deploy/dokploy/select-backup.sh --latest \
    --expected-release-id "$release" >/dev/null 2>&1; then
  echo 'non-HTTPS endpoint was accepted' >&2
  exit 1
fi
if VERSIONING_DISABLED=true deploy/dokploy/select-backup.sh --latest \
    --expected-release-id "$release" >/dev/null 2>&1; then
  echo 'bucket without enabled versioning was accepted' >&2
  exit 1
fi
if NULL_VERSION=true deploy/dokploy/select-backup.sh \
    --object backups/buildhound-20260713T110000Z.dump.age \
    --expected-release-id "$release" >/dev/null 2>&1; then
  echo 'literal null backup version was accepted' >&2
  exit 1
fi

printf 'backup selection validated\n'
