#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
# shellcheck disable=SC1091
source "$root/deploy/dokploy/lib/review.sh"

test_root=$(mktemp -d)
trap 'rm -rf -- "$test_root"' EXIT
export TEST_ROOT=$test_root
export RUNNER_TEMP=$test_root
export REPO=BuildHound/BuildHound
export SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
export ATTEMPT=12345.1
export TITLE="$SHA|$ATTEMPT"
export OWNERSHIP='{"attemptId":"12345.1","repository":"BuildHound/BuildHound","pr":42,"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
export PROVIDER=bh-2e4f87f1c5b0890580d952d3-mr42
export BUILDHOUND_REVIEW_DB_PASSWORD=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
export BUILDHOUND_REVIEW_TOKEN=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc

fail() {
  printf 'review-client-test: %s\n' "$1" >&2
  exit 1
}

assert_eq() {
  [[ $1 == "$2" ]] || fail "expected [$2], got [$1]"
}

assert_log_has() {
  grep -F -- "$1" "$TEST_ROOT/calls.log" >/dev/null || fail "missing API call: $1"
}

assert_log_lacks() {
  if grep -F -- "$1" "$TEST_ROOT/calls.log" >/dev/null; then
    fail "unexpected API call: $1"
  fi
}

reset_fake() {
  find "$TEST_ROOT" -mindepth 1 -maxdepth 1 -type f -delete
  : > "$TEST_ROOT/calls.log"
  export FAKE_MODE=$1
}

fake_owned_environment() {
  if [[ -f $TEST_ROOT/deleted ]]; then
    printf '{"compose":[]}\n'
    return
  fi
  jq -cn --arg description "$OWNERSHIP" '
    {compose:[{name:"mr42",composeId:"c1",serverId:null,description:$description,
               createdAt:"2026-07-13T12:00:00Z"}]}
  '
}

fake_persisted_compose() {
  local app_name=$PROVIDER-Ab12Cd
  if [[ -f $TEST_ROOT/create.json ]]; then
    app_name=$(jq -er '.appName + "-Ab12Cd"' "$TEST_ROOT/create.json")
  fi
  if [[ $FAKE_MODE == appname_newline ]]; then
    app_name+=$'\n'
  fi
  jq -c --arg appName "$app_name" '
    . + {
      environmentId:"env1",name:"mr42",appName:$appName,serverId:null,composeStatus:"idle",
      domains:[],mounts:[],backups:[],github:null,gitlab:null,bitbucket:null,gitea:null,server:null,
      environment:{environmentId:"env1",env:null,project:{env:null}}
    }
  ' "$TEST_ROOT/update.json"
}

