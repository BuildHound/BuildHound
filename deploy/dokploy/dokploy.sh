#!/usr/bin/env bash
# Fail-closed Dokploy delivery client. Never enable xtrace or print API bodies.
{ set +x; } 2>/dev/null
set -Eeuo pipefail
umask 077
export LC_ALL=C

SCRIPT_DIR=$(unset CDPATH && cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/dokploy/lib/common.sh
. "$SCRIPT_DIR/lib/common.sh"
# shellcheck source=deploy/dokploy/lib/api.sh
. "$SCRIPT_DIR/lib/api.sh"
# shellcheck source=deploy/dokploy/lib/release.sh
. "$SCRIPT_DIR/lib/release.sh"
# shellcheck source=deploy/dokploy/lib/review.sh
. "$SCRIPT_DIR/lib/review.sh"
# shellcheck source=deploy/dokploy/lib/integrations.sh
. "$SCRIPT_DIR/lib/integrations.sh"

usage() {
  cat >&2 <<'EOF'
usage: dokploy.sh COMMAND [OPTIONS]

Commands:
  release-id RELEASE
  current-release-id --compose-id ID
  current-source-commit --compose-id ID
  require-manual-current --compose-id ID
  staging-bootstrap-state --compose-id ID
  deploy-release RELEASE --compose-id ID (--site-compose-id ID | --skip-site) --app-role ROLE [OPTIONS]
    site target (required for both roles unless --skip-site):
      --site-url HTTPS_URL --site-dashboard-url HTTPS_URL --site-noindex true|false
    optional retainable evidence: --evidence-file PATH
  deploy-review [OPTIONS]
  count-reviews --base-repo REPO --environment-id ID [--exclude-pr PR]
  list-reviews --base-repo REPO --environment-id ID
  scrub-review [OPTIONS]
  retire-review [OPTIONS]
EOF
  return 2
}

fail() {
  printf '%s\n' "$1" >&2
  return 1
}

require_api_environment() {
  if [ -z "${DOKPLOY_URL-}" ]; then fail "DOKPLOY_URL required"; return 1; fi
  if [ -z "${DOKPLOY_TOKEN-}" ]; then fail "DOKPLOY_TOKEN required"; return 1; fi
  require_command curl || return 1
  require_command jq || return 1
}

require_object_id() {
  case "$2" in
    ''|*[!A-Za-z0-9_-]*) fail "$1 is invalid" ;;
    *)
      if [ "${#2}" -gt 128 ]; then fail "$1 is invalid"; fi
      ;;
  esac
}

require_release_id() {
  case "$1" in
    sha256:????????????????????????????????????????????????????????????????)
      case "${1#sha256:}" in *[!0-9a-f]*) fail "invalid release ID" ;; esac
      ;;
    *) fail "invalid release ID" ;;
  esac
}

require_app_role() {
  case "${1-}" in
    staging|prod) ;;
    *) fail "app role must be staging or prod" ;;
  esac
}

require_site_url() {
  local host
  case "${1-}" in https://*/*|https://|'') fail "site URL is invalid"; return 1 ;; https://*) ;; *) fail "site URL is invalid"; return 1 ;; esac
  host=${1#https://}
  case "$host" in ''|.*|*.) fail "site URL is invalid"; return 1 ;; *[!A-Za-z0-9.-]*) fail "site URL is invalid"; return 1 ;; esac
}

require_site_noindex() {
  case "${1-}" in true|false) ;; *) fail "site noindex value is invalid" ;; esac
}

