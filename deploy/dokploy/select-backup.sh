#!/usr/bin/env bash
set -euo pipefail
umask 077

usage() {
  printf 'usage: select-backup.sh (--latest | --object KEY) --expected-release-id ID\n' >&2
  return 2
}

sha256_text() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 | cut -d' ' -f1
  else
    printf 'sha256sum or shasum required\n' >&2
    return 127
  fi
}

mode=
object=
expected_release=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --latest)
      [ -z "$mode" ] || { usage; exit 2; }
      mode=latest
      shift
      ;;
    --object)
      if [ -n "$mode" ] || [ "$#" -lt 2 ]; then usage; exit 2; fi
      mode=object
      object=$2
      shift 2
      ;;
    --expected-release-id)
      [ "$#" -ge 2 ] || { usage; exit 2; }
      expected_release=$2
      shift 2
      ;;
    *) usage; exit 2 ;;
  esac
done

if [ -z "$mode" ] || [ -z "$expected_release" ]; then usage; exit 2; fi
: "${BACKUP_S3_ENDPOINT:?}" "${BACKUP_S3_BUCKET:?}" \
  "${BUILDHOUND_DB_INSTANCE:?}" "${BUILDHOUND_AGE_RECIPIENT:?}"

endpoint=${BACKUP_S3_ENDPOINT%/}
case "$endpoint" in
  https://*) ;;
  *) printf 'backup endpoint must be an HTTPS origin\n' >&2; exit 64 ;;
