#!/usr/bin/env bash
# Review-environment policy and lifecycle for the trusted Dokploy client.
{ set +x; } 2>/dev/null

_review_lib_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "$_review_lib_dir/common.sh"
# shellcheck disable=SC1091
source "$_review_lib_dir/api.sh"
_review_stack_template=$_review_lib_dir/../review-stack.yaml
readonly _review_lib_dir _review_stack_template

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
         ((($metadata | keys | sort) == ["pr", "repository", "sha"]) or
          (($metadata | keys | sort) == ["attemptId", "pr", "repository", "sha"])) and
         (($metadata.repository | type) == "string") and
         ($metadata.repository | test("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\\z")) and
         (($metadata.pr | type) == "number") and
         ($metadata.pr > 0) and ($metadata.pr == ($metadata.pr | floor)) and
         (($metadata.sha | type) == "string") and
         ($metadata.sha | test("^[0-9a-f]{40}\\z")) and
         ((($metadata | has("attemptId")) | not) or
          (($metadata.attemptId | type) == "string" and
           ($metadata.attemptId | test("^[1-9][0-9]{0,19}\\.[1-9][0-9]{0,9}\\z"))))
      then $metadata + {attemptId:($metadata.attemptId // null)}
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
      isolatedDeployment: false,
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

_review_render_stack() {
  local output=$1 server_image=$2 site_image=$3 repository=$4 pr=$5 sha=$6
  local provider_id=$7 site_host=$8 dashboard_host=$9 ingress=${10} grep_rc
  if ! REVIEW_SERVER_IMAGE=$server_image REVIEW_SITE_IMAGE=$site_image \
    REVIEW_REPOSITORY=$repository REVIEW_PR=$pr REVIEW_SHA=$sha \
    REVIEW_PROVIDER_ID=$provider_id REVIEW_SITE_HOST=$site_host \
    REVIEW_DASHBOARD_HOST=$dashboard_host REVIEW_INGRESS=$ingress \
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
      split("${DOKPLOY_REVIEW_INGRESS_NETWORK}") | join(env.REVIEW_INGRESS)
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
  local body deployments matching_count active_count checks check workdir compose_file
  local expected_title=''

  if [[ -n $expected_attempt_id ]]; then
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

  body=$(jq -cn --arg composeId "$compose_id" '{composeId:$composeId}') || return 1
  dokploy_api POST compose.cleanQueues "$body" >/dev/null || return 1
  matching_count=0
  if [[ -n $expected_title ]]; then
    deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$compose_id") || return 1
    if ! matching_count=$(jq -er --arg title "$expected_title" '
      if type != "array" or any(.[]; type != "object")
      then error("invalid deployment response")
      else [.[] | select(.title == $title)] | length
      end
    ' <<< "$deployments"); then
      die "Dokploy returned invalid deployment evidence during cleanup"
      return 1
    fi
    if ! active_count=$(jq -er --arg title "$expected_title" '
      [ .[] |
        select(.title == $title) |
        (.status | tostring | ascii_downcase) |
        select(. != "done" and . != "success" and . != "error" and
               . != "failed" and . != "cancelled")
      ] | length
    ' <<< "$deployments"); then
      die "Dokploy returned invalid deployment evidence during cleanup"
      return 1
    fi
    if [[ $active_count -ne 0 ]]; then
      die "review deployment is still active; preserving reconciliation anchor"
      return 1
    fi
  fi
  if [[ -n $expected_title && $matching_count -eq 0 ]]; then checks=6; else checks=2; fi
  for ((check = 0; check < checks; check++)); do
    if [[ $check -gt 0 && $matching_count -eq 0 ]]; then
      dokploy_api POST compose.cleanQueues "$body" >/dev/null || return 1
    fi
    dokploy_api GET "compose.one?composeId=$compose_id" > "$compose_file" || return 1
    if ! jq -e --arg composeId "$compose_id" '
      type == "object" and .composeId == $composeId and
      (.composeStatus == "idle" or .composeStatus == "done" or .composeStatus == "error")
    ' "$compose_file" >/dev/null; then
      die "review deployment is still active or has unknown state"
      return 1
    fi
    if [[ $check -lt $((checks - 1)) ]]; then
      if [[ $checks -gt 2 ]]; then sleep 5; else sleep 1; fi
    fi
  done
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
  local label_present=${6-} environment_id=${7-} dns_suffix=${8-} ingress=${9-}
  local server_image=${10-} site_image=${11-} attempt_id=${12-} name

  if [[ $# -ne 12 ]] || ! _review_valid_repository "$base_repo" ||
     ! _review_valid_positive_integer "$pr"; then
    die "valid repository and positive PR number required"
    return 1
  fi
  if ! _review_valid_sha "$sha"; then die "exact 40-character head SHA required"; return 1; fi
  if ! _review_valid_attempt_id "$attempt_id"; then die "valid review attempt ID required"; return 1; fi
  if [[ $head_repo != "$base_repo" ]]; then die "fork reviews are forbidden"; return 1; fi
  if [[ $state != open || $label_present != true ]]; then die "PR is not currently eligible"; return 1; fi
  if ! _review_valid_object_id "$environment_id"; then die "valid review environment ID required"; return 1; fi
  if [[ $ingress != buildhound-review-ingress ]]; then die "unexpected review ingress network"; return 1; fi
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
  local label_present=${6-} environment_id=${7-} dns_suffix=${8-} ingress=${9-}
  local server_image=${10-} site_image=${11-} attempt_id=${12-}
  local name provider_id hosts site_host dashboard_host workdir compose_file update_file old_file body_file
  local persisted_file create_result_file description environment owned count compose_id body deployment_id rc
  local existing_sha title
  local mutation_possible=false deploy_may_be_active=false terminal=false

  review_validate_deploy_args "$@" || return 1
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
  _review_render_stack "$compose_file" "$server_image" "$site_image" "$base_repo" "$pr" "$sha" \
    "$provider_id" "$site_host" "$dashboard_host" "$ingress" || return 1
  description=$(jq -cn --arg attemptId "$attempt_id" --arg repository "$base_repo" \
    --argjson pr "$pr" --arg sha "$sha" \
    '{attemptId:$attemptId,repository:$repository,pr:$pr,sha:$sha}') || return 1

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
    if [[ $existing_sha == "$sha" ]]; then
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
         ((($metadata | keys | sort) == ["pr", "repository", "sha"]) or
          (($metadata | keys | sort) == ["attemptId", "pr", "repository", "sha"])) and
         (($metadata.repository | type) == "string") and
         ($metadata.repository | test("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\\z")) and
         (($metadata.pr | type) == "number") and
         ($metadata.pr > 0) and ($metadata.pr == ($metadata.pr | floor)) and
         (($metadata.sha | type) == "string") and
         ($metadata.sha | test("^[0-9a-f]{40}\\z")) and
         ((($metadata | has("attemptId")) | not) or
          (($metadata.attemptId | type) == "string" and
           ($metadata.attemptId | test("^[1-9][0-9]{0,19}\\.[1-9][0-9]{0,9}\\z"))))
      then $metadata + {attemptId:($metadata.attemptId // null)}
      else error("review ownership metadata is invalid")
      end;
    if type != "object" or (.compose | type) != "array" or any(.compose[]; type != "object")
    then error("Dokploy returned an invalid review environment")
    else [
      .compose[] as $item |
      ($item | review_metadata) as $metadata |
      select($metadata.repository == $repository and ($exclude == null or $metadata.pr != $exclude)) |
      {attemptId:$metadata.attemptId,pr:$metadata.pr,sha:$metadata.sha,
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
  jq -er 'length' <<< "$reviews"
}

_review_find_exact_record() {
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

revoke_review() {
  local base_repo=${1-} pr=${2-} environment_id=${3-} dns_suffix=${4-} compose_id=${5-} sha=${6-}
  local attempt_id=${7-} name
  [[ $# -eq 7 ]] || { die "revoke_review requires seven arguments"; return 1; }
  _review_validate_cleanup_args "$base_repo" "$pr" "$environment_id" "$dns_suffix" "$compose_id" "$sha" "$attempt_id" || return 1
  _review_find_exact_record "$base_repo" "$pr" "$environment_id" "$compose_id" "$sha" "$attempt_id" || return 1
  name=$(review_name "$pr") || return 1
  _review_revoke_compose "$compose_id" "$name" "$dns_suffix" "$sha" "$attempt_id" || return 1
  jq -jrn --arg revoked "$name" '"{\"revoked\": " + ($revoked | tojson) + "}\n"'
}

delete_review() {
  local base_repo=${1-} pr=${2-} environment_id=${3-} dns_suffix=${4-} compose_id=${5-} sha=${6-}
  local attempt_id=${7-} name body environment
  [[ $# -eq 7 ]] || { die "delete_review requires seven arguments"; return 1; }
  _review_validate_cleanup_args "$base_repo" "$pr" "$environment_id" "$dns_suffix" "$compose_id" "$sha" "$attempt_id" || return 1
  _review_find_exact_record "$base_repo" "$pr" "$environment_id" "$compose_id" "$sha" "$attempt_id" || return 1
  name=$(review_name "$pr") || return 1
  _review_revoke_compose "$compose_id" "$name" "$dns_suffix" "$sha" "$attempt_id" || return 1
  body=$(jq -cn --arg composeId "$compose_id" '{composeId:$composeId,deleteVolumes:true}') || return 1
  dokploy_api POST compose.delete "$body" >/dev/null || return 1
  environment=$(dokploy_api GET "environment.one?environmentId=$environment_id") || return 1
  if ! _review_environment_shape <<< "$environment"; then
    die "Dokploy returned an invalid review environment"
    return 1
  fi
  if ! jq -e --arg composeId "$compose_id" 'all(.compose[]; .composeId != $composeId)' >/dev/null <<< "$environment"; then
    die "Dokploy still reports the deleted review Compose or returned invalid state"
    return 1
  fi
  jq -jrn --arg deleted "$name" '"{\"deleted\": " + ($deleted | tojson) + "}\n"'
}