# Dokploy layers project, environment, and Compose variables in that order.  Do
# not print any of those values: they can contain deployment secrets.
effective_compose_dashboard_host() {
  local state_file=$1 compose_id=$2
  jq -er --arg id "$compose_id" '
    if type != "object" or .composeId != $id or
       (.environment | type) != "object" or
       (.environment.project | type) != "object" or
       ([.environment.project.env, .environment.env, .env] | any(. != null and (type != "string")))
    then error("invalid Compose environment")
    else
      ([.environment.project.env // "", .environment.env // "", .env // ""]
       | join("\n") | split("\n")
       | map(select(startswith("BUILDHOUND_DASHBOARD_HOST=")) | .[26:])
       | if length == 0 then error("missing dashboard host") else last end
       | select(test("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$") )
      )
    end
  ' "$state_file"
}

# The evidence contains only release lineage and opaque Dokploy deployment IDs.
# Write via a same-directory temporary file so a failed job never leaves partial JSON.
write_release_evidence() {
  local path=$1 rid=$2 migration=$3 history=$4 compose=${5-} site=${6-}
  local directory temporary
  directory=$(dirname -- "$path") || return 1
  temporary=$(mktemp "$directory/.buildhound-evidence.XXXXXX") || return 1
  if ! jq -cn --arg rid "$rid" --arg migration "$migration" --arg history "$history" \
      --arg compose "$compose" --arg site "$site" '
      {releaseId:$rid,migrationId:$migration,
       migrationHistorySha256:(if $history == "" then null else $history end),
       composeDeploymentId:(if $compose == "" then null else $compose end),
       siteDeploymentId:(if $site == "" then null else $site end)}
    ' > "$temporary" || ! mv -f -- "$temporary" "$path"; then
    rm -f -- "$temporary"
    fail "unable to write deployment evidence"
    return 1
  fi
}

deployment_ids() {
  jq -ce '
    if type != "array" or any(.[];
      type != "object" or
      ((.deploymentId? // null) != null and
       ((.deploymentId | type) != "string" or
        (.deploymentId | test("^[A-Za-z0-9_-]{1,128}\\z") | not))))
    then error("invalid deployments")
    else [.[] | .deploymentId? | select(type == "string")] end
  '
}

# wait_for_deployment PATH OLD_IDS_JSON TITLE [OBSERVED_ID_FILE]
# Returns 42 for an exact failed terminal deployment and 1 for uncertainty.
wait_for_deployment() {
  local path=$1 old_ids=$2 title=$3 observed_id_file=${4-}
  local attempt=1 items matches count status deployment_id
  local deadline=$((SECONDS + 600)) remaining
  while [ "$attempt" -le 121 ]; do
    if ! items=$(dokploy_api GET "$path"); then return 1; fi
    if ! jq -e 'type == "array" and all(.[]; type == "object")' \
        >/dev/null 2>&1 <<<"$items"; then
      fail "Dokploy returned invalid deployment evidence"
      return 1
    fi
    if ! matches=$(jq -ce --arg title "$title" --argjson old "$old_ids" '
        [.[] | select(.title == $title and ((.deploymentId? // null) as $id | ($old | index($id)) == null))]
      ' <<<"$items"); then
      fail "Dokploy returned invalid deployment evidence"
      return 1
    fi
    count=$(jq -er 'length' <<<"$matches") || {
      fail "Dokploy returned invalid deployment evidence"
      return 1
    }
    if [ "$count" -gt 1 ]; then
      fail "new deployment evidence is ambiguous"
      return 1
    fi
    if [ "$count" -eq 1 ]; then
      deployment_id=$(jq -er '.[0].deploymentId | select(type == "string" and test("^[A-Za-z0-9_-]{1,128}\\z"))' \
        <<<"$matches") || {
          fail "matching deployment has an invalid or missing ID"
          return 1
        }
      if [ -n "$observed_id_file" ]; then
        # The caller keeps this in its private workspace and refreshes public
        # evidence before acting on a terminal status.
        printf '%s\n' "$deployment_id" > "$observed_id_file" || return 1
      fi
      status=$(jq -er '
        (.[0].status // "") as $status
        | if ($status | type) != "string" then "other"
          elif ($status | ascii_downcase | test("^(error|failed|cancelled|done|success)\\z"))
          then ($status | ascii_downcase)
          else "other"
          end
      ' <<<"$matches") || return 1
      case "$status" in
        error|failed|cancelled) return 42 ;;
        done|success)
          require_object_id "deployment ID" "$deployment_id" || return 1
          printf '%s\n' "$deployment_id"
          return 0
          ;;
      esac
    fi
    remaining=$((deadline - SECONDS))
    if [ "$attempt" -eq 121 ] || [ "$remaining" -le 0 ]; then break; fi
    if [ "$remaining" -gt 5 ]; then sleep 5; else sleep "$remaining"; fi
    attempt=$((attempt + 1))
  done
  fail "Dokploy deployment did not reach success within 10 minutes"
}

render_release_stack() {
  local manifest=$1 release=$2 rid=$3 output=$4 app_role=$5 placeholder
  local server_image backup_image postgres_image source_hash roundtrip_hash
  require_app_role "$app_role" || return 1
  server_image=$(jq -er '.serverImage' "$release") || return 1
  backup_image=$(jq -er '.backupImage' "$release") || return 1
  postgres_image=$(jq -er '.postgresImage' "$release") || return 1
  source_hash=$(sha256_file "$manifest") || return 1
  if ! jq -Rjs . "$manifest" > "$output"; then
    fail "trusted release Stack is not valid UTF-8"
    return 1
  fi
  roundtrip_hash=$(sha256_file "$output") || return 1
  if [ "$source_hash" != "$roundtrip_hash" ]; then
    fail "trusted release Stack is not valid UTF-8"
    return 1
  fi
  if ! jq -Rjs \
      --arg server "$server_image" --arg backup "$backup_image" \
      --arg postgres "$postgres_image" --arg release "$rid" --arg appRole "$app_role" '
        gsub("\\$\\{BUILDHOUND_SERVER_IMAGE\\}"; $server)
        | gsub("\\$\\{BUILDHOUND_BACKUP_IMAGE\\}"; $backup)
        | gsub("\\$\\{BUILDHOUND_POSTGRES_IMAGE\\}"; $postgres)
        | gsub("\\$\\{BUILDHOUND_RELEASE_ID:-manual\\}"; $release)
        | gsub("\\$\\{BUILDHOUND_APP_ROLE\\}"; $appRole)
      ' "$manifest" > "$output"; then
    fail "unable to render trusted release Stack"
    return 1
  fi
  for placeholder in \
      "\${BUILDHOUND_SERVER_IMAGE}" "\${BUILDHOUND_BACKUP_IMAGE}" \
      "\${BUILDHOUND_POSTGRES_IMAGE}" "\${BUILDHOUND_RELEASE_ID:-manual}" \
      "\${BUILDHOUND_APP_ROLE}"; do
    if LC_ALL=C grep -Fq -- "$placeholder" "$output"; then
      fail "trusted release Stack contains an unresolved release placeholder"
      return 1
    else
      local grep_rc=$?
      if [ "$grep_rc" -ne 1 ]; then
        fail "unable to inspect rendered release Stack"
        return 1
      fi
    fi
  done
}

# Render the trusted site Stack with the exact release values. Unlike the
# dashboard stacks, the site Stack is not part of the release BOM: it ships
# from the trusted workflow revision, and the site host, dashboard URL, and
# noindex value are substituted client-side so the stored composeFile is exact
# and readback-verifiable. ${DOKPLOY_INGRESS_NETWORK} intentionally stays
# Dokploy-environment interpolation, exactly like the dashboard stacks.
render_site_stack() {
  local manifest=${1-} site_image=${2-} app_role=${3-} site_url=${4-}
  local dashboard_url=${5-} noindex=${6-} output=${7-}
  local site_host source_hash roundtrip_hash placeholder
  if [ "$#" -ne 7 ]; then return 2; fi
  require_app_role "$app_role" || return 1
  require_site_url "$site_url" || return 1
  require_site_url "$dashboard_url" || return 1
  require_site_noindex "$noindex" || return 1
  site_host=${site_url#https://}
  source_hash=$(sha256_file "$manifest") || return 1
  if ! jq -Rjs . "$manifest" > "$output"; then
    fail "trusted site Stack is not valid UTF-8"
    return 1
  fi
  roundtrip_hash=$(sha256_file "$output") || return 1
  if [ "$source_hash" != "$roundtrip_hash" ]; then
    fail "trusted site Stack is not valid UTF-8"
    return 1
  fi
  if ! jq -Rjs \
      --arg site "$site_image" --arg appRole "$app_role" --arg host "$site_host" \
      --arg dashboard "$dashboard_url" --arg noindex "$noindex" '
        gsub("\\$\\{BUILDHOUND_SITE_IMAGE\\}"; $site)
        | gsub("\\$\\{BUILDHOUND_APP_ROLE\\}"; $appRole)
        | gsub("\\$\\{BUILDHOUND_SITE_HOST\\}"; $host)
        | gsub("\\$\\{BUILDHOUND_SITE_DASHBOARD_URL\\}"; $dashboard)
        | gsub("\\$\\{BUILDHOUND_SITE_NOINDEX\\}"; $noindex)
      ' "$manifest" > "$output"; then
    fail "unable to render trusted site Stack"
    return 1
  fi
  for placeholder in \
      "\${BUILDHOUND_SITE_IMAGE}" "\${BUILDHOUND_APP_ROLE}" \
      "\${BUILDHOUND_SITE_HOST}" "\${BUILDHOUND_SITE_DASHBOARD_URL}" \
      "\${BUILDHOUND_SITE_NOINDEX}"; do
    if LC_ALL=C grep -Fq -- "$placeholder" "$output"; then
      fail "trusted site Stack contains an unresolved site placeholder"
      return 1
    else
      local grep_rc=$?
      if [ "$grep_rc" -ne 1 ]; then
        fail "unable to inspect rendered site Stack"
        return 1
      fi
    fi
  done
}

# Full-body compose.update payload: every field that can replace or transform
# the protected raw Stack is cleared and reasserted on each release deploy.
compose_update_body() {
  local compose_id=${1-} stack_file=${2-}
  if [ "$#" -ne 2 ]; then return 2; fi
  jq -cn --arg id "$compose_id" --rawfile composeFile "$stack_file" '
    {
      composeId:$id,
      composeFile:$composeFile,
      composeType:"stack",
      sourceType:"raw",
      command:"",
      autoDeploy:false,
      enableSubmodules:false,
      composePath:"./docker-compose.yml",
      suffix:"",
      randomize:false,
      isolatedDeployment:false,
      isolatedDeploymentsVolume:false,
      triggerType:"push",
      watchPaths:[],
      repository:null,
      owner:null,
      branch:null,
      githubId:null,
      gitlabProjectId:null,
      gitlabRepository:null,
      gitlabOwner:null,
      gitlabBranch:null,
      gitlabPathNamespace:null,
      gitlabId:null,
      bitbucketRepository:null,
      bitbucketRepositorySlug:null,
      bitbucketOwner:null,
      bitbucketBranch:null,
      bitbucketId:null,
      giteaRepository:null,
      giteaOwner:null,
      giteaBranch:null,
      giteaId:null,
      customGitUrl:null,
      customGitBranch:null,
      customGitSSHKeyId:null
    }
  '
}

# Verify every persisted field that can replace or transform the protected raw
# Stack. Environment values are intentionally preserved byte-for-byte because
# the long-lived Stack consumes them during Dokploy's environment expansion.
require_exact_release_compose_state() {
  local state_file=${1-} original_file=${2-} stack_file=${3-}
  local compose_id=${4-} server_id=${5-}
  if [ "$#" -ne 5 ]; then return 2; fi
  jq -e --slurpfile original "$original_file" --rawfile composeFile "$stack_file" \
    --arg id "$compose_id" --arg serverId "$server_id" '
    . as $actual |
    $original[0] as $before |
    ($before | type) == "object" and
    (($before.appName | type) == "string") and
    (($before.environmentId | type) == "string") and
    (($before.name | type) == "string") and
    (($before.environment | type) == "object") and
    (($before.environment.project | type) == "object") and
    (["serverId","repository","owner","branch","githubId","gitlabProjectId",
      "gitlabRepository","gitlabOwner","gitlabBranch","gitlabPathNamespace",
      "gitlabId","bitbucketRepository","bitbucketRepositorySlug","bitbucketOwner",
      "bitbucketBranch","bitbucketId","giteaRepository","giteaOwner","giteaBranch",
      "giteaId","customGitUrl","customGitBranch","customGitSSHKeyId","github",
      "gitlab","bitbucket","gitea"] - ($actual | keys) | length) == 0 and
    ($actual | {
      composeId,composeFile,composeType,sourceType,command,autoDeploy,
      enableSubmodules,composePath,suffix,randomize,isolatedDeployment,
      isolatedDeploymentsVolume,triggerType,watchPaths,repository,owner,branch,
      githubId,gitlabProjectId,gitlabRepository,gitlabOwner,gitlabBranch,
      gitlabPathNamespace,gitlabId,bitbucketRepository,bitbucketRepositorySlug,
      bitbucketOwner,bitbucketBranch,bitbucketId,giteaRepository,giteaOwner,
      giteaBranch,giteaId,customGitUrl,customGitBranch,customGitSSHKeyId
    }) == {
      composeId:$id,composeFile:$composeFile,composeType:"stack",sourceType:"raw",
      command:"",autoDeploy:false,enableSubmodules:false,
      composePath:"./docker-compose.yml",suffix:"",randomize:false,
      isolatedDeployment:false,isolatedDeploymentsVolume:false,triggerType:"push",
      watchPaths:[],repository:null,owner:null,branch:null,githubId:null,
      gitlabProjectId:null,gitlabRepository:null,gitlabOwner:null,gitlabBranch:null,
      gitlabPathNamespace:null,gitlabId:null,bitbucketRepository:null,
      bitbucketRepositorySlug:null,bitbucketOwner:null,bitbucketBranch:null,
      bitbucketId:null,giteaRepository:null,giteaOwner:null,giteaBranch:null,
      giteaId:null,customGitUrl:null,customGitBranch:null,customGitSSHKeyId:null
    } and
    (if $serverId == "" then $actual.serverId == null
     else $actual.serverId == $serverId end) and
    ($actual.domains == []) and ($actual.mounts == []) and ($actual.backups == []) and
    ($actual.github == null) and ($actual.gitlab == null) and
    ($actual.bitbucket == null) and ($actual.gitea == null) and
    ($actual.appName == $before.appName) and
    ($actual.environmentId == $before.environmentId) and
    ($actual.name == $before.name) and
    ($actual.env == $before.env) and
    (($actual.environment | type) == "object") and
    (($actual.environment.project | type) == "object") and
    ($actual.environment.env == $before.environment.env) and
    ($actual.environment.project.env == $before.environment.project.env)
  ' "$state_file" >/dev/null 2>&1
}
cmd_release_id() {
  if [ "$#" -ne 1 ]; then usage; return 2; fi
  release_id "$1"
}

parse_single_compose_id() {
  if [ "$#" -ne 2 ] || [ "$1" != "--compose-id" ]; then usage; return 2; fi
  require_object_id "Compose ID" "$2" || return 2
  printf '%s\n' "$2"
}

cmd_current_release_id() {
  local compose_id deployments current
  compose_id=$(parse_single_compose_id "$@") || return
  require_api_environment || return 1
  deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$compose_id") || return
  current=$(current_release "$deployments") || return
  [ -n "$current" ] || { fail "no current successful release deployment found"; return 1; }
  jq -er '.releaseId' <<<"$current"
}

cmd_current_source_commit() {
  local compose_id deployments source_commit
  compose_id=$(parse_single_compose_id "$@") || return
  require_api_environment || return 1
  deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$compose_id") || return
  source_commit=$(current_release_source "$deployments") || return
  [ -n "$source_commit" ] || {
    fail "current successful deployment lacks tracked source lineage"
    return 1
  }
  printf '%s\n' "$source_commit"
}

cmd_require_manual_current() {
  local compose_id deployments
  compose_id=$(parse_single_compose_id "$@") || return
  require_api_environment || return 1
  deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$compose_id") || return
  require_manual_current "$deployments"
}

# Prints "established" when the latest successful deployment is a release, or
# "bootstrap" when the compose has never seen a successful release deployment
# and its latest successful deployment is the explicit plan-087 manual anchor.
# A manual deployment that merely supersedes an existing release history fails
# closed (bootstrap is one-shot), as does every other anchorless state, so the
# first automatic staging deploy cannot run against an unanchored compose.
cmd_staging_bootstrap_state() {
  local compose_id deployments current
  compose_id=$(parse_single_compose_id "$@") || return
  require_api_environment || return 1
  deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$compose_id") || return
  current=$(current_release "$deployments") || return
  if [ -n "$current" ]; then
    printf 'established\n'
    return 0
  fi
  if has_successful_release_deployment "$deployments"; then
    fail "manual deployment supersedes an existing release history; refusing automatic bootstrap"
    return 1
  fi
  require_manual_current "$deployments" || return 1
  printf 'bootstrap\n'
}

cmd_deploy_release() (
  if [ "$#" -lt 1 ]; then usage; return 2; fi
  local release=$1 compose_id='' site_compose_id='' base_repo=''
  local expected_current_release_id='' app_role='' site_url='' site_dashboard_url='' site_noindex='' evidence_file=''
  local manifest="$SCRIPT_DIR/stack.yaml" volume_guard="$SCRIPT_DIR/volume-guard.sh"
  local site_manifest="$SCRIPT_DIR/site-stack.yaml"
  local rollback_compatible=false bootstrap_manual_current=false skip_site=false
  local rid title expected manifest_hash guard_hash deployments current
  local old_compose old_site body server_image site_image registry_host release_image image_host key
  local compose_deployment site_deployment rc has_success compose_server_id
  local workdir stack_file site_stack_file compose_state_file compose_before_file
  local site_state_file site_before_file
  local compose_observed_file site_observed_file
  local migration history manifest_field compose_environment_id='' dashboard_host=''
  shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --base-repo|--compose-id|--site-compose-id|--app-role|--site-url|--site-dashboard-url|--site-noindex|--expected-current-release-id|--manifest|--site-manifest|--volume-guard|--evidence-file)
        if [ "$#" -lt 2 ]; then usage; return 2; fi
        case "$1" in
          --base-repo) base_repo=$2 ;;
          --compose-id) compose_id=$2 ;;
          --site-compose-id) site_compose_id=$2 ;;
          --app-role) app_role=$2 ;;
          --site-url) site_url=$2 ;;
          --site-dashboard-url) site_dashboard_url=$2 ;;
          --site-noindex) site_noindex=$2 ;;
          --expected-current-release-id) expected_current_release_id=$2 ;;
          --manifest) manifest=$2 ;;
          --site-manifest) site_manifest=$2 ;;
          --volume-guard) volume_guard=$2 ;;
          --evidence-file) evidence_file=$2 ;;
        esac
        shift 2
        ;;
      --rollback-compatible) rollback_compatible=true; shift ;;
      --bootstrap-manual-current) bootstrap_manual_current=true; shift ;;
      --skip-site) skip_site=true; shift ;;
      *) usage; return 2 ;;
    esac
  done
  # --skip-site remains an explicit target compatibility/emergency path; normal
  # delivery supplies its provisioned site Compose ID. The flag and a site
  # Compose ID are mutually exclusive so a skipped site deploy is always
  # visible — never a fallback.
  if [ "$skip_site" = true ] && [ -n "$site_compose_id" ]; then
    fail "skip-site conflicts with a site compose ID"
    return 2
  fi
  if [ "$skip_site" = true ] && { [ -n "$site_url" ] || [ -n "$site_dashboard_url" ] || [ -n "$site_noindex" ]; }; then
    fail "skip-site conflicts with site configuration"
    return 2
  fi
  if [ -z "$base_repo" ] || [ -z "$compose_id" ] || [ -z "$app_role" ]; then usage; return 2; fi
  require_app_role "$app_role" || return 2
  if [ -n "$evidence_file" ] && [ ! -d "$(dirname -- "$evidence_file")" ]; then
    fail "evidence file directory is missing"
    return 2
  fi
  # The site stack is client-rendered for both roles, so the site Compose ID
  # and all three site values are required whenever the site is not skipped.
  if [ "$skip_site" != true ] &&
     { [ -z "$site_compose_id" ] || [ -z "$site_url" ] || [ -z "$site_dashboard_url" ] || [ -z "$site_noindex" ]; }; then
    usage
    return 2
  fi
  require_object_id "Compose ID" "$compose_id" || return 2
  if [ "$skip_site" != true ]; then
    require_object_id "site Compose ID" "$site_compose_id" || return 2
    require_site_url "$site_url" || return 2
    require_site_url "$site_dashboard_url" || return 2
    require_site_noindex "$site_noindex" || return 2
  fi
  if [ -n "$expected_current_release_id" ]; then
    require_release_id "$expected_current_release_id" || return 2
  fi
  if [ "$bootstrap_manual_current" = true ] && [ -n "$expected_current_release_id" ]; then
    fail "manual bootstrap cannot name a release predecessor"
    return 2
  fi
  if [ ! -f "$release" ] || [ ! -f "$manifest" ] || [ ! -f "$volume_guard" ]; then
    fail "trusted release source is missing"
    return 1
  fi
  if [ "$skip_site" != true ] && [ ! -f "$site_manifest" ]; then
    fail "trusted release source is missing"
    return 1
  fi
  umask 077
  workdir=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/buildhound-release.XXXXXX") || {
    fail "unable to create private release workspace"
    return 1
  }
  BUILDHOUND_RELEASE_WORKDIR=$workdir
  trap 'if [[ -n ${BUILDHOUND_RELEASE_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_RELEASE_WORKDIR"; fi' EXIT
  trap 'if [[ -n ${BUILDHOUND_RELEASE_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_RELEASE_WORKDIR"; fi; exit 129' HUP
  trap 'if [[ -n ${BUILDHOUND_RELEASE_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_RELEASE_WORKDIR"; fi; exit 130' INT
  trap 'if [[ -n ${BUILDHOUND_RELEASE_WORKDIR-} ]]; then rm -rf -- "$BUILDHOUND_RELEASE_WORKDIR"; fi; exit 143' TERM
  if ! cp -- "$release" "$workdir/release.json" ||
     ! cp -- "$manifest" "$workdir/source-stack.yaml" ||
     ! cp -- "$volume_guard" "$workdir/volume-guard.sh"; then
    fail "unable to snapshot trusted release source"
    return 1
  fi
  if [ "$skip_site" != true ]; then
    if ! cp -- "$site_manifest" "$workdir/source-site-stack.yaml"; then
      fail "unable to snapshot trusted release source"
      return 1
    fi
    site_manifest=$workdir/source-site-stack.yaml
  fi
  release=$workdir/release.json
  manifest=$workdir/source-stack.yaml
  volume_guard=$workdir/volume-guard.sh
  stack_file=$workdir/rendered-stack.yaml
  site_stack_file=$workdir/rendered-site-stack.yaml
  compose_state_file=$workdir/compose.json
  compose_before_file=$workdir/compose-before.json
  site_state_file=$workdir/site-compose.json
  site_before_file=$workdir/site-compose-before.json
  compose_observed_file=$workdir/compose-observed-deployment-id
  site_observed_file=$workdir/site-observed-deployment-id

  release_validate "$release" || return 1
  rid=$(release_id "$release") || return
  manifest_hash=$(sha256_file "$manifest") || return
  guard_hash=$(sha256_file "$volume_guard") || return
  case "$app_role" in
    staging) manifest_field=stagingManifestSha256 ;;
    prod) manifest_field=productionManifestSha256 ;;
    *) fail "unsupported release application role"; return 1 ;;
  esac
  expected=$(jq -er --arg key "$manifest_field" '.[$key]' "$release") || return
  [ "$manifest_hash" = "$expected" ] || { fail "release manifest checksum differs from trusted stack"; return 1; }
  expected=$(jq -er '.volumeGuardSha256' "$release") || return
  [ "$guard_hash" = "$expected" ] || { fail "release volume guard checksum differs from trusted source"; return 1; }
  title=$(release_title "$release") || return
  migration=$(jq -er '.migrationId' "$release") || return
  history=$(jq -r '.migrationHistorySha256 // empty' "$release") || return
  if [ -n "$evidence_file" ]; then
    write_release_evidence "$evidence_file" "$rid" "$migration" "$history" '' '' || return 1
  fi

  require_api_environment || return 1
  server_image=$(jq -er '.serverImage' "$release") || return 1
  site_image=$(jq -er '.siteImage' "$release") || return 1
  registry_host=$(image_registry_host "$server_image") || return 1
  for key in siteImage backupImage postgresImage; do
    release_image=$(jq -er --arg key "$key" '.[$key]' "$release") || return 1
    image_host=$(image_registry_host "$release_image") || return 1
    if [ "$image_host" != "$registry_host" ]; then
      fail "release images do not share one tested registry"
      return 1
    fi
  done
  dokploy_api GET "compose.one?composeId=$compose_id" > "$compose_before_file" || return 1
  compose_server_id=$(jq -er --arg id "$compose_id" '
    if type != "object" or .composeId != $id or (has("serverId") | not) then
      error("invalid Compose target")
    elif .domains != [] or .mounts != [] or .backups != [] then
      error("attached Compose transforms are unsupported")
    elif ((.environment | type) != "object" or
          (.environment.project | type) != "object") then
      error("invalid Compose environment")
    elif .serverId == null then ""
    elif ((.serverId | type) == "string" and
          (.serverId | test("^[A-Za-z0-9_-]{1,128}\\z"))) then .serverId
    else error("invalid Compose server ID")
    end
  ' "$compose_before_file") || {
    fail "Dokploy returned an invalid Compose deployment target"
    return 1
  }
  if [ "$skip_site" != true ]; then
    compose_environment_id=$(jq -er --arg id "$compose_id" '
      if type != "object" or .composeId != $id or (.environmentId | type) != "string" or
         (.environmentId | test("^[A-Za-z0-9_-]{1,128}\\z") | not) then
        error("invalid Compose environment")
      else .environmentId end
    ' "$compose_before_file") || {
      fail "Dokploy returned an invalid Compose deployment target"
      return 1
    }
    dashboard_host=$(effective_compose_dashboard_host "$compose_before_file" "$compose_id") || {
      fail "Dokploy returned an invalid Compose dashboard host"
      return 1
    }
    if [ "$site_dashboard_url" != "https://$dashboard_host" ]; then
      fail "site dashboard URL does not match the release Compose dashboard host"
      return 1
    fi
  fi
  dokploy_require_integrations "$base_repo" "$registry_host" "$compose_server_id" || return 1
  if [ "$skip_site" != true ]; then
    # The site Compose must live in the release Compose's own environment and
    # on its exact manager: worker pull authorization rides the instance-level
    # registry preflight already proven for that manager, and routing, storage,
    # and backup behavior must come only from the client-rendered site Stack.
    dokploy_api GET "compose.one?composeId=$site_compose_id" > "$site_before_file" || return 1
    if ! jq -e --arg id "$site_compose_id" --arg environmentId "$compose_environment_id" \
        --arg serverId "$compose_server_id" '
      type == "object" and .composeId == $id and has("serverId") and
      .domains == [] and .mounts == [] and .backups == [] and
      (.environment | type) == "object" and
      (.environment.project | type) == "object" and
      .environmentId == $environmentId and
      (if $serverId == "" then .serverId == null else .serverId == $serverId end)
    ' "$site_before_file" >/dev/null; then
      fail "site Compose is not bound to the release Compose target"
      return 1
    fi
  fi
  deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$compose_id") || return
  if [ "$bootstrap_manual_current" = true ]; then require_manual_current "$deployments" || return; fi
  current=$(current_release "$deployments") || return
  has_success=$(jq -r '
    if type != "array" or any(.[]; type != "object") then error("invalid deployments")
    else any(.[];
      ((.status? | type) == "string") and
      (((.status? | ascii_downcase) == "done") or ((.status? | ascii_downcase) == "success")))
    end
  ' <<<"$deployments") || { fail "invalid Compose deployment evidence"; return 1; }
  if [ -z "$current" ] && [ "$has_success" = true ]; then
    if [ "$rollback_compatible" != true ] && [ "$bootstrap_manual_current" != true ]; then
      fail "successful current deployment lacks release/migration identity; compatibility attestation required"
      return 1
    fi
  fi
  require_migration_compatibility "$current" "$release" "$rollback_compatible" || return
  old_compose=$(deployment_ids <<<"$deployments") || { fail "invalid Compose deployment evidence"; return 1; }
  if [ "$skip_site" != true ]; then
    deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$site_compose_id") || return
    old_site=$(deployment_ids <<<"$deployments") || { fail "invalid site deployment evidence"; return 1; }
  fi
  render_release_stack "$manifest" "$release" "$rid" "$stack_file" "$app_role" || return
  if [ "$skip_site" != true ]; then
    render_site_stack "$site_manifest" "$site_image" "$app_role" "$site_url" \
      "$site_dashboard_url" "$site_noindex" "$site_stack_file" || return
  fi
  body=$(compose_update_body "$compose_id" "$stack_file") || return
  # Bind backup and rollback decisions to the exact predecessor observed by the
  # workflow. This is deliberately the final read before the first mutation.
  deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$compose_id") || return
  if [ "$bootstrap_manual_current" = true ]; then
    require_manual_current "$deployments" || return
  elif [ -n "$expected_current_release_id" ]; then
    current=$(current_release "$deployments") || return
    [ -n "$current" ] || {
      fail "current release changed after backup selection"
      return 1
    }
    expected=$(jq -er '.releaseId' <<<"$current") || return
    [ "$expected" = "$expected_current_release_id" ] || {
      fail "current release changed after backup selection"
      return 1
    }
  fi
  dokploy_api POST compose.update "$body" >/dev/null || return
  dokploy_api GET "compose.one?composeId=$compose_id" > "$compose_state_file" || return
  require_exact_release_compose_state "$compose_state_file" "$compose_before_file" \
    "$stack_file" "$compose_id" "$compose_server_id" || {
    fail "Dokploy persisted unexpected release Compose state"
    return 1
  }
  if [ "$app_role" = staging ]; then
    deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$compose_id") || return
    old_compose=$(deployment_ids <<<"$deployments") || {
      fail "invalid Compose deployment evidence"
      return 1
    }
  fi
  if [ "$skip_site" != true ]; then
    body=$(compose_update_body "$site_compose_id" "$site_stack_file") || return
    dokploy_api POST compose.update "$body" >/dev/null || return
    dokploy_api GET "compose.one?composeId=$site_compose_id" > "$site_state_file" || return
    require_exact_release_compose_state "$site_state_file" "$site_before_file" \
      "$site_stack_file" "$site_compose_id" "$compose_server_id" || {
      fail "Dokploy persisted unexpected site Compose state"
      return 1
    }
  fi
  if [ "$app_role" = staging ] && [ "$skip_site" != true ]; then
    # Close the attribution window after all staging update readbacks. Any
    # deployment already present here belongs to another actor, even if it uses
    # this release title.
    deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$compose_id") || return
    old_compose=$(deployment_ids <<<"$deployments") || {
      fail "invalid Compose deployment evidence"
      return 1
    }
  fi
  body=$(jq -cn --arg id "$compose_id" --arg title "$title" '{composeId:$id,title:$title}') || return
  dokploy_api POST compose.deploy "$body" >/dev/null || return
  if [ "$skip_site" != true ] && [ "$app_role" = prod ]; then
    # Preserve production's established joint-release behavior. Staging is
    # deliberately serialized so its Compose evidence precedes site mutation.
    body=$(jq -cn --arg id "$site_compose_id" --arg title "$title" '{composeId:$id,title:$title}') || return
    dokploy_api POST compose.deploy "$body" >/dev/null || return
  fi
  rc=0
  compose_deployment=$(wait_for_deployment "deployment.allByCompose?composeId=$compose_id" "$old_compose" "$title" "$compose_observed_file") || rc=$?
  if [ -s "$compose_observed_file" ]; then
    compose_deployment=$(<"$compose_observed_file")
    if [ -n "$evidence_file" ]; then
      write_release_evidence "$evidence_file" "$rid" "$migration" "$history" "$compose_deployment" '' || return 1
    fi
  fi
  if [ "$rc" -eq 42 ]; then
    fail "Dokploy deployment reached a failed terminal state"
    return 42
  elif [ "$rc" -ne 0 ]; then
    fail "Compose deployment failed or remained uncertain"
    return 1
  fi
  if [ -n "$evidence_file" ] && [ ! -s "$compose_observed_file" ]; then
    write_release_evidence "$evidence_file" "$rid" "$migration" "$history" "$compose_deployment" '' || return 1
  fi
  site_deployment=''
  if [ "$skip_site" != true ]; then
    if [ "$app_role" = staging ]; then
      # The Compose wait is an untrusted mutation window. Re-bind the site
      # Compose and its deployment baseline immediately before submitting the
      # staging site deploy.
      dokploy_api GET "compose.one?composeId=$site_compose_id" > "$site_state_file" || return
      require_exact_release_compose_state "$site_state_file" "$site_before_file" \
        "$site_stack_file" "$site_compose_id" "$compose_server_id" || {
        fail "site Compose drifted while the release Compose deployed"
        return 1
      }
      dokploy_api GET "compose.one?composeId=$compose_id" > "$compose_state_file" || return
      require_exact_release_compose_state "$compose_state_file" "$compose_before_file" \
        "$stack_file" "$compose_id" "$compose_server_id" || {
        fail "release Compose drifted while it deployed"
        return 1
      }
      dashboard_host=$(effective_compose_dashboard_host "$compose_state_file" "$compose_id") || {
        fail "Dokploy returned an invalid Compose dashboard host"
        return 1
      }
      if [ "$site_dashboard_url" != "https://$dashboard_host" ]; then
        fail "site dashboard URL no longer matches the release Compose dashboard host"
        return 1
      fi
      deployments=$(dokploy_api GET "deployment.allByCompose?composeId=$site_compose_id") || return
      old_site=$(deployment_ids <<<"$deployments") || {
        fail "invalid site deployment evidence"
        return 1
      }
      body=$(jq -cn --arg id "$site_compose_id" --arg title "$title" '{composeId:$id,title:$title}') || return
      dokploy_api POST compose.deploy "$body" >/dev/null || return
    fi
    rc=0
    site_deployment=$(wait_for_deployment "deployment.allByCompose?composeId=$site_compose_id" "$old_site" "$title" "$site_observed_file") || rc=$?
    if [ -s "$site_observed_file" ]; then
      site_deployment=$(<"$site_observed_file")
      if [ -n "$evidence_file" ]; then
        write_release_evidence "$evidence_file" "$rid" "$migration" "$history" "$compose_deployment" "$site_deployment" || return 1
      fi
    fi
    if [ "$rc" -eq 42 ]; then
      fail "Dokploy deployment reached a failed terminal state"
      return 42
    elif [ "$rc" -ne 0 ]; then
      fail "site deployment failed or remained uncertain"
      return 1
    fi
    if [ -n "$evidence_file" ] && [ ! -s "$site_observed_file" ]; then
      write_release_evidence "$evidence_file" "$rid" "$migration" "$history" "$compose_deployment" "$site_deployment" || return 1
    fi
    dokploy_api GET "compose.one?composeId=$site_compose_id" > "$site_state_file" || return
    require_exact_release_compose_state "$site_state_file" "$site_before_file" \
      "$site_stack_file" "$site_compose_id" "$compose_server_id" || {
      fail "deployed site Compose state differs from release"
      return 1
    }
  fi
  jq -cn --arg rid "$rid" \
    --arg migration "$migration" \
    --arg history "$history" \
    --arg compose "$compose_deployment" --arg site "$site_deployment" '
      {releaseId:$rid,migrationId:$migration,migrationHistorySha256:(if $history == "" then null else $history end),composeDeploymentId:$compose,siteDeploymentId:(if $site == "" then null else $site end)}
    '
)