esac
authority=${endpoint#https://}
case "$authority" in
  ''|*/*|*@*|*\?*|*\#*|*[[:space:]]*)
    printf 'backup endpoint must be an HTTPS origin\n' >&2
    exit 64
    ;;
esac
case "$BACKUP_S3_BUCKET" in
  ''|.*|*.|*[!A-Za-z0-9.-]*) printf 'invalid backup bucket\n' >&2; exit 64 ;;
esac
case "$expected_release" in
  manual) ;;
  sha256:????????????????????????????????????????????????????????????????)
    case "${expected_release#sha256:}" in *[!0-9a-f]*) printf 'invalid expected release ID\n' >&2; exit 64;; esac
    ;;
  *) printf 'invalid expected release ID\n' >&2; exit 64 ;;
esac
if [[ ! $BUILDHOUND_AGE_RECIPIENT =~ ^age1[0-9a-z]{20,100}$ ]]; then
  printf 'invalid age recipient\n' >&2
  exit 64
fi

workdir=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/buildhound-backup-select.XXXXXX")
trap 'rm -rf -- "$workdir"' EXIT
list_file=$workdir/list.json
head_file=$workdir/head.json
versioning_file=$workdir/versioning.json
recipient_sha=$(printf '%s' "$BUILDHOUND_AGE_RECIPIENT" | sha256_text)
now_epoch=$(date -u +%s)

aws --endpoint-url "$endpoint" s3api get-bucket-versioning \
  --bucket "$BACKUP_S3_BUCKET" > "$versioning_file"
if ! jq -e 'type == "object" and .Status == "Enabled"' "$versioning_file" >/dev/null; then
  printf 'backup bucket versioning must be enabled\n' >&2
  exit 1
fi

valid_key() {
  [[ ${1-} =~ ^backups/buildhound-[0-9]{8}T[0-9]{6}Z\.dump\.age$ ]]
}

validate_object() {
  local key=$1 modified modified_epoch started started_epoch completed completed_epoch
  valid_key "$key" || return 1
  aws --endpoint-url "$endpoint" s3api head-object \
    --bucket "$BACKUP_S3_BUCKET" --key "$key" > "$head_file" || return 1
  jq -e --arg instance "$BUILDHOUND_DB_INSTANCE" --arg recipient "$recipient_sha" \
    --arg release "$expected_release" '
      (.ContentLength | type) == "number" and .ContentLength > 0 and
      (.VersionId | type) == "string" and (.VersionId | length) > 0 and
      (.VersionId != "null" and .VersionId != "None") and
      .Metadata["buildhound-db-instance"] == $instance and
      .Metadata["age-recipient-sha256"] == $recipient and
      .Metadata["buildhound-release-id"] == $release and
      .Metadata["buildhound-backup-complete"] == "true" and
      (.Metadata["started-at"] | type) == "string" and
      (.Metadata["completed-at"] | type) == "string" and
      (.LastModified | type) == "string"
    ' "$head_file" >/dev/null || return 1
  modified=$(jq -er .LastModified "$head_file") || return 1
  started=$(jq -er '.Metadata["started-at"]' "$head_file") || return 1
  completed=$(jq -er '.Metadata["completed-at"]' "$head_file") || return 1
  modified_epoch=$(date -u -d "$modified" +%s 2>/dev/null) || return 1
  started_epoch=$(date -u -d "$started" +%s 2>/dev/null) || return 1
  completed_epoch=$(date -u -d "$completed" +%s 2>/dev/null) || return 1
  [ "$modified_epoch" -le $((now_epoch + 300)) ] || return 1
  [ $((now_epoch - modified_epoch)) -le 86400 ] || return 1
  [ "$started_epoch" -le "$completed_epoch" ] || return 1
  [ "$completed_epoch" -le $((modified_epoch + 300)) ] || return 1
  [ $((now_epoch - started_epoch)) -le 86400 ] || return 1
}

emit_evidence() {
  local key=$1 version
  version=$(jq -er '.VersionId | select(type == "string" and length > 0 and . != "null" and . != "None")' "$head_file")
  jq -cn --arg expectedReleaseId "$expected_release" --arg object "$key" \
    --arg versionId "$version" \
    '{expectedReleaseId:$expectedReleaseId,object:$object,versionId:$versionId}'
}

if [ "$mode" = object ]; then
  if ! validate_object "$object"; then
    printf 'backup object failed freshness or metadata validation\n' >&2
    exit 1
  fi
  emit_evidence "$object"
  exit 0
fi

attempts=${BACKUP_SELECTION_ATTEMPTS:-10}
interval=${BACKUP_SELECTION_INTERVAL_SECONDS:-30}
[[ $attempts =~ ^[1-9][0-9]*$ ]] || {
  printf 'invalid backup selection polling bounds\n' >&2
  exit 64
}
[[ $interval =~ ^[0-9]+$ ]] || {
  printf 'invalid backup selection polling bounds\n' >&2
  exit 64
}
if [ "${#attempts}" -gt 2 ] || [ "$attempts" -gt 20 ] ||
   [ "${#interval}" -gt 3 ] || [ "$interval" -gt 300 ]; then
  printf 'invalid backup selection polling bounds\n' >&2
  exit 64
fi
for ((attempt = 1; attempt <= attempts; attempt++)); do
  aws --endpoint-url "$endpoint" s3api list-objects-v2 \
    --bucket "$BACKUP_S3_BUCKET" --prefix backups/ > "$list_file"
  if ! jq -e '(.Contents // []) | type == "array"' "$list_file" >/dev/null; then
    printf 'invalid backup object listing\n' >&2
    exit 1
  fi
  candidate=$(jq -r '
    [(.Contents // [])[]
      | select((.Key | type) == "string" and
               (.Key | test("^backups/buildhound-[0-9]{8}T[0-9]{6}Z[.]dump[.]age$")))
      | .Key]
    | sort | reverse | .[0] // empty
  ' "$list_file")
  if [ -n "$candidate" ] && validate_object "$candidate"; then
    emit_evidence "$candidate"
    exit 0
  fi
  if [ "$attempt" -lt "$attempts" ]; then sleep "$interval"; fi
done

printf 'newest final backup does not match the current release and freshness gates\n' >&2
exit 1
