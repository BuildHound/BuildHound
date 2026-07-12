#!/usr/bin/env bash
set -euo pipefail
: "${BACKUP_OBJECT:?}" "${AGE_KEY_FILE:?}" "${PGHOST:?}" "${PGDATABASE:?}" "${PGUSER:?}" "${PGPASSFILE:?}"
# shellcheck source=/dev/null
. /run/secrets/s3_credentials
export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
aws --endpoint-url "$S3_ENDPOINT" s3 cp "s3://$S3_BUCKET/$BACKUP_OBJECT" - --no-progress | \
  age -d -i "$AGE_KEY_FILE" | pg_restore --exit-on-error --clean --if-exists --host "$PGHOST" --username "$PGUSER" --dbname "$PGDATABASE"
