#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
# Resolved from the repository root calculated above.
# shellcheck disable=SC1091
source "$ROOT/deploy/dokploy/lib/release.sh"

tests=0

fail() {
  printf 'release client test failed: %s\n' "$1" >&2
  exit 1
}

assert_eq() {
  local description=$1 expected=$2 actual=$3
  tests=$((tests + 1))
  [[ $actual == "$expected" ]] || fail "$description (expected '$expected', got '$actual')"
}

assert_command_empty() {
  local description=$1 actual
  shift
  tests=$((tests + 1))
  if ! actual=$("$@" 2>/dev/null); then
    fail "$description (command failed)"
  fi
  [[ -z $actual ]] || fail "$description (expected empty output, got '$actual')"
}

assert_ok() {
  local description=$1
  shift
  tests=$((tests + 1))
  "$@" >/dev/null 2>&1 || fail "$description"
}

assert_fails() {
  local description=$1
  shift
  tests=$((tests + 1))
  if "$@" >/dev/null 2>&1; then
    fail "$description (unexpected success)"
  fi
}

repeat_char() {
  local character=$1 count=$2 value=
  while [[ ${#value} -lt $count ]]; do value+=$character; done
  printf '%s' "$value"
}

history_hash() {
  local canonical
  canonical=$(jq -cS . <<< "$1")
  printf '%s\n' "$canonical" | sha256_stdin
}

write_v1() {
  local file=$1 migration_id=${2:-V1__initial}
  jq -n \
    --arg sourceCommit "$SOURCE_SHA" \
    --arg serverImage "$SERVER_IMAGE" \
    --arg siteImage "$SITE_IMAGE" \
    --arg backupImage "$BACKUP_IMAGE" \
    --arg postgresImage "$POSTGRES_IMAGE" \
    --arg manifestSha256 "$H5" \
    --arg volumeGuardSha256 "$H6" \
    --arg migrationId "$migration_id" \
    '{schema:1,sourceCommit:$sourceCommit,serverImage:$serverImage,siteImage:$siteImage,
      backupImage:$backupImage,postgresImage:$postgresImage,manifestSha256:$manifestSha256,
      volumeGuardSha256:$volumeGuardSha256,migrationId:$migrationId}' > "$file"
}

write_v2() {
  local file=$1 history=$2 migration_id history_sha
  if [[ $# -ge 3 ]]; then
    migration_id=$3
  else
    migration_id=$(jq -er '.[-1].id' <<< "$history")
  fi
  if [[ $# -ge 4 ]]; then
    history_sha=$4
  else
    history_sha=$(history_hash "$history")
  fi
  jq -n \
    --arg sourceCommit "$SOURCE_SHA" \
    --arg serverImage "$SERVER_IMAGE" \
    --arg siteImage "$SITE_IMAGE" \
    --arg backupImage "$BACKUP_IMAGE" \
    --arg postgresImage "$POSTGRES_IMAGE" \
    --arg manifestSha256 "$H5" \
    --arg volumeGuardSha256 "$H6" \
    --arg migrationId "$migration_id" \
    --argjson migrationHistory "$history" \
    --arg migrationHistorySha256 "$history_sha" \
    '{schema:2,sourceCommit:$sourceCommit,serverImage:$serverImage,siteImage:$siteImage,
      backupImage:$backupImage,postgresImage:$postgresImage,manifestSha256:$manifestSha256,
      volumeGuardSha256:$volumeGuardSha256,migrationId:$migrationId,
      migrationHistory:$migrationHistory,migrationHistorySha256:$migrationHistorySha256}' > "$file"
}

write_v3() {
  local file=$1 history=$2 migration_id history_sha
  if [[ $# -ge 3 ]]; then
    migration_id=$3
  else
    migration_id=$(jq -er '.[-1].id' <<< "$history")
  fi
  if [[ $# -ge 4 ]]; then
    history_sha=$4
  else
    history_sha=$(history_hash "$history")
  fi
  jq -n \
    --arg sourceCommit "$SOURCE_SHA" \
    --arg serverImage "$SERVER_IMAGE" \
    --arg siteImage "$SITE_IMAGE" \
    --arg backupImage "$BACKUP_IMAGE" \
    --arg postgresImage "$POSTGRES_IMAGE" \
    --arg productionManifestSha256 "$H5" \
    --arg stagingManifestSha256 "$H9" \
    --arg volumeGuardSha256 "$H6" \
    --arg migrationId "$migration_id" \
    --argjson migrationHistory "$history" \
    --arg migrationHistorySha256 "$history_sha" \
    '{schema:3,sourceCommit:$sourceCommit,serverImage:$serverImage,siteImage:$siteImage,
      backupImage:$backupImage,postgresImage:$postgresImage,
      productionManifestSha256:$productionManifestSha256,
      stagingManifestSha256:$stagingManifestSha256,
      volumeGuardSha256:$volumeGuardSha256,migrationId:$migrationId,
      migrationHistory:$migrationHistory,migrationHistorySha256:$migrationHistorySha256}' > "$file"
}

current_full() {
  jq -cn --arg releaseId "$1" --arg migrationId "$2" --arg migrationHistorySha256 "$3" \
    '{releaseId:$releaseId,migrationId:$migrationId,migrationHistorySha256:$migrationHistorySha256}'
}

current_legacy() {
  jq -cn --arg releaseId "$1" --arg migrationId "$2" \
    '{releaseId:$releaseId,migrationId:$migrationId,migrationHistorySha256:null}'
}

current_bare() {
  jq -cn --arg releaseId "$1" \
    '{releaseId:$releaseId,migrationId:null,migrationHistorySha256:null}'
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

SOURCE_SHA=$(repeat_char a 40)
H1=$(repeat_char 1 64)
H2=$(repeat_char 2 64)
H3=$(repeat_char 3 64)
H4=$(repeat_char 4 64)
H5=$(repeat_char 5 64)
H6=$(repeat_char 6 64)
H7=$(repeat_char 7 64)
H8=$(repeat_char 8 64)
H9=$(repeat_char 9 64)
H0=$(repeat_char 0 64)
SERVER_IMAGE="ghcr.io/buildhound/server@sha256:$H1"
SITE_IMAGE="ghcr.io/buildhound/site@sha256:$H2"
BACKUP_IMAGE="ghcr.io/buildhound/backup@sha256:$H3"
POSTGRES_IMAGE="ghcr.io/buildhound/db@sha256:$H4"

printf abc > "$tmp/abc"
assert_eq "SHA-256 stdin" \
  "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" \
  "$(printf abc | sha256_stdin)"
assert_eq "SHA-256 file" "$(printf abc | sha256_stdin)" "$(sha256_file "$tmp/abc")"
assert_eq "canonical JSON" '{"a":1,"b":2}' "$(printf '{\"b\":2,\"a\":1}' | canonical_json)"
assert_ok "lowercase source SHA accepted" is_source_sha "$SOURCE_SHA"
UPPER_SOURCE_SHA=$(printf '%s' "$SOURCE_SHA" | tr '[:lower:]' '[:upper:]')
assert_fails "uppercase source SHA rejected" is_source_sha "$UPPER_SOURCE_SHA"
assert_ok "digest reference accepted" is_digest_reference "$SERVER_IMAGE"
assert_fails "moving image tag rejected" is_digest_reference "ghcr.io/buildhound/server:latest"

V1="$tmp/release-v1.json"
HISTORY_2_10=$(jq -cn --arg h7 "$H7" --arg h8 "$H8" \
  '[{id:"V2__earlier",sha256:$h7},{id:"V10__later",sha256:$h8}]')
V2="$tmp/release-v2.json"
write_v1 "$V1"
write_v2 "$V2" "$HISTORY_2_10"
V3="$tmp/release-v3.json"
write_v3 "$V3" "$HISTORY_2_10"
assert_ok "schema 1 release accepted" release_validate "$V1"
assert_ok "schema 2 release accepted with numeric order" release_validate "$V2"
assert_ok "schema 3 release accepts target manifests" release_validate "$V3"

canonical=$(canonical_json "$V2")
expected_id="sha256:$(printf '%s\n' "$canonical" | sha256_stdin)"
assert_eq "release ID hashes canonical BOM" "$expected_id" "$(release_id "$V2")"
assert_eq "schema 1 title is legacy migration-bound form" \
  "$(release_id "$V1")|V1__initial" "$(release_title "$V1")"
assert_eq "schema 2 title includes history hash and source lineage" \
  "$expected_id|V10__later|$(history_hash "$HISTORY_2_10")|$SOURCE_SHA" "$(release_title "$V2")"
assert_eq "schema 3 title includes history hash and source lineage" \
  "$(release_id "$V3")|V10__later|$(history_hash "$HISTORY_2_10")|$SOURCE_SHA" \
  "$(release_title "$V3")"

v3_id=$(release_id "$V3")
jq --arg hash "$H0" '.stagingManifestSha256 = $hash' "$V3" > "$tmp/staging-manifest-change.json"
assert_ok "schema 3 accepts a changed valid staging manifest hash" \
  release_validate "$tmp/staging-manifest-change.json"
if [[ $(release_id "$tmp/staging-manifest-change.json") == "$v3_id" ]]; then
  fail "staging manifest hash did not change the release ID"
fi

jq '.extra = true' "$V2" > "$tmp/extra.json"
assert_fails "extra release key rejected" release_validate "$tmp/extra.json"
jq 'del(.siteImage)' "$V2" > "$tmp/missing.json"
assert_fails "missing release key rejected" release_validate "$tmp/missing.json"
jq '.sourceCommit = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"' "$V2" > "$tmp/source.json"
assert_fails "uppercase source commit rejected" release_validate "$tmp/source.json"
jq --arg value "$SOURCE_SHA"$'\n' '.sourceCommit = $value' "$V2" > "$tmp/source-newline.json"
assert_fails "source commit with trailing newline rejected" release_validate "$tmp/source-newline.json"
jq '.serverImage = "ghcr.io/buildhound/server:latest"' "$V2" > "$tmp/tag.json"
assert_fails "non-digest release image rejected" release_validate "$tmp/tag.json"
jq --arg value "$SERVER_IMAGE"$'\n' '.serverImage = $value' "$V2" > "$tmp/image-newline.json"
assert_fails "digest with trailing newline rejected" release_validate "$tmp/image-newline.json"
jq '.manifestSha256 = "ABC"' "$V2" > "$tmp/manifest.json"
assert_fails "invalid manifest checksum rejected" release_validate "$tmp/manifest.json"
jq --arg value "$H5"$'\n' '.manifestSha256 = $value' "$V2" > "$tmp/manifest-newline.json"
assert_fails "checksum with trailing newline rejected" release_validate "$tmp/manifest-newline.json"
jq '.productionManifestSha256 = "ABC"' "$V3" > "$tmp/production-manifest.json"
assert_fails "invalid production manifest checksum rejected" release_validate "$tmp/production-manifest.json"
jq '.stagingManifestSha256 = "ABC"' "$V3" > "$tmp/staging-manifest.json"
assert_fails "invalid staging manifest checksum rejected" release_validate "$tmp/staging-manifest.json"
jq '.volumeGuardSha256 = "ABC"' "$V2" > "$tmp/guard.json"
assert_fails "invalid guard checksum rejected" release_validate "$tmp/guard.json"
jq '.migrationId = "1_initial"' "$V2" > "$tmp/migration.json"
assert_fails "invalid migration ID rejected" release_validate "$tmp/migration.json"
jq --arg value 'V10__later'$'\n' '.migrationId = $value' "$V2" > "$tmp/migration-newline.json"
assert_fails "migration ID with trailing newline rejected" release_validate "$tmp/migration-newline.json"

write_v2 "$tmp/empty-history.json" '[]' 'V1__initial' "$H0"
assert_fails "empty migration history rejected" release_validate "$tmp/empty-history.json"
BAD_ENTRY=$(jq -cn --arg h7 "$H7" '[{id:"V1__initial",sha256:$h7,extra:true}]')
write_v2 "$tmp/bad-entry.json" "$BAD_ENTRY" 'V1__initial'
assert_fails "migration history entry keys are exact" release_validate "$tmp/bad-entry.json"
jq --arg value 'V2__earlier'$'\n' '.migrationHistory[0].id = $value' "$V2" > "$tmp/history-id-newline.json"
assert_fails "history ID with trailing newline rejected" release_validate "$tmp/history-id-newline.json"
jq --arg value "$H7"$'\n' '.migrationHistory[0].sha256 = $value' "$V2" > "$tmp/history-hash-newline.json"
assert_fails "history checksum with trailing newline rejected" release_validate "$tmp/history-hash-newline.json"
UNORDERED=$(jq -cn --arg h7 "$H7" --arg h8 "$H8" \
  '[{id:"V10__later",sha256:$h8},{id:"V2__earlier",sha256:$h7}]')
write_v2 "$tmp/unordered.json" "$UNORDERED"
assert_fails "numeric migration order required" release_validate "$tmp/unordered.json"
EQUAL_VERSION=$(jq -cn --arg h7 "$H7" --arg h8 "$H8" \
  '[{id:"V02__first",sha256:$h7},{id:"V2__second",sha256:$h8}]')
write_v2 "$tmp/equal-version.json" "$EQUAL_VERSION"
assert_fails "leading-zero duplicate migration version rejected" release_validate "$tmp/equal-version.json"
write_v2 "$tmp/last-mismatch.json" "$HISTORY_2_10" 'V11__missing'
assert_fails "latest migration must match history tail" release_validate "$tmp/last-mismatch.json"
write_v2 "$tmp/history-hash.json" "$HISTORY_2_10" 'V10__later' "$H0"
assert_fails "migration history checksum mismatch rejected" release_validate "$tmp/history-hash.json"
{ printf '{}\n'; cat "$V2"; } > "$tmp/prefixed-json.json"
assert_fails "prefixed JSON document rejected" release_validate "$tmp/prefixed-json.json"
{ cat "$V2"; printf '{}\n'; } > "$tmp/suffixed-json.json"
assert_fails "suffixed JSON document rejected" release_validate "$tmp/suffixed-json.json"

assert_command_empty "no successful deployment produces no latest evidence" \
  latest_successful_deployment '[{"status":"running","createdAt":"2026-07-13T12:00:00Z"}]'
DEPLOYMENTS='[
  {"deploymentId":"old","status":"DONE","title":"old","createdAt":"2026-07-13T12:00:00.900Z"},
  {"deploymentId":"new","status":"success","title":"new","createdAt":"2026-07-13T14:00:01+01:00"}
]'
latest=$(latest_successful_deployment "$DEPLOYMENTS")
assert_eq "latest successful deployment compares canonical UTC instants" "new" "$(jq -r .deploymentId <<< "$latest")"
AMBIGUOUS='[
  {"deploymentId":"one","status":"done","createdAt":"2026-07-13T12:00:00Z"},
  {"deploymentId":"two","status":"success","createdAt":"2026-07-13T14:00:00+02:00"}
]'
assert_fails "equal latest UTC instants are ambiguous" latest_successful_deployment "$AMBIGUOUS"
assert_fails "successful deployment requires timestamp" \
  latest_successful_deployment '[{"status":"done"}]'
assert_fails "timezone-free timestamp rejected" \
  latest_successful_deployment '[{"status":"done","createdAt":"2026-07-13T12:00:00"}]'
assert_fails "timestamp with trailing newline rejected" \
  latest_successful_deployment '[{"status":"done","createdAt":"2026-07-13T12:00:00Z\n"}]'
assert_fails "invalid deployment evidence rejected" latest_successful_deployment '{}'
assert_fails "non-object deployment evidence rejected" latest_successful_deployment '["bad"]'

RID="sha256:$H9"
FULL_TITLE="$RID|V15__current|$H8"
TRACKED_TITLE="$FULL_TITLE|$SOURCE_SHA"
TRACKED_DEPLOYMENTS=$(jq -cn --arg title "$TRACKED_TITLE" \
  '[{status:"done",title:$title,createdAt:"2026-07-13T12:00:00Z"}]')
FULL_DEPLOYMENTS=$(jq -cn --arg title "$FULL_TITLE" \
  '[{status:"done",title:$title,createdAt:"2026-07-13T12:00:00Z"}]')
current=$(current_release "$TRACKED_DEPLOYMENTS")
assert_eq "tracked release title parsed" "$RID" "$(jq -r .releaseId <<< "$current")"
assert_eq "tracked migration title parsed" "V15__current" "$(jq -r .migrationId <<< "$current")"
assert_eq "tracked history title parsed" "$H8" "$(jq -r .migrationHistorySha256 <<< "$current")"
assert_eq "tracked release source parsed" "$SOURCE_SHA" \
  "$(current_release_source "$TRACKED_DEPLOYMENTS")"
full=$(current_release "$FULL_DEPLOYMENTS")
assert_eq "pre-lineage full release title remains compatible" "$RID" "$(jq -r .releaseId <<< "$full")"
assert_command_empty "pre-lineage full title has no trusted source" \
  current_release_source "$FULL_DEPLOYMENTS"
legacy=$(current_release "[{\"status\":\"success\",\"title\":\"$RID|V15__current\",\"createdAt\":\"2026-07-13T12:00:00Z\"}]")
assert_eq "legacy title has null history" "null" "$(jq -r .migrationHistorySha256 <<< "$legacy")"
bare=$(current_release "[{\"status\":\"done\",\"title\":\"$RID\",\"createdAt\":\"2026-07-13T12:00:00Z\"}]")
assert_eq "bare title has null migration" "null" "$(jq -r .migrationId <<< "$bare")"
assert_command_empty "unknown latest successful title produces no current release" \
  current_release '[{"status":"done","title":"manual deployment","createdAt":"2026-07-13T12:00:00Z"}]'
assert_command_empty "release title with trailing newline is not normalized" \
  current_release "[{\"status\":\"done\",\"title\":\"$FULL_TITLE\\n\",\"createdAt\":\"2026-07-13T12:00:00Z\"}]"
assert_command_empty "tracked release source with trailing newline is not normalized" \
  current_release_source "[{\"status\":\"done\",\"title\":\"$TRACKED_TITLE\\n\",\"createdAt\":\"2026-07-13T12:00:00Z\"}]"
NEWER_UNIDENTIFIED=$(jq -cn --arg fullTitle "$FULL_TITLE" '[
  {status:"done",title:$fullTitle,createdAt:"2026-07-13T11:00:00Z"},
  {status:"done",title:"manual",createdAt:"2026-07-13T12:00:00Z"}
]')
assert_command_empty "newer unidentified success is not skipped" \
  current_release "$NEWER_UNIDENTIFIED"

MANUAL='[{"status":"done","title":"Manual deployment","createdAt":"2026-07-13T12:00:00Z"}]'
assert_ok "exact manual sentinel accepted" require_manual_current "$MANUAL"
assert_fails "manual sentinel is case-sensitive" \
  require_manual_current '[{"status":"done","title":"manual deployment","createdAt":"2026-07-13T12:00:00Z"}]'
assert_fails "manual sentinel with trailing newline rejected" \
  require_manual_current '[{"status":"done","title":"Manual deployment\n","createdAt":"2026-07-13T12:00:00Z"}]'
assert_fails "manual current requires a successful deployment" require_manual_current '[]'

assert_ok "missing current release is compatible" require_migration_compatibility '' "$V2" false
assert_ok "identical release is compatible" require_migration_compatibility \
  "$(current_bare "$(release_id "$V2")")" "$V2" false
assert_fails "missing migration identity requires attestation" require_migration_compatibility \
  "$(current_bare "$RID")" "$V2" false
assert_ok "missing migration identity accepts attestation" require_migration_compatibility \
  "$(current_bare "$RID")" "$V2" true

ROLLBACK_HISTORY=$(jq -cn --arg h7 "$H7" '[{id:"V1__initial",sha256:$h7}]')
ROLLBACK="$tmp/rollback.json"
write_v2 "$ROLLBACK" "$ROLLBACK_HISTORY"
CURRENT_HISTORY=$(jq -cn --arg h7 "$H7" --arg h8 "$H8" \
  '[{id:"V1__initial",sha256:$h7},{id:"V2__current",sha256:$h8}]')
CURRENT_FULL=$(current_full "$RID" 'V2__current' "$(history_hash "$CURRENT_HISTORY")")
assert_fails "rollback requires attestation" require_migration_compatibility "$CURRENT_FULL" "$ROLLBACK" false
assert_ok "rollback accepts attestation" require_migration_compatibility "$CURRENT_FULL" "$ROLLBACK" true

RENAMED_HISTORY=$(jq -cn --arg h7 "$H7" --arg h8 "$H8" \
  '[{id:"V1__initial",sha256:$h7},{id:"V2__renamed",sha256:$h8}]')
RENAMED="$tmp/renamed.json"
write_v2 "$RENAMED" "$RENAMED_HISTORY"
assert_fails "same-version rewrite is always rejected" \
  require_migration_compatibility "$CURRENT_FULL" "$RENAMED" true

FORWARD_HISTORY=$(jq -cn --arg h7 "$H7" --arg h8 "$H8" --arg h9 "$H9" \
  '[{id:"V1__initial",sha256:$h7},{id:"V2__current",sha256:$h8},{id:"V3__next",sha256:$h9}]')
FORWARD="$tmp/forward.json"
write_v2 "$FORWARD" "$FORWARD_HISTORY"
assert_ok "forward migration accepts unchanged history prefix" \
  require_migration_compatibility "$CURRENT_FULL" "$FORWARD" false
FORWARD_V3="$tmp/forward-v3.json"
write_v3 "$FORWARD_V3" "$FORWARD_HISTORY"
assert_ok "schema 3 forward migration accepts unchanged history prefix" \
  require_migration_compatibility "$CURRENT_FULL" "$FORWARD_V3" false

REWRITTEN_HISTORY=$(jq -cn --arg h0 "$H0" --arg h8 "$H8" --arg h9 "$H9" \
  '[{id:"V1__initial",sha256:$h0},{id:"V2__current",sha256:$h8},{id:"V3__next",sha256:$h9}]')
REWRITTEN="$tmp/rewritten.json"
write_v2 "$REWRITTEN" "$REWRITTEN_HISTORY"
assert_fails "forward rewritten prefix rejected despite attestation" \
  require_migration_compatibility "$CURRENT_FULL" "$REWRITTEN" true

LEGACY_CURRENT=$(current_legacy "$RID" 'V2__current')
assert_fails "legacy current history requires attestation" \
  require_migration_compatibility "$LEGACY_CURRENT" "$FORWARD" false
assert_ok "legacy current history accepts attestation" \
  require_migration_compatibility "$LEGACY_CURRENT" "$FORWARD" true

V1_FORWARD="$tmp/v1-forward.json"
write_v1 "$V1_FORWARD" 'V3__next'
assert_fails "forward candidate without full history rejected" \
  require_migration_compatibility "$CURRENT_FULL" "$V1_FORWARD" false
assert_fails "invalid current release evidence rejected" \
  require_migration_compatibility '{}' "$FORWARD" false
BAD_CURRENT=$(current_bare "$RID")
BAD_CURRENT=$(jq -c --arg value "$RID"$'\n' '.releaseId = $value' <<< "$BAD_CURRENT")
assert_fails "current release ID with trailing newline rejected" \
  require_migration_compatibility "$BAD_CURRENT" "$FORWARD" false
assert_fails "attestation must be an exact boolean word" \
  require_migration_compatibility "$CURRENT_FULL" "$FORWARD" yes

CURRENT_RELEASE="sha256:$H1"
CANDIDATE_RELEASE="sha256:$H2"
assert_ok "ahead source accepts a new release for staging" \
  require_deployment_progress "$CURRENT_RELEASE" "$CANDIDATE_RELEASE" ahead staging false
assert_ok "ahead source accepts a new release for production" \
  require_deployment_progress "$CURRENT_RELEASE" "$CANDIDATE_RELEASE" ahead production false
assert_ok "identical source and release is idempotent" \
  require_deployment_progress "$CURRENT_RELEASE" "$CURRENT_RELEASE" identical staging false
assert_fails "same-source release replacement is rejected for staging" \
  require_deployment_progress "$CURRENT_RELEASE" "$CANDIDATE_RELEASE" identical staging true
assert_fails "same-source release replacement requires production attestation" \
  require_deployment_progress "$CURRENT_RELEASE" "$CANDIDATE_RELEASE" identical production false
assert_ok "same-source release replacement accepts production attestation" \
  require_deployment_progress "$CURRENT_RELEASE" "$CANDIDATE_RELEASE" identical production true
assert_fails "staging rollback is rejected despite attestation" \
  require_deployment_progress "$CURRENT_RELEASE" "$CANDIDATE_RELEASE" behind staging true
assert_fails "production rollback requires attestation" \
  require_deployment_progress "$CURRENT_RELEASE" "$CANDIDATE_RELEASE" behind production false
assert_ok "production rollback accepts attestation" \
  require_deployment_progress "$CURRENT_RELEASE" "$CANDIDATE_RELEASE" behind production true
assert_fails "diverged source is always rejected" \
  require_deployment_progress "$CURRENT_RELEASE" "$CANDIDATE_RELEASE" diverged production true
assert_fails "unknown source progress is rejected" \
  require_deployment_progress "$CURRENT_RELEASE" "$CANDIDATE_RELEASE" sideways production true
assert_fails "invalid release ID is rejected" \
  require_deployment_progress sha256:bad "$CANDIDATE_RELEASE" ahead staging false
assert_fails "deployment attestation must be an exact boolean word" \
  require_deployment_progress "$CURRENT_RELEASE" "$CANDIDATE_RELEASE" ahead staging yes

HUGE_PREVIOUS='V999999999999999999999__previous'
HUGE_CANDIDATE='V1000000000000000000000__candidate'
HUGE_HISTORY=$(jq -cn --arg previous "$HUGE_PREVIOUS" --arg candidate "$HUGE_CANDIDATE" \
  --arg h7 "$H7" --arg h8 "$H8" '[{id:$previous,sha256:$h7},{id:$candidate,sha256:$h8}]')
HUGE="$tmp/huge.json"
write_v2 "$HUGE" "$HUGE_HISTORY"
HUGE_PREFIX=$(jq -cn --arg previous "$HUGE_PREVIOUS" --arg h7 "$H7" '[{id:$previous,sha256:$h7}]')
assert_ok "arbitrary-length migration versions do not overflow" require_migration_compatibility \
  "$(current_full "$RID" "$HUGE_PREVIOUS" "$(history_hash "$HUGE_PREFIX")")" "$HUGE" false

printf 'release client validated (%s assertions)\n' "$tests"
