#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
# shellcheck disable=SC1091
source "$root/deploy/dokploy/lib/integrations.sh"

tmp=$(mktemp -d "${TMPDIR:-/tmp}/buildhound-integrations.XXXXXX")
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/runtime"
export RUNNER_TEMP=$tmp/runtime

fail() { printf 'integrations-test: %s\n' "$1" >&2; exit 1; }

export DOKPLOY_REGISTRY_ID=registry1
export DOKPLOY_GIT_PROVIDER_ID=provider1
mode=success
expected_server_id=

dokploy_api() {
  local method=$1 path=$2 body=${3-}
  printf '%s|%s\n' "$method" "$path" >> "$tmp/calls"
  case "$method|$path" in
    'GET|registry.one?registryId=registry1')
      if [[ $mode == registry_mismatch ]]; then
        printf '{"registryId":"registry1","registryUrl":"registry.example.test"}\n'
      else
        printf '{"registryId":"registry1","registryUrl":"ghcr.io","password":"never-log"}\n'
      fi
      ;;
    'POST|registry.testRegistryById')
      if [[ -n $expected_server_id ]]; then
        jq -e --arg serverId "$expected_server_id" \
          '. == {registryId:"registry1",serverId:$serverId}' >/dev/null <<< "$body" || return 90
      else
        jq -e '. == {registryId:"registry1"}' >/dev/null <<< "$body" || return 90
      fi
      if [[ $mode == registry_test_failure ]]; then printf 'false\n'; else printf 'true\n'; fi
      ;;
    'GET|github.githubProviders')
      if [[ $mode == provider_missing ]]; then
        printf '[]\n'
      else
        printf '[{"githubId":"github1","gitProvider":{"gitProviderId":"provider1"}}]\n'
      fi
      ;;
    'GET|github.getGithubRepositories?githubId=github1')
      if [[ $mode == repository_missing ]]; then
        printf '[{"full_name":"Another/Repository"}]\n'
      else
        printf '[{"full_name":"BuildHound/BuildHound"}]\n'
      fi
      ;;
    *) return 91 ;;
  esac
}

reset() { : > "$tmp/calls"; mode=$1; expected_server_id=${2-}; }
assert_fails() {
  local description=$1
  shift
  if "$@" >/dev/null 2>&1; then fail "$description"; fi
}

[[ $(image_registry_host 'ghcr.io/buildhound/server@sha256:1111111111111111111111111111111111111111111111111111111111111111') == ghcr.io ]] ||
  fail 'registry host was not derived from a digest reference'
assert_fails 'moving image tag accepted' image_registry_host 'ghcr.io/buildhound/server:latest'

reset success
dokploy_require_integrations BuildHound/BuildHound ghcr.io ""
expected=$(cat <<'EOF'
GET|registry.one?registryId=registry1
POST|registry.testRegistryById
GET|github.githubProviders
GET|github.getGithubRepositories?githubId=github1
EOF
)
[[ $(cat "$tmp/calls") == "$expected" ]] || fail 'integration preflight used unexpected API calls'
if find "$RUNNER_TEMP" -mindepth 1 -maxdepth 1 -name 'buildhound-integrations.*' -print -quit | grep -q .; then
  fail 'integration preflight left a private workspace behind'
fi

reset success server1
dokploy_require_integrations BuildHound/BuildHound ghcr.io server1
[[ $(cat "$tmp/calls") == "$expected" ]] || fail 'remote integration preflight used unexpected API calls'

for failure_mode in registry_mismatch registry_test_failure provider_missing repository_missing; do
  reset "$failure_mode"
  assert_fails "$failure_mode was accepted" \
    dokploy_require_integrations BuildHound/BuildHound ghcr.io ""
done

reset success
DOKPLOY_REGISTRY_ID=$'registry1\n'
assert_fails 'registry ID with newline accepted' \
  dokploy_require_integrations BuildHound/BuildHound ghcr.io ""
[[ ! -s $tmp/calls ]] || fail 'invalid registry ID reached Dokploy'

reset success
assert_fails 'invalid Compose server ID accepted' \
  dokploy_require_integrations BuildHound/BuildHound ghcr.io 'server1&registryId=attacker'
[[ ! -s $tmp/calls ]] || fail 'invalid Compose server ID reached Dokploy'

xtrace_secret='registry-password-must-not-leak'
if ! INTEGRATIONS_LIB="$root/deploy/dokploy/lib/integrations.sh" \
    XTRACE_SECRET="$xtrace_secret" bash -x -c '
      source "$INTEGRATIONS_LIB"
      export DOKPLOY_REGISTRY_ID=registry1 DOKPLOY_GIT_PROVIDER_ID=provider1
      dokploy_api() {
        case "$1|$2" in
          "GET|registry.one?registryId=registry1")
            printf "{\"registryId\":\"registry1\",\"registryUrl\":\"ghcr.io\",\"password\":\"%s\"}\\n" "$XTRACE_SECRET" ;;
          "POST|registry.testRegistryById") printf "true\\n" ;;
          "GET|github.githubProviders") printf "[{\"githubId\":\"github1\",\"gitProvider\":{\"gitProviderId\":\"provider1\"}}]\\n" ;;
          "GET|github.getGithubRepositories?githubId=github1") printf "[{\"full_name\":\"BuildHound/BuildHound\"}]\\n" ;;
          *) return 90 ;;
        esac
      }
      dokploy_require_integrations BuildHound/BuildHound ghcr.io ""
    ' > "$tmp/xtrace-stdout" 2> "$tmp/xtrace-stderr"; then
  fail 'xtrace preflight probe failed'
fi
if grep -Fq -- "$xtrace_secret" "$tmp/xtrace-stdout" "$tmp/xtrace-stderr"; then
  fail 'inherited xtrace exposed registry credentials'
fi
if find "$RUNNER_TEMP" -mindepth 1 -maxdepth 1 -name 'buildhound-integrations.*' -print -quit | grep -q .; then
  fail 'xtrace preflight left a private workspace behind'
fi

printf 'Dokploy integrations validated\n'
