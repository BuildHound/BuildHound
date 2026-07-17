#!/usr/bin/env bash
# Review-environment policy and lifecycle for the trusted Dokploy client.
{ set +x; } 2>/dev/null

_review_lib_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "$_review_lib_dir/common.sh"
# shellcheck disable=SC1091
source "$_review_lib_dir/api.sh"
_review_stack_template=$_review_lib_dir/../review-stack.yaml
_review_anchor_template=$_review_lib_dir/../review-anchor.yaml
_review_anchor_image=timescale/timescaledb:latest-pg16@sha256:ba149561ad4ddff5940d6eb0a0df60aefd1355cee1a450928f271267038fc888
_review_supported_dokploy_version=v0.29.12
readonly _review_lib_dir _review_stack_template _review_anchor_template _review_anchor_image
readonly _review_supported_dokploy_version

_review_require_supported_dokploy_version() {
  local response
  response=$(dokploy_api GET settings.getDokployVersion) || return 1
  if ! jq -e --arg expected "$_review_supported_dokploy_version" \
      'type == "string" and . == $expected' <<< "$response" >/dev/null; then
    die "review lifecycle requires Dokploy $_review_supported_dokploy_version"
    return 1
  fi
}

_review_valid_positive_integer() {
  local value=${1-}
  [[ $value =~ ^[1-9][0-9]*$ && ${#value} -le 10 ]] || return 1
  ((10#$value <= 2147483647))
}

_review_valid_repository() {
  [[ ${1-} =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]
}

_review_valid_sha() {
  [[ ${1-} =~ ^[0-9a-f]{40}$ ]]
}

_review_valid_attempt_id() {
  [[ ${1-} =~ ^[1-9][0-9]{0,19}\.[1-9][0-9]{0,9}$ ]]
}

_review_valid_digest() {
  [[ ${1-} =~ ^[a-z0-9./_-]+(:[a-z0-9._-]+)?@sha256:[0-9a-f]{64}$ ]]
}

_review_valid_object_id() {
  [[ ${1-} =~ ^[A-Za-z0-9_-]{1,128}$ ]]
}

_review_valid_secret() {
  [[ ${1-} =~ ^[0-9a-f]{64}$ ]]
}

_review_valid_dns_label() {
  local value=${1-}
  [[ $value =~ ^[a-z0-9]$ || $value =~ ^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$ ]]
}

_review_validate_dns_name() {
  local value=${1-} rest label labels=0
  [[ -n $value && ${#value} -le 253 && $value != *[!a-z0-9.-]* ]] || return 1
  rest=$value
  while [[ $rest == *.* ]]; do
    label=${rest%%.*}
    _review_valid_dns_label "$label" || return 1
    labels=$((labels + 1))
    rest=${rest#*.}
  done
  _review_valid_dns_label "$rest" || return 1
  labels=$((labels + 1))
  [[ $labels -ge 2 ]]
}

review_name() {
  local number=${1-}
  if ! _review_valid_positive_integer "$number"; then
    die "positive PR number required"
    return 1
  fi
  printf 'mr%s\n' "$number"
}

review_provider_id() {
  local repository=${1-} number=${2-} lower digest name identifier
  if ! _review_valid_repository "$repository"; then
    die "valid repository required"
    return 1
  fi
  name=$(review_name "$number") || return 1
  lower=$(printf '%s' "$repository" | tr '[:upper:]' '[:lower:]') || return 1
  digest=$(printf '%s' "$lower" | sha256_stdin) || return 1
  identifier=bh-${digest:0:24}-$name
  if [[ ${#identifier} -gt 63 ]]; then
    die "derived review provider ID is invalid"
    return 1
  fi
  printf '%s\n' "$identifier"
}

# Prints SITE_HOST|DASHBOARD_HOST.
review_hosts() {
  local name=${1-} suffix=${2-} site dashboard
  if ! _review_valid_dns_label "$name" || ! _review_validate_dns_name "$suffix"; then
    die "invalid review DNS suffix"
    return 1
  fi
  site=$name.$suffix
  dashboard=$name.dashboard.$suffix
  if ! _review_validate_dns_name "$site" || ! _review_validate_dns_name "$dashboard"; then
    die "derived review host is invalid"
    return 1
  fi
  printf '%s|%s\n' "$site" "$dashboard"
}

_review_environment_shape() {
  jq -e '
    type == "object" and
    (.compose | type == "array") and
    all(.compose[]; type == "object")
  ' >/dev/null
}

# Strictly parses ownership metadata for every Compose, then returns exact-owned items.
_review_owned_items() {
  local environment=${1-} name=${2-} repository=${3-} pr=${4-} sha=${5-} attempt_id=${6-}
  jq -cer --arg name "$name" --arg repository "$repository" \
    --argjson pr "$pr" --arg sha "$sha" --arg attemptId "$attempt_id" '
    def review_metadata:
      (.description // "{}") as $description |
      (if ($description | type) == "string"
       then (try ($description | fromjson) catch null)
      else null end) as $metadata |
      if (($metadata | type) == "object") and
         (($metadata | has("repository")) and ($metadata | has("pr")) and
          ($metadata | has("sha"))) and
         (($metadata | keys) - ["activatedAt", "attemptId", "pr", "repository", "retired", "retiredAt", "sha"] == []) and
         (($metadata.repository | type) == "string") and
         ($metadata.repository | test("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\\z")) and
         (($metadata.pr | type) == "number") and
         ($metadata.pr > 0) and ($metadata.pr == ($metadata.pr | floor)) and
         (($metadata.sha | type) == "string") and
         ($metadata.sha | test("^[0-9a-f]{40}\\z")) and
         ((($metadata | has("retired")) | not) or
          (($metadata.retired | type) == "boolean")) and
         ((($metadata | has("activatedAt")) | not) or
          (($metadata.activatedAt | type) == "string" and
           ($metadata.activatedAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\\z")))) and
         ((($metadata | has("retiredAt")) | not) or
          (($metadata.retiredAt | type) == "string" and
           ($metadata.retiredAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\\z")))) and
         ((($metadata | has("attemptId")) | not) or
          (($metadata.attemptId | type) == "string" and
           ($metadata.attemptId | test("^[1-9][0-9]{0,19}\\.[1-9][0-9]{0,9}\\z"))))
      then $metadata + {
        activatedAt:($metadata.activatedAt // null),
        attemptId:($metadata.attemptId // null),
        retired:($metadata.retired // false)
      }
      else error("review ownership metadata is invalid")
      end;
    if type != "object" or (.compose | type) != "array" or
       any(.compose[]; type != "object")
    then error("Dokploy returned an invalid review environment")
    else [
      .compose[] as $item |
      ($item | review_metadata) as $metadata |
      select($item.name == $name and
             $metadata.repository == $repository and
             $metadata.pr == $pr and
             ($sha == "" or $metadata.sha == $sha) and
             ($attemptId == "" or $metadata.attemptId == $attemptId)) |
      $item
    ]
    end
  ' <<< "$environment"
}

# Dokploy v0.29.12 persists the isolation toggle through compose.update.
# compose.isolatedDeployment only renders a preview and must not be used here.
_review_update_body() {
  local compose_id=$1 description=$2 compose_file=$3 output=$4
  jq -cn --arg composeId "$compose_id" --arg description "$description" \
    --rawfile composeFile "$compose_file" '
    {
      composeId: $composeId,
      description: $description,
      composeType: "stack",
      composeFile: $composeFile,
      sourceType: "raw",
      command: "",
      env: "",
      autoDeploy: false,
      enableSubmodules: false,
      composePath: "./docker-compose.yml",
      suffix: "",
      randomize: false,
      isolatedDeployment: true,
      isolatedDeploymentsVolume: false,
      triggerType: "push",
      watchPaths: [],
      repository: null,
      owner: null,
      branch: null,
      githubId: null,
      gitlabProjectId: null,
      gitlabRepository: null,
      gitlabOwner: null,
      gitlabBranch: null,
      gitlabPathNamespace: null,
      gitlabId: null,
      bitbucketRepository: null,
      bitbucketRepositorySlug: null,
      bitbucketOwner: null,
      bitbucketBranch: null,
      bitbucketId: null,
      giteaRepository: null,
      giteaOwner: null,
      giteaBranch: null,
      giteaId: null,
      customGitUrl: null,
      customGitBranch: null,
      customGitSSHKeyId: null
    }
  ' > "$output"
}

_review_require_exact_compose() {
  local persisted_file=$1 expected_file=$2 environment_id=$3 name=$4 provider_id=$5
  if ! jq -e --slurpfile expected "$expected_file" \
    --arg environmentId "$environment_id" --arg name "$name" --arg prefix "$provider_id" '
    . as $actual |
    $expected[0] as $wanted |
    (type == "object") and
    all($wanted | to_entries[]; $actual[.key] == .value) and
    ($actual.environmentId == $environmentId) and
    ($actual.name == $name) and
    ($actual.serverId == null) and
    (($actual.appName | type) == "string") and
    ($actual.appName | test("^" + $prefix + "-[A-Za-z0-9]{6}\\z")) and
    ($actual.domains == []) and ($actual.mounts == []) and ($actual.backups == []) and
    ($actual.github == null) and ($actual.gitlab == null) and
    ($actual.bitbucket == null) and ($actual.gitea == null) and
    ($actual.server == null) and
    (($actual.environment | type) == "object") and
    ($actual.environment.environmentId == $environmentId) and
    (($actual.environment.project | type) == "object") and
    (($actual.environment.env == null) or ($actual.environment.env == "")) and
    (($actual.environment.project.env == null) or ($actual.environment.project.env == ""))
  ' "$persisted_file" >/dev/null; then
    die "Dokploy persisted unexpected review Compose state"
    return 1
  fi
}

# Shared normalization for materialized-compose evidence. env -i: the
# materialized file legitimately contains no ${...} placeholders, so
# interpolation must never see runner secrets (a hostile response could
# otherwise pull them into the normalized evidence via ${DOKPLOY_TOKEN}).
# On failure the parser's diagnostics are surfaced with every 64-hex value
# redacted (review credentials are validated 64-hex lowercase; release ids and
# digests are recognizable without their payload) so live failures are
# diagnosable from run logs without leaking manifest secrets.
_review_normalize_compose_evidence() {
  local yaml_file=$1 json_file=$2 stderr_file
  stderr_file=${yaml_file%.yaml}.stderr
  if command env -i PATH="$PATH" HOME="${HOME-}" \
    docker compose --file "$yaml_file" config --format json > "$json_file" 2> "$stderr_file"; then
    return 0
  fi
  sed -E 's/[0-9a-f]{64}/[64-hex]/g' "$stderr_file" | head -n 5 >&2
  die "unable to normalize materialized review Compose evidence"
  return 1
}

# Deploy-path verifier: checks that the materialized review stack kept the
# routing-critical properties after
# Dokploy's isolated-deployment conversion (plan 088). Traefik v3's swarm
# provider needs traefik.swarm.network to pick a task IP it can reach, skips
# services that also define traefik.docker.network (routes 404), and the
# Swarm converter must not have dropped the long-form tmpfs mounts.
_review_require_materialized_stack() {
  local compose_id=$1 app_name=$2 workdir=$3 response_file yaml_file json_file
  response_file=$workdir/converted-stack.json
  yaml_file=$workdir/materialized-stack.yaml
  json_file=$workdir/materialized-stack.json
  dokploy_api GET "compose.getConvertedCompose?composeId=$compose_id" > "$response_file" || return 1
  if ! jq -er 'select(type == "string" and length > 0)' "$response_file" > "$yaml_file"; then
    die "Dokploy returned invalid materialized review Compose evidence"
    return 1
  fi
  _review_normalize_compose_evidence "$yaml_file" "$json_file" || return 1
  if ! jq -e --arg appName "$app_name" '
    (type == "object") and
    (.services | type) == "object" and
    ((["db", "server", "site"] - (.services | keys)) == []) and
    ([.services[] |
        ((.deploy.labels // {}) + (.labels // {})) as $labels |
        ($labels | type) == "object" and
        ($labels | has("traefik.docker.network") | not) and
        ((.networks | type) == "object") and ((.networks | keys) == [$appName])
      ] | all) and
    ([.services.site, .services.server] |
      all(. as $svc |
        ($svc | type) == "object" and
        (($svc.deploy.labels // {})["traefik.enable"] == "true") and
        (($svc.deploy.labels // {})["traefik.swarm.network"] == $appName) and
        (($svc.volumes // []) |
          any(type == "object" and .type == "tmpfs" and .target == "/tmp"))
      )) and
    (.networks | type) == "object" and ((.networks | keys) == [$appName]) and
    (.networks[$appName] | type) == "object" and
    (.networks[$appName].external == true) and
    ((.networks[$appName].name // $appName) == $appName)
  ' "$json_file" >/dev/null; then
    # Structural evidence only — service/network/label-key shape, never
    # environment values or full label strings; 64-hex redaction as backstop.
    jq -c '{
      topLevel: (if type == "object" then keys else type end),
      services: (
        (.services // {}) | if type == "object" then
          to_entries | map({
            name: .key,
            networks: ((.value.networks // {}) | if type == "object" then keys else type end),
            volumes: ((.value.volumes // []) | if type == "array" then
              map(if type == "object" then {type: (.type // null), target: (.target // null)} else {short: true} end)
            else type end),
            traefikLabelKeys: ((.value.deploy.labels // {}) | if type == "object" then
              (keys | map(select(startswith("traefik"))))
            else type end),
            swarmNetwork: (((.value.deploy.labels // {})["traefik.swarm.network"]) // null)
          })
        else type end),
      networks: (
        (.networks // {}) | if type == "object" then
          to_entries | map({name: .key, external: (.value.external // null)})
        else type end)
    }' "$json_file" 2>/dev/null | sed -E 's/[0-9a-f]{64}/[64-hex]/g' >&2 || true
    die "Dokploy did not materialize a routable isolated review stack"
    return 1
  fi
}

_review_render_stack() {
  local output=$1 server_image=$2 site_image=$3 repository=$4 pr=$5 sha=$6
  local provider_id=$7 site_host=$8 dashboard_host=$9 network=${10} grep_rc
  if ! REVIEW_SERVER_IMAGE=$server_image REVIEW_SITE_IMAGE=$site_image \
    REVIEW_REPOSITORY=$repository REVIEW_PR=$pr REVIEW_SHA=$sha \
    REVIEW_PROVIDER_ID=$provider_id REVIEW_SITE_HOST=$site_host \
    REVIEW_DASHBOARD_HOST=$dashboard_host REVIEW_NETWORK=$network \
    jq -jRs '
      split("${BUILDHOUND_SERVER_IMAGE}") | join(env.REVIEW_SERVER_IMAGE) |
      split("${BUILDHOUND_SITE_IMAGE}") | join(env.REVIEW_SITE_IMAGE) |
      split("${BUILDHOUND_REVIEW_DB_PASSWORD}") | join(env.BUILDHOUND_REVIEW_DB_PASSWORD) |
      split("${BUILDHOUND_REVIEW_TOKEN}") | join(env.BUILDHOUND_REVIEW_TOKEN) |
      split("${BUILDHOUND_REPOSITORY}") | join(env.REVIEW_REPOSITORY) |
      split("${BUILDHOUND_PR_NUMBER}") | join(env.REVIEW_PR) |
      split("${BUILDHOUND_HEAD_SHA}") | join(env.REVIEW_SHA) |
      split("${BUILDHOUND_REVIEW_PROVIDER_ID}") | join(env.REVIEW_PROVIDER_ID) |
      split("${BUILDHOUND_REVIEW_SITE_HOST}") | join(env.REVIEW_SITE_HOST) |
      split("${BUILDHOUND_REVIEW_DASHBOARD_HOST}") | join(env.REVIEW_DASHBOARD_HOST) |
      split("${BUILDHOUND_REVIEW_NETWORK}") | join(env.REVIEW_NETWORK)
    ' "$_review_stack_template" > "$output"; then
    die "unable to render trusted review manifest"
    return 1
  fi
  if grep -Eq '\$\{[^}]+}' "$output"; then
    die "trusted review manifest contains an unresolved placeholder"
    return 1
  else
    grep_rc=$?
    if [[ $grep_rc -ne 1 ]]; then
      die "unable to inspect rendered review manifest"
      return 1
    fi
  fi
}

_review_old_deployments() {
  local compose_id=$1 output=$2 deployments
  deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$compose_id") || return 1
  if ! jq -ce '
    if type != "array" or any(.[];
      type != "object" or
      ((.deploymentId? // null) != null and
       ((.deploymentId | type) != "string" or
        (.deploymentId | test("^[A-Za-z0-9_-]{1,128}\\z") | not))))
    then error("invalid deployment response")
    else [.[] | .deploymentId | select(type == "string")] | unique
    end
  ' > "$output" <<< "$deployments"; then
    die "Dokploy returned invalid deployment evidence"
    return 1
  fi
}

# Returns 42 for an exact failed terminal deployment and another nonzero status
# for an uncertain/invalid state that must preserve its reconciliation anchor.
_review_wait_for_deployment() {
  local compose_id=$1 old_file=$2 title=$3 attempt deployments result count status deployment_id
  local deadline=$((SECONDS + 600)) remaining
  for ((attempt = 0; attempt <= 120; attempt++)); do
    deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$compose_id") || return 1
    if ! result=$(jq -ce --slurpfile old "$old_file" --arg title "$title" '
      if type != "array" or any(.[]; type != "object")
      then error("invalid deployment response")
      else [
        .[] as $item |
        select($item.title == $title and
               (($item.deploymentId | type) != "string" or
                (($old[0] | index($item.deploymentId)) == null))) |
        $item
      ]
      end
    ' <<< "$deployments"); then
      die "Dokploy returned invalid deployment evidence"
      return 1
    fi
    count=$(jq -er 'length' <<< "$result") || return 1
    if [[ $count -gt 1 ]]; then
      die "new deployment evidence is ambiguous"
      return 1
    fi
    if [[ $count -eq 1 ]]; then
      deployment_id=$(jq -er '.[0].deploymentId | select(type == "string" and test("^[A-Za-z0-9_-]{1,128}\\z"))' <<< "$result") || {
        die "matching deployment has an invalid or missing ID"
        return 1
      }
      status=$(jq -er '
        (.[0].status // "") as $status
        | if ($status | type) != "string" then "other"
          elif ($status | ascii_downcase | test("^(error|failed|cancelled|done|success)\\z"))
          then ($status | ascii_downcase)
          else "other"
          end
      ' <<< "$result") || return 1
      case "$status" in
        error|failed|cancelled)
          printf '%s\n' "Dokploy deployment reached a failed terminal state" >&2
          return 42
          ;;
        done|success)
          if ! _review_valid_object_id "$deployment_id"; then
            die "successful deployment has an invalid ID"
            return 1
          fi
          printf '%s\n' "$deployment_id"
          return 0
          ;;
      esac
    fi
    remaining=$((deadline - SECONDS))
    if [[ $attempt -eq 120 || $remaining -le 0 ]]; then
      die "Dokploy deployment did not reach success within 10 minutes"
      return 1
    fi
    if [[ $remaining -gt 5 ]]; then sleep 5; else sleep "$remaining"; fi
  done
}

_review_wait_for_routes_gone() {
  local name=$1 dns_suffix=$2 hosts site_host dashboard_host attempt site_status dashboard_status
  local deadline=$((SECONDS + 300)) remaining
  hosts=$(review_hosts "$name" "$dns_suffix") || return 1
  site_host=${hosts%%|*}
  dashboard_host=${hosts#*|}
  for ((attempt = 0; attempt <= 60; attempt++)); do
    site_status=$(curl --disable --silent --output /dev/null --proto '=https' \
      --connect-timeout 10 --max-time 20 --max-redirs 0 --write-out '%{http_code}' \
      "https://$site_host/" 2>/dev/null) || site_status=
    dashboard_status=$(curl --disable --silent --output /dev/null --proto '=https' \
      --connect-timeout 10 --max-time 20 --max-redirs 0 --write-out '%{http_code}' \
      "https://$dashboard_host/health" 2>/dev/null) || dashboard_status=
    if [[ $site_status == 404 && $dashboard_status == 404 ]]; then
      return 0
    fi
    remaining=$((deadline - SECONDS))
    if [[ $attempt -eq 60 || $remaining -le 0 ]]; then
      die "review routes remained reachable after Swarm removal"
      return 1
    fi
    if [[ $remaining -gt 5 ]]; then sleep 5; else sleep "$remaining"; fi
  done
}

_review_revoke_compose() (
  local compose_id=$1 name=$2 dns_suffix=$3 expected_sha=${4-} expected_attempt_id=${5-}
  local expected_title_override=${6-}
  local body deployments active_count workdir compose_file
  local expected_title=''

  if [[ -n $expected_title_override ]]; then
    expected_title=$expected_title_override
  elif [[ -n $expected_attempt_id ]]; then
    expected_title=$expected_sha'|'$expected_attempt_id
  elif [[ -n $expected_sha ]]; then
    expected_title=$expected_sha
  fi

  umask 077
  workdir=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/buildhound-review-revoke.XXXXXX") || {
    die "unable to create private review revocation workspace"
    return 1
  }
  BUILDHOUND_REVIEW_REVOKE_WORKDIR=$workdir
  trap 'if [[ -n ${BUILDHOUND_REVIEW_REVOKE_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_REVOKE_WORKDIR"; fi' EXIT
  trap 'if [[ -n ${BUILDHOUND_REVIEW_REVOKE_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_REVOKE_WORKDIR"; fi; exit 129' HUP
  trap 'if [[ -n ${BUILDHOUND_REVIEW_REVOKE_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_REVOKE_WORKDIR"; fi; exit 130' INT
  trap 'if [[ -n ${BUILDHOUND_REVIEW_REVOKE_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_REVOKE_WORKDIR"; fi; exit 143' TERM
  compose_file=$workdir/compose.json

  # Plan 089: no queue-drain choreography — a queued deployment that slips
  # past the checks below is converged away by the next reconciler tick
  # instead of being prevented here.
  body=$(jq -cn --arg composeId "$compose_id" '{composeId:$composeId}') || return 1
  if [[ -n $expected_title ]]; then
    deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$compose_id") || return 1
    if ! active_count=$(jq -er --arg title "$expected_title" '
      if type != "array" or any(.[]; type != "object")
      then error("invalid deployment response")
      else [ .[] |
        select(.title == $title) |
        (.status | tostring | ascii_downcase) |
        select(. != "done" and . != "success" and . != "error" and
               . != "failed" and . != "cancelled")
      ] | length
      end
    ' <<< "$deployments"); then
      die "Dokploy returned invalid deployment evidence during cleanup"
      return 1
    fi
    if [[ $active_count -ne 0 ]]; then
      die "review deployment is still active; preserving reconciliation anchor"
      return 1
    fi
  fi
  dokploy_api GET "compose.one?composeId=$compose_id" > "$compose_file" || return 1
  if ! jq -e --arg composeId "$compose_id" '
    type == "object" and .composeId == $composeId and
    (.composeStatus == "idle" or .composeStatus == "done" or .composeStatus == "error")
  ' "$compose_file" >/dev/null; then
    die "review deployment is still active or has unknown state"
    return 1
  fi
  dokploy_api POST compose.stop "$body" >/dev/null || return 1
  _review_wait_for_routes_gone "$name" "$dns_suffix"
)

_review_cleanup_failed_attempt() {
  local repository=$1 pr=$2 sha=$3 attempt_id=$4 environment_id=$5 name=$6 dns_suffix=$7 terminal=$8
  local environment matches count compose_id expected='' expected_attempt=''
  environment=$(dokploy_api GET "environment.one?environmentId=$environment_id") || return 1
  matches=$(_review_owned_items "$environment" "$name" "$repository" "$pr" "$sha" "$attempt_id") || return 1
  count=$(jq -er 'length' <<< "$matches") || return 1
  if [[ $count -gt 1 ]]; then
    die "failed review ownership is ambiguous"
    return 1
  fi
  if [[ $count -eq 1 ]]; then
    compose_id=$(jq -er '.[0].composeId | select(type == "string" and test("^[A-Za-z0-9_-]{1,128}\\z"))' <<< "$matches") || return 1
    _review_valid_object_id "$compose_id" || {
      die "failed review has an invalid Compose ID"
      return 1
    }
    if [[ $terminal == true ]]; then
      expected=$sha
      expected_attempt=$attempt_id
    fi
    _review_revoke_compose "$compose_id" "$name" "$dns_suffix" "$expected" \
      "$expected_attempt" || return 1
  fi
}

review_validate_deploy_args() {
  local base_repo=${1-} head_repo=${2-} pr=${3-} sha=${4-} state=${5-}
  local label_present=${6-} environment_id=${7-} dns_suffix=${8-}
  local server_image=${9-} site_image=${10-} attempt_id=${11-} name

  if [[ $# -ne 11 ]] || ! _review_valid_repository "$base_repo" ||
     ! _review_valid_positive_integer "$pr"; then
    die "valid repository and positive PR number required"
    return 1
  fi
  if ! _review_valid_sha "$sha"; then die "exact 40-character head SHA required"; return 1; fi
  if ! _review_valid_attempt_id "$attempt_id"; then die "valid review attempt ID required"; return 1; fi
  if [[ $head_repo != "$base_repo" ]]; then die "fork reviews are forbidden"; return 1; fi
  if [[ $state != open || $label_present != true ]]; then die "PR is not currently eligible"; return 1; fi
  if ! _review_valid_object_id "$environment_id"; then die "valid review environment ID required"; return 1; fi
  if ! _review_valid_digest "$server_image" || ! _review_valid_digest "$site_image"; then
    die "review images must be resolved digests"
    return 1
  fi
  if ! _review_valid_secret "${BUILDHOUND_REVIEW_DB_PASSWORD-}" ||
     ! _review_valid_secret "${BUILDHOUND_REVIEW_TOKEN-}"; then
    die "review credentials must be 64-character lowercase hexadecimal values"
    return 1
  fi
  name=$(review_name "$pr") || return 1
  review_provider_id "$base_repo" "$pr" >/dev/null || return 1
  review_hosts "$name" "$dns_suffix" >/dev/null || return 1
}

deploy_review() (
  local base_repo=${1-} head_repo=${2-} pr=${3-} sha=${4-} state=${5-}
  local label_present=${6-} environment_id=${7-} dns_suffix=${8-}
  local server_image=${9-} site_image=${10-} attempt_id=${11-}
  local name provider_id hosts site_host dashboard_host workdir compose_file update_file old_file body_file
  local persisted_file create_result_file description environment owned count compose_id body deployment_id rc
  local app_name pre_file
  local activated_at existing_sha existing_retired title
  local mutation_possible=false deploy_may_be_active=false terminal=false

  review_validate_deploy_args "$@" || return 1
  _review_require_supported_dokploy_version || return 1
  name=$(review_name "$pr") || return 1
  provider_id=$(review_provider_id "$base_repo" "$pr") || return 1
  hosts=$(review_hosts "$name" "$dns_suffix") || return 1
  site_host=${hosts%%|*}
  dashboard_host=${hosts#*|}

  umask 077
  workdir=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/buildhound-review.XXXXXX") || return 1
  BUILDHOUND_REVIEW_WORKDIR=$workdir
  trap 'if [[ -n ${BUILDHOUND_REVIEW_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_WORKDIR"; fi' EXIT
  trap 'if [[ -n ${BUILDHOUND_REVIEW_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_WORKDIR"; fi; exit 129' HUP
  trap 'if [[ -n ${BUILDHOUND_REVIEW_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_WORKDIR"; fi; exit 130' INT
  trap 'if [[ -n ${BUILDHOUND_REVIEW_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_WORKDIR"; fi; exit 143' TERM
  compose_file=$workdir/compose.yaml
  update_file=$workdir/update.json
  old_file=$workdir/old.json
  persisted_file=$workdir/persisted.json
  create_result_file=$workdir/create-result.json
  # Bootstrap render for compose.create only: Dokploy assigns the isolated
  # network's name (the suffixed appName) at creation time, so the real
  # network label value is substituted in a second render before
  # compose.update — the file sent here is never deployed.
  _review_render_stack "$compose_file" "$server_image" "$site_image" "$base_repo" "$pr" "$sha" \
    "$provider_id" "$site_host" "$dashboard_host" "$provider_id" || return 1
  activated_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ') || return 1
  if [[ ! $activated_at =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]; then
    die "unable to determine review activation time"
    return 1
  fi
  description=$(jq -cn --arg activatedAt "$activated_at" --arg attemptId "$attempt_id" \
    --arg repository "$base_repo" \
    --argjson pr "$pr" --arg sha "$sha" \
    '{activatedAt:$activatedAt,attemptId:$attemptId,repository:$repository,
      pr:$pr,retired:false,sha:$sha}') || return 1

  environment=$(dokploy_api GET "environment.one?environmentId=$environment_id") || return 1
  owned=$(_review_owned_items "$environment" "$name" "$base_repo" "$pr" "") || return 1
  count=$(jq -er 'length' <<< "$owned") || return 1
  if [[ $count -gt 1 ]]; then die "review ownership is ambiguous"; return 1; fi
  if [[ $count -eq 1 ]]; then
    if ! jq -e '.[0] | has("serverId") and .serverId == null' >/dev/null <<< "$owned"; then
      die "owned review has an unexpected server target"
      return 1
    fi
    compose_id=$(jq -er '.[0].composeId | select(type == "string" and test("^[A-Za-z0-9_-]{1,128}\\z"))' <<< "$owned") || return 1
    if ! _review_valid_object_id "$compose_id"; then die "owned review has an invalid Compose ID"; return 1; fi
    existing_sha=$(jq -er '.[0].description | fromjson | .sha | select(type == "string")' <<< "$owned") || return 1
    existing_retired=$(jq -er '
      .[0].description | fromjson | (.retired // false) |
      select(type == "boolean") | if . then "true" else "false" end
    ' <<< "$owned") || return 1
    if [[ $existing_sha == "$sha" && $existing_retired != true ]]; then
      die "same-SHA review redeploys are unsupported; push a new commit"
      return 1
    fi
    _review_old_deployments "$compose_id" "$old_file" || return 1
  else
    printf '[]\n' > "$old_file"
    body_file=$workdir/create.json
    jq -cn --arg environmentId "$environment_id" --arg name "$name" --arg appName "$provider_id" \
      --arg description "$description" --rawfile composeFile "$compose_file" '
      {environmentId:$environmentId,name:$name,appName:$appName,description:$description,
       composeType:"stack",composeFile:$composeFile}
    ' > "$body_file" || return 1
    mutation_possible=true
    if dokploy_api POST compose.create "$(< "$body_file")" > "$create_result_file"; then
      compose_id=$(jq -er '.composeId | select(type == "string" and test("^[A-Za-z0-9_-]{1,128}\\z"))' "$create_result_file") || rc=$?
      if [[ ${rc:-0} -eq 0 ]] && ! _review_valid_object_id "$compose_id"; then rc=1; fi
    else
      rc=$?
    fi
    if [[ ${rc:-0} -ne 0 ]]; then
      if ! _review_cleanup_failed_attempt "$base_repo" "$pr" "$sha" "$attempt_id" "$environment_id" "$name" "$dns_suffix" false; then
        die "review deployment failed and exact-owned cleanup failed"
        return 1
      fi
      return "$rc"
    fi
  fi

  # The isolated network is named after the compose's suffixed appName, which
  # only exists server-side. Read it back, then render the trusted manifest
  # again so traefik.swarm.network points at the real isolated network.
  pre_file=$workdir/pre-update.json
  if dokploy_api GET "compose.one?composeId=$compose_id" > "$pre_file" &&
     app_name=$(jq -er --arg prefix "$provider_id" '
       .appName | select(type == "string" and test("^" + $prefix + "-[A-Za-z0-9]{6}\\z"))
     ' "$pre_file") &&
     _review_render_stack "$compose_file" "$server_image" "$site_image" "$base_repo" "$pr" "$sha" \
       "$provider_id" "$site_host" "$dashboard_host" "$app_name"; then
    :
  else
    rc=$?
    if ! _review_cleanup_failed_attempt "$base_repo" "$pr" "$sha" "$attempt_id" "$environment_id" "$name" "$dns_suffix" false; then
      die "review deployment failed and exact-owned cleanup failed"
      return 1
    fi
    return "$rc"
  fi

  mutation_possible=true
  if ! _review_update_body "$compose_id" "$description" "$compose_file" "$update_file"; then
    if ! _review_cleanup_failed_attempt "$base_repo" "$pr" "$sha" "$attempt_id" "$environment_id" "$name" "$dns_suffix" false; then
      die "review deployment failed and exact-owned cleanup failed"
      return 1
    fi
    return 1
  fi
  if dokploy_api POST compose.update "$(< "$update_file")" >/dev/null; then
    :
  else
    rc=$?
    if ! _review_cleanup_failed_attempt "$base_repo" "$pr" "$sha" "$attempt_id" "$environment_id" "$name" "$dns_suffix" false; then
      die "review deployment failed and exact-owned cleanup failed"
      return 1
    fi
    return "$rc"
  fi
  if dokploy_api GET "compose.one?composeId=$compose_id" > "$persisted_file" &&
     _review_require_exact_compose "$persisted_file" "$update_file" "$environment_id" "$name" "$provider_id"; then
    :
  else
    rc=$?
    if ! _review_cleanup_failed_attempt "$base_repo" "$pr" "$sha" "$attempt_id" "$environment_id" "$name" "$dns_suffix" false; then
      die "review deployment failed and exact-owned cleanup failed"
      return 1
    fi
    return "$rc"
  fi
  title=$sha'|'$attempt_id
  body=$(jq -cn --arg composeId "$compose_id" --arg title "$title" '{composeId:$composeId,title:$title}') || return 1
  deploy_may_be_active=true
  if ! dokploy_api POST compose.deploy "$body" >/dev/null; then
    printf '%s\n' "warning: review deployment state is uncertain; preserving exact-owned reconciliation anchor" >&2
    return 1
  fi
  if deployment_id=$(_review_wait_for_deployment "$compose_id" "$old_file" "$title"); then
    # Dokploy materializes the manager-side converted file on deploy, not on
    # compose.update (same reason the anchor verifier runs post-deploy), so
    # the routing verification can only see the real stack here.
    if _review_require_materialized_stack "$compose_id" "$app_name" "$workdir"; then
      :
    else
      rc=$?
      if ! _review_cleanup_failed_attempt "$base_repo" "$pr" "$sha" "$attempt_id" "$environment_id" "$name" "$dns_suffix" false; then
        die "review deployment failed and exact-owned cleanup failed"
        return 1
      fi
      return "$rc"
    fi
    jq -cn --arg name "$name" --arg composeId "$compose_id" --arg deploymentId "$deployment_id" \
      '{name:$name,composeId:$composeId,deploymentId:$deploymentId}'
    return 0
  else
    rc=$?
  fi
  if [[ $rc -eq 42 ]]; then
    terminal=true
    if ! _review_cleanup_failed_attempt "$base_repo" "$pr" "$sha" "$attempt_id" "$environment_id" "$name" "$dns_suffix" "$terminal"; then
      die "review deployment failed and exact-owned cleanup failed"
      return 1
    fi
  elif [[ $mutation_possible == true && $deploy_may_be_active == true ]]; then
    printf '%s\n' "warning: review deployment state is uncertain; preserving exact-owned reconciliation anchor" >&2
  fi
  return "$rc"
)

list_reviews() {
  local base_repo=${1-} environment_id=${2-} exclude_pr=${3-} environment exclude_json
  if [[ $# -lt 2 || $# -gt 3 ]] || ! _review_valid_repository "$base_repo"; then
    die "valid repository required"
    return 1
  fi
  if ! _review_valid_object_id "$environment_id"; then die "valid review environment ID required"; return 1; fi
  exclude_json=null
  if [[ -n $exclude_pr ]]; then
    if ! _review_valid_positive_integer "$exclude_pr"; then die "excluded PR must be positive"; return 1; fi
    exclude_json=$exclude_pr
  fi
  environment=$(dokploy_api GET "environment.one?environmentId=$environment_id") || return 1
  if ! jq -cer --arg repository "$base_repo" --argjson exclude "$exclude_json" '
    def review_metadata:
      (.description // "{}") as $description |
      (if ($description | type) == "string"
       then (try ($description | fromjson) catch null)
      else null end) as $metadata |
      if (($metadata | type) == "object") and
         (($metadata | has("repository")) and ($metadata | has("pr")) and
          ($metadata | has("sha"))) and
         (($metadata | keys) - ["activatedAt", "attemptId", "pr", "repository", "retired", "retiredAt", "sha"] == []) and
         (($metadata.repository | type) == "string") and
         ($metadata.repository | test("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\\z")) and
         (($metadata.pr | type) == "number") and
         ($metadata.pr > 0) and ($metadata.pr == ($metadata.pr | floor)) and
         (($metadata.sha | type) == "string") and
         ($metadata.sha | test("^[0-9a-f]{40}\\z")) and
         ((($metadata | has("retired")) | not) or
          (($metadata.retired | type) == "boolean")) and
         ((($metadata | has("activatedAt")) | not) or
          (($metadata.activatedAt | type) == "string" and
           ($metadata.activatedAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\\z")))) and
         ((($metadata | has("retiredAt")) | not) or
          (($metadata.retiredAt | type) == "string" and
           ($metadata.retiredAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\\z")))) and
         ((($metadata | has("attemptId")) | not) or
          (($metadata.attemptId | type) == "string" and
           ($metadata.attemptId | test("^[1-9][0-9]{0,19}\\.[1-9][0-9]{0,9}\\z"))))
      then $metadata + {
        activatedAt:($metadata.activatedAt // null),
        attemptId:($metadata.attemptId // null),
        retired:($metadata.retired // false)
      }
      else error("review ownership metadata is invalid")
      end;
    if type != "object" or (.compose | type) != "array" or any(.compose[]; type != "object")
    then error("Dokploy returned an invalid review environment")
    else [
      .compose[] as $item |
      ($item | review_metadata) as $metadata |
      select($metadata.repository == $repository and ($exclude == null or $metadata.pr != $exclude)) |
      {activatedAt:($metadata.activatedAt // $item.createdAt),attemptId:$metadata.attemptId,
       pr:$metadata.pr,retired:$metadata.retired,sha:$metadata.sha,
       createdAt:$item.createdAt,composeId:$item.composeId}
    ]
    end
  ' <<< "$environment"; then
    die "Dokploy returned invalid review ownership metadata"
    return 1
  fi
}

count_reviews() {
  local reviews
  reviews=$(list_reviews "$@") || return 1
  jq -er '[.[] | select(.retired == false)] | length' <<< "$reviews"
}

_review_get_exact_record() {
  local base_repo=$1 pr=$2 environment_id=$3 compose_id=$4 sha=$5 attempt_id=$6
  local name environment matches count
  name=$(review_name "$pr") || return 1
  environment=$(dokploy_api GET "environment.one?environmentId=$environment_id") || return 1
  matches=$(_review_owned_items "$environment" "$name" "$base_repo" "$pr" "$sha" "$attempt_id") || return 1
  count=$(jq -er --arg composeId "$compose_id" \
    '[.[] | select(has("serverId") and .composeId == $composeId and .serverId == null)] | length' \
    <<< "$matches") || return 1
  if [[ $count -ne 1 ]]; then
    die "review ownership is missing or ambiguous"
    return 1
  fi
  jq -cer --arg composeId "$compose_id" \
    '.[] | select(has("serverId") and .composeId == $composeId and .serverId == null)' \
    <<< "$matches"
}

_review_validate_cleanup_args() {
  local base_repo=$1 pr=$2 environment_id=$3 dns_suffix=$4 compose_id=$5 sha=$6 attempt_id=$7 name
  if ! _review_valid_repository "$base_repo" || ! _review_valid_positive_integer "$pr" ||
     ! _review_valid_object_id "$environment_id" || ! _review_valid_object_id "$compose_id" ||
     ! _review_valid_sha "$sha"; then
    die "valid exact review ownership is required"
    return 1
  fi
  if [[ -n $attempt_id ]] && ! _review_valid_attempt_id "$attempt_id"; then
    die "valid exact review attempt is required"
    return 1
  fi
  name=$(review_name "$pr") || return 1
  review_hosts "$name" "$dns_suffix" >/dev/null
}

scrub_review() (
  local base_repo=${1-} pr=${2-} environment_id=${3-} dns_suffix=${4-} compose_id=${5-} sha=${6-}
  local attempt_id=${7-} name provider_id record metadata description workdir anchor_file update_file
  local persisted_file old_file title body deployment_id retired scrub_epoch
  [[ $# -eq 7 ]] || { die "scrub_review requires seven arguments"; return 1; }
  _review_validate_cleanup_args "$base_repo" "$pr" "$environment_id" "$dns_suffix" "$compose_id" "$sha" "$attempt_id" || return 1
  _review_require_supported_dokploy_version || return 1
  record=$(_review_get_exact_record "$base_repo" "$pr" "$environment_id" "$compose_id" "$sha" "$attempt_id") || return 1
  name=$(review_name "$pr") || return 1
  provider_id=$(review_provider_id "$base_repo" "$pr") || return 1
  metadata=$(jq -cer '.description | fromjson' <<< "$record") || return 1
  retired=$(jq -er '(.retired // false) | select(type == "boolean") | if . then "true" else "false" end' <<< "$metadata") || return 1
  description=$(jq -c --argjson retired "$retired" '. + {retired:$retired}' <<< "$metadata") || return 1

  _review_revoke_compose "$compose_id" "$name" "$dns_suffix" "$sha" "$attempt_id" || return 1

  umask 077
  workdir=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/buildhound-review-scrub.XXXXXX") || return 1
  BUILDHOUND_REVIEW_SCRUB_WORKDIR=$workdir
  trap 'if [[ -n ${BUILDHOUND_REVIEW_SCRUB_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_SCRUB_WORKDIR"; fi' EXIT
  trap 'if [[ -n ${BUILDHOUND_REVIEW_SCRUB_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_SCRUB_WORKDIR"; fi; exit 129' HUP
  trap 'if [[ -n ${BUILDHOUND_REVIEW_SCRUB_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_SCRUB_WORKDIR"; fi; exit 130' INT
  trap 'if [[ -n ${BUILDHOUND_REVIEW_SCRUB_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_SCRUB_WORKDIR"; fi; exit 143' TERM
  anchor_file=$workdir/anchor.yaml
  update_file=$workdir/update.json
  persisted_file=$workdir/compose.json
  old_file=$workdir/old.json
  cp -- "$_review_anchor_template" "$anchor_file" || return 1
  _review_old_deployments "$compose_id" "$old_file" || return 1
  _review_update_body "$compose_id" "$description" "$anchor_file" "$update_file" || return 1
  dokploy_api POST compose.update "$(< "$update_file")" >/dev/null || return 1
  dokploy_api GET "compose.one?composeId=$compose_id" > "$persisted_file" || return 1
  if ! _review_require_exact_compose "$persisted_file" "$update_file" \
      "$environment_id" "$name" "$provider_id" ||
     ! jq -e '.composeStatus == "idle"' "$persisted_file" >/dev/null; then
    die "Dokploy did not persist the inert isolated review anchor"
    return 1
  fi

  scrub_epoch=$(date -u +%s) || return 1
  [[ $scrub_epoch =~ ^[0-9]{1,20}$ ]] || { die "unable to derive scrub deployment title"; return 1; }
  title="scrub|$sha|${attempt_id:-legacy}|$scrub_epoch|$$"
  body=$(jq -cn --arg composeId "$compose_id" --arg title "$title" \
    '{composeId:$composeId,title:$title}') || return 1
  if ! dokploy_api POST compose.deploy "$body" >/dev/null; then
    die "inert review scrub deployment could not be queued"
    return 1
  fi
  deployment_id=$(_review_wait_for_deployment "$compose_id" "$old_file" "$title") || return 1
  # Plan 089: no getConvertedCompose anchor verification here — a mangled
  # anchor materialization is a convergence concern, not a scrub blocker.
  _review_revoke_compose "$compose_id" "$name" "$dns_suffix" "" "" "$title" || return 1
  dokploy_api GET "compose.one?composeId=$compose_id" > "$persisted_file" || return 1
  if ! _review_require_exact_compose "$persisted_file" "$update_file" \
      "$environment_id" "$name" "$provider_id" ||
     ! jq -e '.composeStatus == "idle"' "$persisted_file" >/dev/null; then
    die "Dokploy did not preserve the stopped inert isolated review anchor"
    return 1
  fi
  jq -jrn --arg scrubbed "$name" --arg deploymentId "$deployment_id" \
    '"{\"scrubbed\": " + ($scrubbed | tojson) + ", \"deploymentId\": " + ($deploymentId | tojson) + "}\n"'
)

retire_review() (
  local base_repo=${1-} pr=${2-} environment_id=${3-} dns_suffix=${4-} compose_id=${5-} sha=${6-}
  local attempt_id=${7-} name provider_id record metadata description retired workdir anchor_file
  local update_file persisted_file retired_at
  [[ $# -eq 7 ]] || { die "retire_review requires seven arguments"; return 1; }
  _review_validate_cleanup_args "$base_repo" "$pr" "$environment_id" "$dns_suffix" "$compose_id" "$sha" "$attempt_id" || return 1
  _review_require_supported_dokploy_version || return 1
  record=$(_review_get_exact_record "$base_repo" "$pr" "$environment_id" "$compose_id" "$sha" "$attempt_id") || return 1
  name=$(review_name "$pr") || return 1
  provider_id=$(review_provider_id "$base_repo" "$pr") || return 1
  metadata=$(jq -cer '.description | fromjson' <<< "$record") || return 1
  retired=$(jq -er '(.retired // false) | select(type == "boolean") | if . then "true" else "false" end' <<< "$metadata") || return 1

  umask 077
  workdir=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/buildhound-review-retire.XXXXXX") || return 1
  BUILDHOUND_REVIEW_RETIRE_WORKDIR=$workdir
  trap 'if [[ -n ${BUILDHOUND_REVIEW_RETIRE_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_RETIRE_WORKDIR"; fi' EXIT
  trap 'if [[ -n ${BUILDHOUND_REVIEW_RETIRE_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_RETIRE_WORKDIR"; fi; exit 129' HUP
  trap 'if [[ -n ${BUILDHOUND_REVIEW_RETIRE_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_RETIRE_WORKDIR"; fi; exit 130' INT
  trap 'if [[ -n ${BUILDHOUND_REVIEW_RETIRE_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_REVIEW_RETIRE_WORKDIR"; fi; exit 143' TERM
  anchor_file=$workdir/anchor.yaml
  update_file=$workdir/update.json
  persisted_file=$workdir/compose.json
  cp -- "$_review_anchor_template" "$anchor_file" || return 1
  # retiredAt anchors the host GC's retention window to actual retirement
  # time (plan 089). An already-retired anchor keeps its original stamp:
  # regenerating it here would make the post-check compare a fresh timestamp
  # against the preserved one and fail whenever the two retires straddle a
  # second boundary (observed as a CI-only flake).
  if [[ $retired == true ]]; then
    description=$metadata
  else
    retired_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ') || return 1
    if [[ ! $retired_at =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]; then
      die "unable to determine review retirement time"
      return 1
    fi
    description=$(jq -c --arg retiredAt "$retired_at" \
      '. + {retired:true, retiredAt:$retiredAt}' <<< "$metadata") || return 1
  fi
  _review_update_body "$compose_id" "$description" "$anchor_file" "$update_file" || return 1
  dokploy_api GET "compose.one?composeId=$compose_id" > "$persisted_file" || return 1
  # Plan 089: scrub-before-retire ordering is the callers' (converge)
  # responsibility; the getConvertedCompose anchor re-verification is gone.
  if ! jq -e '.composeStatus == "idle"' "$persisted_file" >/dev/null; then
    die "review anchor is not safely scrubbed and stopped"
    return 1
  fi
  if [[ $retired != true ]]; then
    dokploy_api POST compose.update "$(< "$update_file")" >/dev/null || return 1
  fi
  dokploy_api GET "compose.one?composeId=$compose_id" > "$persisted_file" || return 1
  if ! _review_require_exact_compose "$persisted_file" "$update_file" \
      "$environment_id" "$name" "$provider_id" ||
     ! jq -e '.composeStatus == "idle"' "$persisted_file" >/dev/null; then
    die "Dokploy did not preserve the retired isolated review anchor"
    return 1
  fi
  jq -jrn --arg retired "$name" '"{\"retired\": " + ($retired | tojson) + "}\n"'
)
