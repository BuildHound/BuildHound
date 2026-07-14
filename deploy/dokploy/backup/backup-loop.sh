#!/usr/bin/env bash
# Staging credentials are ordinary environment values; never let inherited xtrace print them.
set +x
set -euo pipefail
umask 077
: "${AGE_RECIPIENT:?}" "${S3_ENDPOINT:?}" "${S3_BUCKET:?}" "${BUILDHOUND_DB_INSTANCE:?}" "${BUILDHOUND_RELEASE_ID:?}"

if [ -n "${PGPASSFILE:-}" ] && [ -n "${PGPASSWORD:-}" ]; then
  printf 'configure exactly one PostgreSQL password source\n' >&2
  exit 64
elif [ -n "${PGPASSFILE:-}" ]; then
  [ -r "$PGPASSFILE" ] || { printf 'PostgreSQL password file is not readable\n' >&2; exit 66; }
elif [ -z "${PGPASSWORD:-}" ]; then
  printf 'PostgreSQL password source is required\n' >&2
  exit 64
fi

if [ -n "${S3_CREDENTIALS_FILE:-}" ]; then
  if [ -n "${AWS_ACCESS_KEY_ID:-}" ] || [ -n "${AWS_SECRET_ACCESS_KEY:-}" ]; then
    printf 'configure exactly one S3 credential source\n' >&2
    exit 64
  fi
  [ -r "$S3_CREDENTIALS_FILE" ] || { printf 'S3 credential file is not readable\n' >&2; exit 66; }
  # shellcheck source=/dev/null
  . "$S3_CREDENTIALS_FILE"
fi
: "${AWS_ACCESS_KEY_ID:?}" "${AWS_SECRET_ACCESS_KEY:?}"
export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

staged_key=

cleanup_staged() {
  if [ -n "$staged_key" ]; then
    aws --endpoint-url "$S3_ENDPOINT" s3api delete-object \
      --bucket "$S3_BUCKET" --key "$staged_key" >/dev/null 2>&1 || true
  fi
}

trap cleanup_staged EXIT

backup() {
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  key="backups/buildhound-$stamp.dump.age"
  staged_key="$key.partial"
  recipient_sha=$(printf '%s' "$AGE_RECIPIENT" | sha256sum | cut -d' ' -f1)
  metadata="buildhound-db-instance=$BUILDHOUND_DB_INSTANCE,buildhound-release-id=$BUILDHOUND_RELEASE_ID,age-recipient-sha256=$recipient_sha,started-at=$started_at"

  pg_dump --format=custom | age -r "$AGE_RECIPIENT" | \
    aws --endpoint-url "$S3_ENDPOINT" s3 cp - "s3://$S3_BUCKET/$staged_key" \
      --no-progress --metadata "$metadata,buildhound-backup-complete=false"

  completed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  # The high-level S3 client automatically uses multipart copy for large objects;
  # the single-request CopyObject API cannot finalize objects larger than 5 GiB.
  aws --endpoint-url "$S3_ENDPOINT" s3 cp \
    "s3://$S3_BUCKET/$staged_key" \
    "s3://$S3_BUCKET/$key" \
    --no-progress \
    --metadata-directive REPLACE \
    --metadata "$metadata,completed-at=$completed_at,buildhound-backup-complete=true" >/dev/null
  aws --endpoint-url "$S3_ENDPOINT" s3api head-object --bucket "$S3_BUCKET" --key "$key" >/dev/null
  aws --endpoint-url "$S3_ENDPOINT" s3api delete-object --bucket "$S3_BUCKET" --key "$staged_key" >/dev/null
  staged_key=
  printf 'backup completed: %s\n' "$key"
}

main() {
  while :; do backup; sleep "${BACKUP_INTERVAL_SECONDS:-82800}"; done
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main
fi