dokploy_api() {
  local method=${1-} path=${2-} body=${3-}
  printf '%s|%s\n' "$method" "$path" >> "$TEST_ROOT/calls.log"
  case "$method|$path" in
    'GET|environment.one?environmentId=env1')
      case "$FAKE_MODE" in
        ambiguity)
          jq -cn --arg description "$OWNERSHIP" '
            {compose:[
              {name:"mr42",composeId:"c1",serverId:null,description:$description},
              {name:"mr42",composeId:"c2",serverId:null,description:$description}
            ]}
          '
          ;;
        invalid_metadata)
          printf '{"compose":[{"name":"mr42","composeId":"c1","description":"not-json"}]}\n'
          ;;
        list_good)
          jq -cn --arg sha "$SHA" '
            {compose:[
              {composeId:"c1",createdAt:"one",description:({repository:"BuildHound/BuildHound",pr:42,sha:$sha}|tojson)},
              {composeId:"c2",createdAt:"two",description:({repository:"BuildHound/BuildHound",pr:43,sha:$sha}|tojson)},
              {composeId:"c3",createdAt:"three",description:({repository:"Another/Repo",pr:44,sha:$sha}|tojson)}
            ]}
          '
          ;;
        cleanup|route_mixed|delete|running|no_row)
          fake_owned_environment
          ;;
        *)
          if [[ -f $TEST_ROOT/created ]]; then fake_owned_environment; else printf '{"compose":[]}\n'; fi
          ;;
      esac
      ;;
    'POST|compose.create')
      printf '%s\n' "$body" > "$TEST_ROOT/create.json"
      : > "$TEST_ROOT/created"
      printf '{"composeId":"c1"}\n'
      ;;
    'POST|compose.update')
      printf '%s\n' "$body" > "$TEST_ROOT/update.json"
      printf '{}\n'
      ;;
    'GET|compose.one?composeId=c1')
      if [[ $FAKE_MODE == hidden && ! -f $TEST_ROOT/cleanup_started ]]; then
        fake_persisted_compose | jq -c '.command="curl attacker.invalid"'
      else
        if [[ -f $TEST_ROOT/update.json ]]; then
          fake_persisted_compose
        else
          printf '{"composeId":"c1","composeStatus":"idle"}\n'
        fi
      fi
      ;;
    'POST|compose.deploy')
      : > "$TEST_ROOT/deploy_started"
      printf '{}\n'
      ;;
    'GET|deployment.allByCompose?composeId=c1')
      if [[ ! -f $TEST_ROOT/deploy_started ]]; then
        case "$FAKE_MODE" in
          cleanup|route_mixed|delete)
            printf '[{"deploymentId":"d1","title":"%s","status":"done"}]\n' "$TITLE"
            ;;
          running)
            printf '[{"deploymentId":"d1","title":"%s","status":"running"}]\n' "$TITLE"
            ;;
          no_row) printf '[]\n' ;;
          *) printf '[]\n' ;;
        esac
      else
        case "$FAKE_MODE" in
          terminal|terminal_cleanup_fail) printf '[{"deploymentId":"d1","title":"%s","status":"failed"}]\n' "$TITLE" ;;
          terminal_invalid_id) printf '[{"title":"%s","status":"failed"}]\n' "$TITLE" ;;
          uncertain)
            printf '[{"deploymentId":"d1","title":"%s","status":"success"},{"deploymentId":"d2","title":"%s","status":"success"}]\n' "$TITLE" "$TITLE"
            ;;
          *) printf '[{"deploymentId":"d1","title":"%s","status":"success"}]\n' "$TITLE" ;;
        esac
      fi
      ;;
    'POST|compose.cleanQueues')
      if [[ $FAKE_MODE == terminal_cleanup_fail ]]; then
        return 91
      fi
      : > "$TEST_ROOT/cleanup_started"
      printf '{}\n'
      ;;
    'POST|compose.stop')
      : > "$TEST_ROOT/stopped"
      printf '{}\n'
      ;;
    'POST|compose.delete')
      : > "$TEST_ROOT/deleted"
      printf '{}\n'
      ;;
    *)
      printf 'unexpected fake Dokploy call: %s %s\n' "$method" "$path" >&2
      return 90
      ;;
  esac
}

curl() {
  local last=
  for last in "$@"; do :; done
  printf '%s\n' "$last" >> "$TEST_ROOT/curl.log"
  if [[ $FAKE_MODE == route_mixed && $last == *dashboard* ]]; then
    printf '200'
  else
    printf '404'
  fi
}

sleep() { :; }

assert_eq "$(review_name 42)" mr42
if review_name 2147483648 >/dev/null 2>&1; then fail 'unsafe JSON PR number accepted'; fi
assert_eq "$(review_provider_id "$REPO" 42)" "$PROVIDER"
assert_eq "$(review_hosts mr42 reviews.example.test)" 'mr42.reviews.example.test|mr42.dashboard.reviews.example.test'
if review_hosts mr42 'reviews.example.test/path' >/dev/null 2>&1; then fail 'invalid DNS suffix accepted'; fi
bad_ownership=$(jq -cn --arg repository "$REPO"$'\n' --arg sha "$SHA" \
  '{repository:$repository,pr:42,sha:$sha} | tojson')
bad_environment=$(jq -cn --arg description "$bad_ownership" \
  '{compose:[{name:"mr42",composeId:"c1",description:$description}]}')
if _review_owned_items "$bad_environment" mr42 "$REPO" 42 "$SHA" >/dev/null 2>&1; then
  fail 'ownership repository with trailing newline accepted'
fi

