#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${AGE_RECIPIENT:?}" "${S3_ENDPOINT:?}" "${S3_BUCKET:?}" "${PGPASSFILE:?}" "${BUILDHOUND_DB_INSTANCE:?}" "${BUILDHOUND_RELEASE_ID:?}"
# shellcheck source=/dev/null
. /run/secrets/s3_credentials
export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

backup() {
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  key="backups/buildhound-$stamp.dump.age"
  recipient_sha=$(printf '%s' "$AGE_RECIPIENT" | sha256sum | cut -d' ' -f1)
  pg_dump --format=custom | age -r "$AGE_RECIPIENT" | \
    aws --endpoint-url "$S3_ENDPOINT" s3 cp - "s3://$S3_BUCKET/$key" --no-progress --metadata "buildhound-db-instance=$BUILDHOUND_DB_INSTANCE,buildhound-release-id=$BUILDHOUND_RELEASE_ID,age-recipient-sha256=$recipient_sha,started-at=$started_at"
  aws --endpoint-url "$S3_ENDPOINT" s3api head-object --bucket "$S3_BUCKET" --key "$key" >/dev/null
  printf 'backup completed: %s\n' "$key"
}

while :; do backup; sleep "${BACKUP_INTERVAL_SECONDS:-82800}"; done
