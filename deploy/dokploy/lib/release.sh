#!/usr/bin/env bash

_release_lib_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# Resolved from this module's BASH_SOURCE at runtime.
# shellcheck disable=SC1091
source "$_release_lib_dir/common.sh"
unset _release_lib_dir

release_validate() {
  local file=${1:-}
  local schema key value history expected_hash actual_hash

  if [[ $# -ne 1 || ! -r $file ]]; then
    die "readable release file required"
    return 1
  fi
  require_command jq || return 1

  if ! jq -e -s 'length == 1' "$file" >/dev/null; then
    die "release file must contain exactly one JSON value"
    return 1
  fi

  if ! jq -e '
    def v1_keys: [
      "backupImage", "manifestSha256", "migrationId", "postgresImage",
      "schema", "serverImage", "siteImage", "sourceCommit", "volumeGuardSha256"
    ];
    def v2_keys: [
      "backupImage", "manifestSha256", "migrationHistory", "migrationHistorySha256",
      "migrationId", "postgresImage", "schema", "serverImage", "siteImage",
      "sourceCommit", "volumeGuardSha256"
    ];
    def v3_keys: [
      "backupImage", "migrationHistory", "migrationHistorySha256", "migrationId",
      "postgresImage", "productionManifestSha256", "schema", "serverImage",
      "siteImage", "sourceCommit", "stagingManifestSha256", "volumeGuardSha256"
    ];
    . as $root
      | type == "object"
      and (.schema == 1 or .schema == 2 or .schema == 3)
      and (keys == (if .schema == 1 then v1_keys elif .schema == 2 then v2_keys else v3_keys end))
      and (.sourceCommit | type == "string" and test("^[0-9a-f]{40}\\z"))
      and all(["serverImage", "siteImage", "backupImage", "postgresImage"][];
        . as $key
        | ($root[$key] | type == "string"
           and test("^[a-z0-9./_-]+(:[a-z0-9._-]+)?@sha256:[0-9a-f]{64}\\z"))
      )
      and (if .schema < 3 then
        (.manifestSha256 | type == "string" and test("^[0-9a-f]{64}\\z"))
      else
        (.productionManifestSha256 | type == "string" and test("^[0-9a-f]{64}\\z")) and
        (.stagingManifestSha256 | type == "string" and test("^[0-9a-f]{64}\\z"))
      end)
      and (.volumeGuardSha256 | type == "string" and test("^[0-9a-f]{64}\\z"))
      and (.migrationId | type == "string" and test("^V[0-9]+__[A-Za-z0-9_.-]+\\z"))
  ' "$file" >/dev/null; then
    die "invalid release schema or source commit"
    return 1
  fi

  schema=$(jq -er '.schema' "$file") || return 1
  for key in serverImage siteImage backupImage postgresImage; do
    value=$(jq -er --arg key "$key" '.[$key] | select(type == "string")' "$file") || {
      die "$key is not digest-addressed"
      return 1
    }
    if ! is_digest_reference "$value"; then
      die "$key is not digest-addressed"
      return 1
    fi
  done

  if [[ $schema -lt 3 ]]; then
    value=$(jq -er '.manifestSha256 | select(type == "string")' "$file") || true
    if ! is_sha256 "$value"; then
      die "invalid manifest checksum"
      return 1
    fi
  else
    for key in productionManifestSha256 stagingManifestSha256; do
      value=$(jq -er --arg key "$key" '.[$key] | select(type == "string")' "$file") || true
      if ! is_sha256 "$value"; then
        die "invalid target manifest checksum"
        return 1
      fi
    done
  fi
  value=$(jq -er '.volumeGuardSha256 | select(type == "string")' "$file") || true
  if ! is_sha256 "$value"; then
    die "invalid volume guard checksum"
    return 1
  fi
  value=$(jq -er '.migrationId | select(type == "string")' "$file") || true
  if ! is_migration_id "$value"; then
    die "invalid migration identity"
    return 1
  fi

  if [[ $schema -ge 2 ]]; then
    if ! jq -e '
      def normalized_version:
        capture("^V(?<version>[0-9]+)__[A-Za-z0-9_.-]+\\z").version
        | sub("^0+"; "")
        | if length == 0 then "0" else . end;
      def decimal_lt($left; $right):
        (($left | length) < ($right | length))
        or ((($left | length) == ($right | length)) and ($left < $right));
      . as $root
      | ($root.migrationHistory | type == "array" and length > 0)
        and all($root.migrationHistory[];
          type == "object"
          and keys == ["id", "sha256"]
          and (.id | type == "string" and test("^V[0-9]+__[A-Za-z0-9_.-]+\\z"))
          and (.sha256 | type == "string" and test("^[0-9a-f]{64}\\z"))
        )
        and ($root.migrationHistory[-1].id == $root.migrationId)
        and (
          ($root.migrationHistory | length) as $length
          | [range(1; $length)
              | . as $index
              | ($root.migrationHistory[$index - 1].id | normalized_version) as $previous
              | ($root.migrationHistory[$index].id | normalized_version) as $candidate
              | decimal_lt($previous; $candidate)
            ]
          | all
        )
    ' "$file" >/dev/null; then
      die "invalid or unordered migration history"
      return 1
    fi

    history=$(jq -cS '.migrationHistory' "$file") || return 1
    actual_hash=$(printf '%s\n' "$history" | sha256_stdin) || return 1
    expected_hash=$(jq -er '.migrationHistorySha256 | select(type == "string")' "$file") || true
    if ! is_sha256 "$expected_hash" || [[ $expected_hash != "$actual_hash" ]]; then
      die "migration history checksum differs from ordered migration set"
      return 1
    fi
  fi
}

release_id() {
  local file=${1:-}
  local canonical digest
  if [[ $# -ne 1 ]]; then
    die "release_id expects one release file"
    return 1
  fi
  release_validate "$file" || return 1
  canonical=$(canonical_json "$file") || return 1
  digest=$(printf '%s\n' "$canonical" | sha256_stdin) || return 1
  printf 'sha256:%s\n' "$digest"
}

release_title() {
  local file=${1:-}
  local schema id migration history_hash source_commit
  if [[ $# -ne 1 ]]; then
    die "release_title expects one release file"
    return 1
  fi
  release_validate "$file" || return 1
  schema=$(jq -er '.schema' "$file") || return 1
  id=$(release_id "$file") || return 1
  migration=$(jq -er '.migrationId' "$file") || return 1
  if [[ $schema -ge 2 ]]; then
    history_hash=$(jq -er '.migrationHistorySha256' "$file") || return 1
    source_commit=$(jq -er '.sourceCommit' "$file") || return 1
    printf '%s|%s|%s|%s\n' "$id" "$migration" "$history_hash" "$source_commit"
  else
    printf '%s|%s\n' "$id" "$migration"
  fi
}

_canonical_utc_created_at() {
  local value=${1:-}
  local pattern='^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(\.([0-9]+))?(Z|([+-])([0-9]{2}):([0-9]{2}))$'
  local year month day hour minute second fraction zone_sign offset_hour offset_minute
  local prior_year days month_days offset_seconds sortable_seconds
  local -a days_before_month=(0 0 31 59 90 120 151 181 212 243 273 304 334)

  if [[ ! $value =~ $pattern ]]; then
    return 1
  fi
  year=$((10#${BASH_REMATCH[1]}))
  month=$((10#${BASH_REMATCH[2]}))
  day=$((10#${BASH_REMATCH[3]}))
  hour=$((10#${BASH_REMATCH[4]}))
  minute=$((10#${BASH_REMATCH[5]}))
  second=$((10#${BASH_REMATCH[6]}))
  fraction=${BASH_REMATCH[8]:-0}
  zone_sign=${BASH_REMATCH[10]:-}
  offset_hour=$((10#${BASH_REMATCH[11]:-0}))
  offset_minute=$((10#${BASH_REMATCH[12]:-0}))

  if [[ $year -lt 1 || $month -lt 1 || $month -gt 12 || $hour -gt 23 || $minute -gt 59 || $second -gt 59 || $offset_hour -gt 23 || $offset_minute -gt 59 ]]; then
    return 1
  fi
  month_days=31
  case $month in
    4|6|9|11) month_days=30 ;;
    2)
      month_days=28
      if (( year % 400 == 0 || (year % 4 == 0 && year % 100 != 0) )); then
        month_days=29
      fi
      ;;
  esac
  if [[ $day -lt 1 || $day -gt $month_days ]]; then
    return 1
  fi

  prior_year=$((year - 1))
  days=$((365 * prior_year + prior_year / 4 - prior_year / 100 + prior_year / 400))
  days=$((days + days_before_month[month] + day - 1))
  if (( month > 2 && (year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)) )); then
    days=$((days + 1))
  fi
  offset_seconds=$((offset_hour * 3600 + offset_minute * 60))
  if [[ $zone_sign == - ]]; then
    offset_seconds=$((-offset_seconds))
  fi
  # Offset by one day so even 0001-01-01T00:00:00+23:59 stays positive;
  # fixed-width output can then be compared lexicographically under LC_ALL=C.
  sortable_seconds=$((days * 86400 + hour * 3600 + minute * 60 + second - offset_seconds + 86400))
  fraction=${fraction}000000
  fraction=${fraction:0:6}
  printf '%012d.%06d\n' "$sortable_seconds" "$((10#$fraction))"
}

latest_successful_deployment() {
  local deployments=${1:-}
  local item created timestamp latest_item='' latest_timestamp='' latest_count=0
  local successful count index

  if [[ $# -ne 1 ]] || ! jq -e 'type == "array" and all(.[]; type == "object")' <<< "$deployments" >/dev/null; then
    die "invalid deployment evidence"
    return 1
  fi
  successful=$(jq -ce '[.[]
      | ((.status // "") | tostring | ascii_downcase) as $status
      | select($status == "done" or $status == "success")]' <<< "$deployments") || {
    die "invalid deployment evidence"
    return 1
  }
  count=$(jq -er 'length' <<< "$successful") || return 1
  if [[ $count -eq 0 ]]; then
    return 0
  fi

  for ((index = 0; index < count; index++)); do
    item=$(jq -ce --argjson index "$index" '.[$index]' <<< "$successful") || return 1
    if ! jq -e '
      .createdAt
      | type == "string"
        and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})\\z")
    ' <<< "$item" >/dev/null; then
      die "successful deployment is missing its creation timestamp"
      return 1
    fi
    created=$(jq -er '.createdAt | select(type == "string")' <<< "$item") || {
      die "successful deployment is missing its creation timestamp"
      return 1
    }
    timestamp=$(_canonical_utc_created_at "$created") || {
      die "successful deployment has an invalid or timezone-free creation timestamp"
      return 1
    }
    if [[ -z $latest_timestamp || $timestamp > $latest_timestamp ]]; then
      latest_timestamp=$timestamp
      latest_item=$item
      latest_count=1
    elif [[ $timestamp == "$latest_timestamp" ]]; then
      latest_count=$((latest_count + 1))
    fi
  done

  if [[ $latest_count -ne 1 ]]; then
    die "latest successful deployment is ambiguous"
    return 1
  fi
  jq -c . <<< "$latest_item"
}

current_release() {
  local deployments=${1:-}
  local latest title
  local tracked_pattern='^(sha256:[0-9a-f]{64})\|(V[0-9]+__[A-Za-z0-9_.-]+)\|([0-9a-f]{64})\|([0-9a-f]{40})$'
  local full_pattern='^(sha256:[0-9a-f]{64})\|(V[0-9]+__[A-Za-z0-9_.-]+)\|([0-9a-f]{64})$'
  local legacy_pattern='^(sha256:[0-9a-f]{64})\|(V[0-9]+__[A-Za-z0-9_.-]+)$'
  local bare_pattern='^(sha256:[0-9a-f]{64})$'

  if [[ $# -ne 1 ]]; then
    die "current_release expects deployment JSON"
    return 1
  fi
  latest=$(latest_successful_deployment "$deployments") || return 1
  if [[ -z $latest ]]; then
    return 0
  fi
  if ! jq -e '
    (.title | type) == "string" and
    (
      (.title | test("^sha256:[0-9a-f]{64}\\|V[0-9]+__[A-Za-z0-9_.-]+\\|[0-9a-f]{64}\\|[0-9a-f]{40}\\z")) or
      (.title | test("^sha256:[0-9a-f]{64}\\|V[0-9]+__[A-Za-z0-9_.-]+\\|[0-9a-f]{64}\\z")) or
      (.title | test("^sha256:[0-9a-f]{64}\\|V[0-9]+__[A-Za-z0-9_.-]+\\z")) or
      (.title | test("^sha256:[0-9a-f]{64}\\z"))
    )
  ' <<< "$latest" >/dev/null; then
    return 0
  fi
  title=$(jq -r '.title | if type == "string" then . else empty end' <<< "$latest") || return 1

  if [[ $title =~ $tracked_pattern ]]; then
    jq -cn \
      --arg releaseId "${BASH_REMATCH[1]}" \
      --arg migrationId "${BASH_REMATCH[2]}" \
      --arg migrationHistorySha256 "${BASH_REMATCH[3]}" \
      '{releaseId:$releaseId,migrationId:$migrationId,migrationHistorySha256:$migrationHistorySha256}'
  elif [[ $title =~ $full_pattern ]]; then
    jq -cn \
      --arg releaseId "${BASH_REMATCH[1]}" \
      --arg migrationId "${BASH_REMATCH[2]}" \
      --arg migrationHistorySha256 "${BASH_REMATCH[3]}" \
      '{releaseId:$releaseId,migrationId:$migrationId,migrationHistorySha256:$migrationHistorySha256}'
  elif [[ $title =~ $legacy_pattern ]]; then
    jq -cn \
      --arg releaseId "${BASH_REMATCH[1]}" \
      --arg migrationId "${BASH_REMATCH[2]}" \
      '{releaseId:$releaseId,migrationId:$migrationId,migrationHistorySha256:null}'
  elif [[ $title =~ $bare_pattern ]]; then
    jq -cn \
      --arg releaseId "${BASH_REMATCH[1]}" \
      '{releaseId:$releaseId,migrationId:null,migrationHistorySha256:null}'
  fi
}

current_release_source() {
  local deployments=${1:-}
  local latest title
  local tracked_pattern='^sha256:[0-9a-f]{64}\|V[0-9]+__[A-Za-z0-9_.-]+\|[0-9a-f]{64}\|([0-9a-f]{40})$'

  if [[ $# -ne 1 ]]; then
    die "current_release_source expects deployment JSON"
    return 1
  fi
  latest=$(latest_successful_deployment "$deployments") || return 1
  if [[ -z $latest ]]; then
    return 0
  fi
  if ! jq -e '
    (.title | type) == "string" and
    (.title | test("^sha256:[0-9a-f]{64}\\|V[0-9]+__[A-Za-z0-9_.-]+\\|[0-9a-f]{64}\\|[0-9a-f]{40}\\z"))
  ' <<< "$latest" >/dev/null; then
    return 0
  fi
  title=$(jq -r '.title | if type == "string" then . else empty end' <<< "$latest") || return 1
  if [[ $title =~ $tracked_pattern ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  fi
}

# True (exit 0) when any successful deployment in the history carries a
# release-shaped title, regardless of recency. Used to keep the automatic
# staging bootstrap one-shot: a manual deployment that merely tops an
# existing release history must not re-open the bootstrap path.
has_successful_release_deployment() {
  local deployments=${1:-}
  if [[ $# -ne 1 ]] || ! jq -e 'type == "array" and all(.[]; type == "object")' <<< "$deployments" >/dev/null; then
    die "invalid deployment evidence"
    return 1
  fi
  jq -e '
    any(.[];
      (((.status // "") | tostring | ascii_downcase) as $status
        | $status == "done" or $status == "success") and
      ((.title // null) | type) == "string" and
      (.title | test("^sha256:[0-9a-f]{64}(\\|V[0-9]+__[A-Za-z0-9_.-]+(\\|[0-9a-f]{64}(\\|[0-9a-f]{40})?)?)?\\z"))
    )
  ' <<< "$deployments" >/dev/null
}

require_manual_current() {
  local deployments=${1:-}
  local latest
  if [[ $# -ne 1 ]]; then
    die "require_manual_current expects deployment JSON"
    return 1
  fi
  latest=$(latest_successful_deployment "$deployments") || return 1
  if [[ -z $latest ]]; then
    die "no current successful deployment found"
    return 1
  fi
  if ! jq -e '.title == "Manual deployment"' <<< "$latest" >/dev/null; then
    die "latest successful deployment is not the explicit manual deployment"
    return 1
  fi
}

_normalized_decimal() {
  local value=$1
  while [[ ${#value} -gt 1 && ${value:0:1} == 0 ]]; do
    value=${value:1}
  done
  printf '%s\n' "$value"
}

_migration_version() {
  local migration=$1
  if [[ ! $migration =~ ^V([0-9]+)__[A-Za-z0-9_.-]+$ ]]; then
    return 1
  fi
  _normalized_decimal "${BASH_REMATCH[1]}"
}

_decimal_compare() {
  local left right
  left=$(_normalized_decimal "$1") || return 1
  right=$(_normalized_decimal "$2") || return 1
  if [[ ${#left} -lt ${#right} ]]; then
    printf '%s\n' -1
  elif [[ ${#left} -gt ${#right} ]]; then
    printf '%s\n' 1
  elif [[ $left == "$right" ]]; then
    printf '%s\n' 0
  elif [[ $left < $right ]]; then
    printf '%s\n' -1
  else
    printf '%s\n' 1
  fi
}

require_migration_compatibility() {
  local current=${1-}
  local release_file=${2:-}
  local attested=${3:-}
  local candidate_release_id current_release_id previous candidate
  local previous_version candidate_version comparison previous_history schema prefix prefix_hash

  if [[ $# -ne 3 || ( $attested != true && $attested != false ) ]]; then
    die "migration compatibility expects current JSON, release file, and true/false attestation"
    return 1
  fi
  release_validate "$release_file" || return 1
  if [[ -z $current ]]; then
    return 0
  fi
  if ! jq -e '
    type == "object"
      and keys == ["migrationHistorySha256", "migrationId", "releaseId"]
      and (.releaseId | type == "string" and test("^sha256:[0-9a-f]{64}\\z"))
      and (
        (.migrationId == null and .migrationHistorySha256 == null)
        or (
          (.migrationId | type == "string" and test("^V[0-9]+__[A-Za-z0-9_.-]+\\z"))
          and (
            .migrationHistorySha256 == null
            or (.migrationHistorySha256 | type == "string" and test("^[0-9a-f]{64}\\z"))
          )
        )
      )
  ' <<< "$current" >/dev/null; then
    die "invalid current release evidence"
    return 1
  fi

  candidate_release_id=$(release_id "$release_file") || return 1
  current_release_id=$(jq -er '.releaseId' <<< "$current") || return 1
  if [[ $current_release_id == "$candidate_release_id" ]]; then
    return 0
  fi

  previous=$(jq -r '.migrationId // empty' <<< "$current") || return 1
  candidate=$(jq -er '.migrationId' "$release_file") || return 1
  if [[ -z $previous ]]; then
    if [[ $attested != true ]]; then
      die "current deployment lacks migration identity; compatibility attestation required"
      return 1
    fi
    return 0
  fi

  previous_version=$(_migration_version "$previous") || return 1
  candidate_version=$(_migration_version "$candidate") || return 1
  comparison=$(_decimal_compare "$candidate_version" "$previous_version") || return 1
  if [[ $comparison == 0 && $candidate != "$previous" ]]; then
    die "migration history was rewritten at the current version"
    return 1
  fi
  if [[ $comparison == -1 ]]; then
    if [[ $attested != true ]]; then
      die "rollback migration compatibility attestation required"
      return 1
    fi
    return 0
  fi

  previous_history=$(jq -r '.migrationHistorySha256 // empty' <<< "$current") || return 1
  if [[ -z $previous_history ]]; then
    if [[ $attested != true ]]; then
      die "current deployment lacks full migration history; compatibility attestation required"
      return 1
    fi
    return 0
  fi

  schema=$(jq -er '.schema' "$release_file") || return 1
  if [[ $schema -lt 2 ]]; then
    die "candidate release lacks full migration history"
    return 1
  fi
  prefix=$(jq -cS --arg previous "$previous" '
    .migrationHistory as $history
    | ($history | map(.id == $previous) | index(true)) as $index
    | if $index == null then empty else $history[0:($index + 1)] end
  ' "$release_file") || return 1
  if [[ -z $prefix ]]; then
    die "migration history before the candidate was rewritten"
    return 1
  fi
  prefix_hash=$(printf '%s\n' "$prefix" | sha256_stdin) || return 1
  if [[ $prefix_hash != "$previous_history" ]]; then
    die "migration history before the candidate was rewritten"
    return 1
  fi
}

require_deployment_progress() {
  local current_release_id=${1-}
  local candidate_release_id=${2-}
  local progress=${3-}
  local target=${4-}
  local attested=${5-}

  if [[ $# -ne 5 || ( $target != staging && $target != production ) ||
        ( $attested != true && $attested != false ) ]]; then
    die "deployment progress expects current and candidate release IDs, progress, target, and true/false attestation"
    return 1
  fi
  if [[ $current_release_id != sha256:* ]] ||
     ! is_sha256 "${current_release_id#sha256:}" ||
     [[ $candidate_release_id != sha256:* ]] ||
     ! is_sha256 "${candidate_release_id#sha256:}"; then
    die "deployment progress requires valid release IDs"
    return 1
  fi

  case "$progress" in
    ahead)
      return 0
      ;;
    identical)
      if [[ $current_release_id == "$candidate_release_id" ]]; then
        return 0
      fi
      if [[ $target == production && $attested == true ]]; then
        return 0
      fi
      die "$target candidate changes the release at an identical source; production rollback attestation required"
      return 1
      ;;
    behind)
      if [[ $target == production && $attested == true ]]; then
        return 0
      fi
      die "$target candidate would move backward from the deployed source"
      return 1
      ;;
    diverged)
      die "$target candidate is not on the deployed source lineage"
      return 1
      ;;
    *)
      die "invalid deployment source progress"
      return 1
      ;;
  esac
}