deploy_args=(
  "$REPO" "$REPO" 42 "$SHA" open true env1 reviews.example.test
  buildhound-review-ingress
  "ghcr.io/buildhound/server@sha256:$(printf '1%.0s' {1..64})"
  "ghcr.io/buildhound/site@sha256:$(printf '2%.0s' {1..64})"
  "$ATTEMPT"
)

reset_fake success
result=$(deploy_review "${deploy_args[@]}")
jq -e --arg provider "$PROVIDER" '
  .name == "mr42" and .composeId == "c1" and .deploymentId == "d1"
' >/dev/null <<< "$result"
jq -e --arg password "$BUILDHOUND_REVIEW_DB_PASSWORD" --arg token "$BUILDHOUND_REVIEW_TOKEN" '
  .sourceType == "raw" and .composeType == "stack" and .command == "" and .env == "" and
  .autoDeploy == false and .randomize == false and .isolatedDeployment == false and
  .githubId == null and .gitlabId == null and .bitbucketId == null and .giteaId == null and
  (.composeFile | contains($password)) and (.composeFile | contains($token))
' "$TEST_ROOT/update.json" >/dev/null
jq -e '.description | fromjson | .attemptId == "12345.1" and .repository == "BuildHound/BuildHound" and .pr == 42 and .sha == "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"' \
  "$TEST_ROOT/update.json" >/dev/null
assert_log_has 'POST|compose.update'
assert_log_has 'POST|compose.deploy'
assert_log_lacks 'POST|compose.stop'

reset_fake ambiguity
if deploy_review "${deploy_args[@]}" >/dev/null 2>&1; then fail 'ambiguous ownership accepted'; fi
assert_log_lacks 'POST|compose.create'
assert_log_lacks 'POST|compose.update'

reset_fake cleanup
if deploy_review "${deploy_args[@]}" >/dev/null 2> "$TEST_ROOT/error.log"; then
  fail 'same-SHA redeploy was accepted'
fi
grep -F 'same-SHA review redeploys are unsupported' "$TEST_ROOT/error.log" >/dev/null
assert_log_lacks 'POST|compose.update'

bad_args=("${deploy_args[@]}")
bad_args[8]=dokploy-network
reset_fake success
if deploy_review "${bad_args[@]}" >/dev/null 2>&1; then fail 'unapproved ingress accepted'; fi
assert_log_lacks 'GET|environment.one'

reset_fake hidden
if deploy_review "${deploy_args[@]}" >/dev/null 2>&1; then fail 'hidden persisted state accepted'; fi
assert_log_lacks 'POST|compose.deploy'
assert_log_has 'POST|compose.cleanQueues'
assert_log_has 'POST|compose.stop'
[[ ! -f $TEST_ROOT/deleted ]] || fail 'failed deployment cleanup deleted its ownership record'

reset_fake appname_newline
if deploy_review "${deploy_args[@]}" >/dev/null 2>&1; then
  fail 'persisted appName with trailing newline accepted'
fi
assert_log_lacks 'POST|compose.deploy'

reset_fake terminal
if deploy_review "${deploy_args[@]}" >/dev/null 2> "$TEST_ROOT/error.log"; then
  fail 'failed terminal deployment reported success'
else
  terminal_rc=$?
fi
assert_eq "$terminal_rc" 42
assert_log_has 'POST|compose.deploy'
assert_log_has 'POST|compose.cleanQueues'
assert_log_has 'POST|compose.stop'
[[ ! -f $TEST_ROOT/deleted ]] || fail 'terminal cleanup deleted its ownership record'

reset_fake terminal_cleanup_fail
if deploy_review "${deploy_args[@]}" >/dev/null 2> "$TEST_ROOT/error.log"; then
  fail 'terminal deployment with failed cleanup reported success'
else
  terminal_cleanup_rc=$?
fi
assert_eq "$terminal_cleanup_rc" 1
grep -F 'exact-owned cleanup failed' "$TEST_ROOT/error.log" >/dev/null
assert_log_has 'POST|compose.cleanQueues'
assert_log_lacks 'POST|compose.stop'

reset_fake terminal_invalid_id
if deploy_review "${deploy_args[@]}" >/dev/null 2> "$TEST_ROOT/error.log"; then
  fail 'terminal deployment with invalid ID reported success'