cmd_deploy_review() {
  local base_repo='' head_repo='' pr='' sha='' state='' label_present=false
  local environment_id='' dns_suffix='' server_image='' site_image=''
  local attempt_id=''
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --label-present) label_present=true; shift ;;
      --base-repo|--head-repo|--pr|--sha|--state|--environment-id|--dns-suffix|--server-image|--site-image|--attempt-id)
        if [ "$#" -lt 2 ]; then usage; return 2; fi
        case "$1" in
          --base-repo) base_repo=$2 ;; --head-repo) head_repo=$2 ;; --pr) pr=$2 ;;
          --sha) sha=$2 ;; --state) state=$2 ;; --environment-id) environment_id=$2 ;;
          --dns-suffix) dns_suffix=$2 ;;
          --server-image) server_image=$2 ;; --site-image) site_image=$2 ;;
          --attempt-id) attempt_id=$2 ;;
        esac
        shift 2
        ;;
      *) usage; return 2 ;;
    esac
  done
  if [ -z "$base_repo" ] || [ -z "$head_repo" ] || [ -z "$pr" ] || [ -z "$sha" ] ||
     [ -z "$state" ] || [ -z "$environment_id" ] || [ -z "$dns_suffix" ] ||
     [ -z "$server_image" ] || [ -z "$site_image" ] || [ -z "$attempt_id" ]; then
    usage
    return 2
  fi
  review_validate_deploy_args "$base_repo" "$head_repo" "$pr" "$sha" "$state" "$label_present" \
    "$environment_id" "$dns_suffix" "$server_image" "$site_image" "$attempt_id" || return 1
  require_api_environment || return 1
  local server_registry site_registry
  server_registry=$(image_registry_host "$server_image") || return 1
  site_registry=$(image_registry_host "$site_image") || return 1
  if [ "$server_registry" != "$site_registry" ]; then
    fail "review images do not share one registry"
    return 1
  fi
  # The registry credential test refreshes manager login state, so pin the
  # installed Dokploy behavior before that first review-related mutation.
  # `warn` = this is the deploy entrypoint, the one place the plan-091
  # above-baseline tripwire should speak (lifecycle re-checks stay quiet).
  _review_require_supported_dokploy_version warn || return 1
  # Review Composes are constrained to the Dokploy manager (serverId null).
  dokploy_require_integrations "$base_repo" "$server_registry" "" || return 1
  deploy_review "$base_repo" "$head_repo" "$pr" "$sha" "$state" "$label_present" \
    "$environment_id" "$dns_suffix" "$server_image" "$site_image" "$attempt_id"
}

