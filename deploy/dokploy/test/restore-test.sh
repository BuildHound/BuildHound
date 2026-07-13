#!/usr/bin/env bash
set -euo pipefail

root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
bin="$root/bin"
mkdir -p "$bin"
aws_log="$root/aws.log"
restore_log="$root/restore.log"
credentials="$root/s3_credentials"

cat > "$credentials" <<'EOF'
AWS_ACCESS_KEY_ID=test-access
AWS_SECRET_ACCESS_KEY=test-secret
AWS_DEFAULT_REGION=us-east-1
EOF

cat > "$bin/aws" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$AWS_LOG"
case " $* " in
  *" s3api head-object "*)
    printf '%s\t%s\n' "${HEAD_COMPLETE:-true}" "${HEAD_VERSION:-version-1}"
    ;;
  *" s3api get-object "*)
    for argument do output=$argument; done
    printf 'encrypted archive\n' > "$output"
    ;;
esac
EOF

cat > "$bin/age" <<'EOF'
#!/usr/bin/env sh
while [ "$#" -gt 0 ]; do
  if [ "$1" = -o ]; then
    output=$2
    shift 2
  else
    shift
  fi
done
printf 'custom archive\n' > "$output"
EOF

cat > "$bin/pg_restore" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$RESTORE_LOG"
EOF
chmod +x "$bin/aws" "$bin/age" "$bin/pg_restore"

run_restore() (
  export BACKUP_OBJECT=$1
  export AGE_KEY_FILE="$root/age-key"
  export S3_ENDPOINT=https://s3.example.test
  export S3_BUCKET=test-bucket
  export PGHOST=database
  export PGDATABASE=buildhound
  export PGUSER=buildhound
  export PGPASSFILE="$root/pgpass"
  export S3_CREDENTIALS_FILE="$credentials"
  export AWS_LOG="$aws_log"
  export RESTORE_LOG="$restore_log"
  export PATH="$bin:$PATH"
  bash deploy/dokploy/backup/restore.sh
)

: > "$aws_log"
set +e
run_restore backups/test.dump.age.partial >/dev/null 2>&1
status=$?
set -e
test "$status" -eq 64
test ! -s "$aws_log"

: > "$aws_log"
set +e
HEAD_COMPLETE=false run_restore backups/test.dump.age >/dev/null 2>&1
status=$?
set -e
test "$status" -eq 65
if grep -F -- 's3api get-object' "$aws_log" >/dev/null; then
  echo 'restore downloaded an incomplete backup' >&2
  exit 1
fi

: > "$aws_log"
set +e
HEAD_VERSION=None run_restore backups/test.dump.age >/dev/null 2>&1
status=$?
set -e
test "$status" -eq 66
if grep -F -- 's3api get-object' "$aws_log" >/dev/null; then
  echo 'restore downloaded an unversioned backup' >&2
  exit 1
fi

: > "$aws_log"
: > "$restore_log"
HEAD_COMPLETE=true HEAD_VERSION=version-42 run_restore backups/test.dump.age >/dev/null
grep -F -- 's3api head-object --bucket test-bucket --key backups/test.dump.age' "$aws_log" >/dev/null
grep -F -- 's3api get-object --bucket test-bucket --key backups/test.dump.age --version-id version-42' "$aws_log" >/dev/null
list_match=$(grep -n -m 1 -F -- '--list' "$restore_log")
apply_match=$(grep -n -m 1 -F -- '--exit-on-error --single-transaction' "$restore_log")
test "${list_match%%:*}" -lt "${apply_match%%:*}"

printf 'version-pinned restore validated\n'
