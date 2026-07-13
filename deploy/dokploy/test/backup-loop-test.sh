#!/usr/bin/env bash
set -euo pipefail

root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
bin="$root/bin"
mkdir -p "$bin"
log="$root/aws.log"
credentials="$root/s3_credentials"

cat > "$credentials" <<'EOF'
AWS_ACCESS_KEY_ID=test-access
AWS_SECRET_ACCESS_KEY=test-secret
AWS_DEFAULT_REGION=us-east-1
EOF

cat > "$bin/pg_dump" <<'EOF'
#!/usr/bin/env sh
printf 'custom archive bytes\n'
EOF

cat > "$bin/age" <<'EOF'
#!/usr/bin/env sh
cat
if [ "${AGE_FAIL:-false}" = true ]; then
  exit 9
fi
EOF

cat > "$bin/aws" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$AWS_LOG"
case " $* " in
  *" s3 cp - s3://"*) cat >/dev/null ;;
esac
EOF
chmod +x "$bin/pg_dump" "$bin/age" "$bin/aws"

run_backup() (
  export AGE_RECIPIENT=age1test
  export S3_ENDPOINT=https://s3.example.test
  export S3_BUCKET=test-bucket
  export PGPASSFILE="$root/pgpass"
  export BUILDHOUND_DB_INSTANCE=test-db
  export BUILDHOUND_RELEASE_ID=test-release
  export S3_CREDENTIALS_FILE="$credentials"
  export AWS_LOG="$log"
  export PATH="$bin:$PATH"
  # shellcheck source=/dev/null
  . deploy/dokploy/backup/backup-loop.sh
  backup
)

: > "$log"
run_backup >/dev/null
partial=$(sed -n 's#.*s3 cp - s3://test-bucket/\(backups/[^ ]*\.dump\.age\.partial\).*#\1#p' "$log")
test -n "$partial"
final=${partial%.partial}
grep -F -- "s3 cp s3://test-bucket/$partial s3://test-bucket/$final --no-progress --metadata-directive REPLACE" "$log" >/dev/null
grep -F -- 'buildhound-backup-complete=true' "$log" >/dev/null
grep -F -- "s3api head-object --bucket test-bucket --key $final" "$log" >/dev/null
grep -F -- "s3api delete-object --bucket test-bucket --key $partial" "$log" >/dev/null
upload_match=$(grep -n -m 1 -F -- "s3 cp - s3://test-bucket/$partial" "$log")
copy_match=$(grep -n -m 1 -F -- "s3 cp s3://test-bucket/$partial s3://test-bucket/$final" "$log")
head_match=$(grep -n -m 1 -F -- "s3api head-object --bucket test-bucket --key $final" "$log")
delete_match=$(grep -n -m 1 -F -- "s3api delete-object --bucket test-bucket --key $partial" "$log")
test "${upload_match%%:*}" -lt "${copy_match%%:*}"
test "${copy_match%%:*}" -lt "${head_match%%:*}"
test "${head_match%%:*}" -lt "${delete_match%%:*}"
if grep -F -- "s3 cp - s3://test-bucket/$final " "$log" >/dev/null; then
  echo 'backup uploaded directly to its eligible final key' >&2
  exit 1
fi
if grep -F -- 's3api copy-object' "$log" >/dev/null; then
  echo 'backup used the 5 GiB-limited single-request copy API' >&2
  exit 1
fi

: > "$log"
set +e
AGE_FAIL=true run_backup >/dev/null 2>&1
status=$?
set -e
if [ "$status" -eq 0 ]; then
  echo 'failed encryption unexpectedly published a backup' >&2
  exit 1
fi
grep -F -- '.dump.age.partial' "$log" >/dev/null
if grep -F -- 's3://test-bucket/' "$log" | grep -F -- '.dump.age.partial s3://test-bucket/' >/dev/null; then
  echo 'failed encryption copied the staged object to a final key' >&2
  exit 1
fi
grep -F -- 's3api delete-object' "$log" >/dev/null

printf 'multipart-safe backup staging validated\n'
