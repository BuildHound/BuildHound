#!/usr/bin/env bash
{ set +x; } 2>/dev/null

_integrations_lib_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "$_integrations_lib_dir/common.sh"
unset _integrations_lib_dir

_dokploy_integration_id() {
  [[ ${1-} =~ ^[A-Za-z0-9_-]{1,128}$ ]]
}

_dokploy_repository() {
  [[ ${1-} =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]
}

image_registry_host() {
  local image=${1-} name host
  if ! is_digest_reference "$image"; then
    die "digest-addressed image required for registry preflight"
    return 1
  fi
  name=${image%@sha256:*}
  case "$name" in
    */*) host=${name%%/*} ;;
    *) host=docker.io ;;
  esac
  case "$host" in
    ''|.*|*.|*[!A-Za-z0-9.:-]*|*:*:*)
      die "image has an invalid registry host"
      return 1
      ;;
  esac
  printf '%s\n' "$host"
}

# Prove that the protected IDs supplied by Actions identify a working registry
# and a GitHub provider that can read the repository. Raw Stack delivery remains
# immutable/provider-free; the provider is a control-plane provenance preflight.
dokploy_require_integrations() (
  local repository=${1-} registry_host=${2-} server_id=${3-}
  local registry_id=${DOKPLOY_REGISTRY_ID-}
  local git_provider_id=${DOKPLOY_GIT_PROVIDER_ID-}
  local workdir registry_file result_file providers_file repositories_file
  local github_id body

  BUILDHOUND_INTEGRATIONS_WORKDIR=

  if [[ $# -ne 3 ]] || ! _dokploy_repository "$repository"; then
    die "valid repository required for Dokploy integration preflight"
    return 1
  fi
  if ! _dokploy_integration_id "$registry_id"; then
    die "DOKPLOY_REGISTRY_ID is invalid"
    return 1
  fi
  if ! _dokploy_integration_id "$git_provider_id"; then
    die "DOKPLOY_GIT_PROVIDER_ID is invalid"
    return 1
  fi
  if [[ -n $server_id ]] && ! _dokploy_integration_id "$server_id"; then
    die "Dokploy Compose server ID is invalid"
    return 1
  fi
  case "$registry_host" in
    ''|.*|*.|*[!A-Za-z0-9.:-]*|*:*:*)
      die "registry host is invalid"
      return 1
      ;;
  esac

  umask 077
  # Invoked indirectly by the EXIT trap below.
  # shellcheck disable=SC2317,SC2329
  _dokploy_integrations_cleanup() {
    if [ -n "${BUILDHOUND_INTEGRATIONS_WORKDIR-}" ]; then
      rm -rf -- "$BUILDHOUND_INTEGRATIONS_WORKDIR" 2>/dev/null
    fi
  }
  trap _dokploy_integrations_cleanup EXIT
  trap '_dokploy_integrations_cleanup; exit 129' HUP
  trap '_dokploy_integrations_cleanup; exit 130' INT
  trap '_dokploy_integrations_cleanup; exit 143' TERM

  if ! BUILDHOUND_INTEGRATIONS_WORKDIR=$(mktemp -d \
      "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/buildhound-integrations.XXXXXX" 2>/dev/null); then
    die "unable to create private integration workspace"
    return 1
  fi
  workdir=$BUILDHOUND_INTEGRATIONS_WORKDIR
  registry_file=$workdir/registry.json
  result_file=$workdir/registry-test.json
  providers_file=$workdir/github-providers.json
  repositories_file=$workdir/github-repositories.json

  dokploy_api GET "registry.one?registryId=$registry_id" > "$registry_file" || return 1
  if ! jq -e --arg id "$registry_id" --arg host "$registry_host" '
    type == "object" and .registryId == $id and .registryUrl == $host
  ' "$registry_file" >/dev/null; then
    die "Dokploy registry ID does not match the image registry"
    return 1
  fi
  if [[ -n $server_id ]]; then
    body=$(jq -cn --arg registryId "$registry_id" --arg serverId "$server_id" \
      '{registryId:$registryId,serverId:$serverId}') || return 1
  else
    body=$(jq -cn --arg registryId "$registry_id" '{registryId:$registryId}') || return 1
  fi
  dokploy_api POST registry.testRegistryById "$body" > "$result_file" || return 1
  if ! jq -e '. == true' "$result_file" >/dev/null; then
    die "Dokploy registry credential test returned unexpected evidence"
    return 1
  fi

  dokploy_api GET github.githubProviders > "$providers_file" || return 1
  github_id=$(jq -er --arg id "$git_provider_id" '
    if type != "array" then error("invalid GitHub provider response") else
      [.[] |
        select(type == "object" and
               (.gitProvider | type) == "object" and
               .gitProvider.gitProviderId == $id)] as $matches
      | if ($matches | length) != 1 then error("Git provider is missing or ambiguous")
        else $matches[0].githubId
          | select(type == "string" and test("^[A-Za-z0-9_-]{1,128}\\z"))
        end
    end
  ' "$providers_file") || {
    die "Dokploy Git provider ID is missing, ambiguous, or invalid"
    return 1
  }
  dokploy_api GET "github.getGithubRepositories?githubId=$github_id" > "$repositories_file" || return 1
  if ! jq -e --arg repository "$repository" '
    type == "array" and any(.[]; type == "object" and .full_name == $repository)
  ' "$repositories_file" >/dev/null; then
    die "Dokploy Git provider cannot access the deployment repository"
    return 1
  fi
)
