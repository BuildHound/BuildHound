#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${BACKUP_OBJECT:?}" "${BACKUP_VERSION_ID:?}" "${AGE_KEY_FILE:?}" "${S3_ENDPOINT:?}" "${S3_BUCKET:?}" "${PGHOST:?}" "${PGDATABASE:?}" "${PGUSER:?}" "${PGPASSFILE:?}"
case "$BACKUP_OBJECT" in
  *.dump.age) ;;
  *) echo "backup object must be a completed .dump.age key" >&2; exit 64 ;;
esac
version_id=$BACKUP_VERSION_ID
case "$version_id" in
  None|null) echo "backup version ID is not immutable" >&2; exit 66 ;;
  *$'\r'*|*$'\n'*) echo "backup version ID is invalid" >&2; exit 66 ;;
esac
# shellcheck source=/dev/null
. "${S3_CREDENTIALS_FILE:-/run/secrets/s3_credentials}"
: "${AWS_ACCESS_KEY_ID:?}" "${AWS_SECRET_ACCESS_KEY:?}"
export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-${AWS_REGION:-us-east-1}}"
export AWS_REGION="${AWS_REGION:-$AWS_DEFAULT_REGION}"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT
encrypted="$workdir/backup.dump.age"
archive="$workdir/backup.dump"

head_result=$(aws --endpoint-url "$S3_ENDPOINT" s3api head-object \
  --bucket "$S3_BUCKET" --key "$BACKUP_OBJECT" --version-id "$BACKUP_VERSION_ID" \
  --query '[Metadata."buildhound-backup-complete",VersionId]' --output text)
IFS=$'\t' read -r complete observed_version_id <<< "$head_result"
[ "$complete" = true ] || { echo "backup object is not marked complete" >&2; exit 65; }
case "$observed_version_id" in
  ""|None|null) echo "backup object has no immutable version ID" >&2; exit 66 ;;
esac
[ "$observed_version_id" = "$version_id" ] || {
  echo "backup object version does not match attested version ID" >&2
  exit 66
}

aws --endpoint-url "$S3_ENDPOINT" s3api get-object \
  --bucket "$S3_BUCKET" --key "$BACKUP_OBJECT" --version-id "$version_id" \
  "$encrypted" >/dev/null
age -d -i "$AGE_KEY_FILE" -o "$archive" "$encrypted"
pg_restore --list "$archive" >/dev/null
pg_restore --exit-on-error --single-transaction --clean --if-exists \
  --host "$PGHOST" --username "$PGUSER" --dbname "$PGDATABASE" "$archive"