cmd_list_or_count_reviews() {
  local command=$1 base_repo='' environment_id='' exclude_pr=''
  shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --base-repo|--environment-id|--exclude-pr)
        if [ "$#" -lt 2 ]; then usage; return 2; fi
        case "$1" in --base-repo) base_repo=$2 ;; --environment-id) environment_id=$2 ;; --exclude-pr) exclude_pr=$2 ;; esac
        shift 2
        ;;
      *) usage; return 2 ;;
    esac
  done
  if [ -z "$base_repo" ] || [ -z "$environment_id" ]; then usage; return 2; fi
  if [ "$command" = list ]; then
    if [ -n "$exclude_pr" ]; then usage; return 2; fi
    require_api_environment || return 1
    list_reviews "$base_repo" "$environment_id"
  else
    require_api_environment || return 1
    count_reviews "$base_repo" "$environment_id" "$exclude_pr"
  fi
}

cmd_review_cleanup() {
  local command=$1 base_repo='' pr='' environment_id='' dns_suffix=''
  local compose_id='' sha='' attempt_id=''
  shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --base-repo|--pr|--environment-id|--dns-suffix|--expected-compose-id|--expected-sha|--expected-attempt-id)
        if [ "$#" -lt 2 ]; then usage; return 2; fi
        case "$1" in
          --base-repo) base_repo=$2 ;; --pr) pr=$2 ;; --environment-id) environment_id=$2 ;;
          --dns-suffix) dns_suffix=$2 ;; --expected-compose-id) compose_id=$2 ;; --expected-sha) sha=$2 ;;
          --expected-attempt-id) attempt_id=$2 ;;
        esac
        shift 2
        ;;
      *) usage; return 2 ;;
    esac
  done
  if [ -z "$base_repo" ] || [ -z "$pr" ] || [ -z "$environment_id" ] ||
     [ -z "$dns_suffix" ] || [ -z "$compose_id" ] || [ -z "$sha" ]; then
    usage
    return 2
  fi
  require_api_environment || return 1
  case "$command" in
    scrub)
      require_command docker || return 1
      scrub_review "$base_repo" "$pr" "$environment_id" "$dns_suffix" "$compose_id" "$sha" "$attempt_id"
      ;;
    retire)
      require_command docker || return 1
      retire_review "$base_repo" "$pr" "$environment_id" "$dns_suffix" "$compose_id" "$sha" "$attempt_id"
      ;;
  esac
}

main() {
  local command=${1-}
  [ -n "$command" ] || { usage; return; }
  shift
  case "$command" in
    release-id) cmd_release_id "$@" ;;
    current-release-id) cmd_current_release_id "$@" ;;
    staging-bootstrap-state) cmd_staging_bootstrap_state "$@" ;;
    current-source-commit) cmd_current_source_commit "$@" ;;
    require-manual-current) cmd_require_manual_current "$@" ;;
    deploy-release) cmd_deploy_release "$@" ;;
    deploy-review) cmd_deploy_review "$@" ;;
    count-reviews) cmd_list_or_count_reviews count "$@" ;;
    list-reviews) cmd_list_or_count_reviews list "$@" ;;
    scrub-review) cmd_review_cleanup scrub "$@" ;;
    retire-review) cmd_review_cleanup retire "$@" ;;
    *) usage ;;
  esac
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
  main "$@"
fi