else
  terminal_invalid_rc=$?
fi
assert_eq "$terminal_invalid_rc" 1
grep -F 'invalid or missing ID' "$TEST_ROOT/error.log" >/dev/null
grep -F 'preserving exact-owned reconciliation anchor' "$TEST_ROOT/error.log" >/dev/null
assert_log_has 'POST|compose.deploy'
assert_log_lacks 'POST|compose.cleanQueues'
assert_log_lacks 'POST|compose.stop'

reset_fake uncertain
if deploy_review "${deploy_args[@]}" >/dev/null 2> "$TEST_ROOT/error.log"; then
  fail 'ambiguous deployment evidence reported success'
fi
grep -F 'preserving exact-owned reconciliation anchor' "$TEST_ROOT/error.log" >/dev/null
assert_log_has 'POST|compose.deploy'
assert_log_lacks 'POST|compose.cleanQueues'
assert_log_lacks 'POST|compose.stop'

reset_fake list_good
reviews=$(list_reviews "$REPO" env1 42)
jq -e 'length == 1 and .[0].pr == 43 and .[0].composeId == "c2" and .[0].attemptId == null' \
  >/dev/null <<< "$reviews"
assert_eq "$(count_reviews "$REPO" env1 42)" 1

reset_fake invalid_metadata
if list_reviews "$REPO" env1 >/dev/null 2>&1; then fail 'invalid ownership metadata was listed'; fi
if count_reviews "$REPO" env1 >/dev/null 2>&1; then fail 'invalid ownership metadata was counted'; fi

reset_fake cleanup
result=$(revoke_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" "$ATTEMPT")
assert_eq "$result" '{"revoked": "mr42"}'
jq -e '.revoked == "mr42"' >/dev/null <<< "$result"
assert_log_has 'POST|compose.stop'
test "$(grep -c '^https://' "$TEST_ROOT/curl.log")" -eq 2
grep -Fx 'https://mr42.reviews.example.test/' "$TEST_ROOT/curl.log" >/dev/null
grep -Fx 'https://mr42.dashboard.reviews.example.test/health' "$TEST_ROOT/curl.log" >/dev/null

reset_fake cleanup
if revoke_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" 12345.2 >/dev/null 2>&1; then
  fail 'cleanup accepted a different review attempt'
fi
assert_log_lacks 'POST|compose.cleanQueues'
assert_log_lacks 'POST|compose.stop'

reset_fake route_mixed
if revoke_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" "$ATTEMPT" >/dev/null 2>&1; then
  fail 'cleanup accepted a still-reachable dashboard route'
fi
[[ ! -f $TEST_ROOT/deleted ]] || fail 'route convergence failure deleted ownership record'

reset_fake running
if revoke_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" "$ATTEMPT" >/dev/null 2>&1; then
  fail 'cleanup raced a running deployment'
fi
assert_log_has 'POST|compose.cleanQueues'
assert_log_lacks 'POST|compose.stop'

reset_fake no_row
result=$(revoke_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" "$ATTEMPT")
jq -e '.revoked == "mr42"' >/dev/null <<< "$result"
test "$(grep -c '^POST|compose.cleanQueues$' "$TEST_ROOT/calls.log")" -eq 6 || \
  fail 'no-row cleanup did not repeatedly drain the queue'
assert_log_has 'POST|compose.stop'

reset_fake delete
result=$(delete_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" "$ATTEMPT")
assert_eq "$result" '{"deleted": "mr42"}'
jq -e '.deleted == "mr42"' >/dev/null <<< "$result"
assert_log_has 'POST|compose.delete'
[[ -f $TEST_ROOT/deleted ]] || fail 'delete did not reach fake Dokploy'

if find "$TEST_ROOT" -maxdepth 1 -type d -name 'buildhound-review.*' | grep -q .; then
  fail 'review client left a private workspace behind'
fi
if find "$TEST_ROOT" -maxdepth 1 -type d -name 'buildhound-review-revoke.*' | grep -q .; then
  fail 'review revocation left a private workspace behind'
fi

printf 'review shell client validated\n'
