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
export OWNERSHIP='{"activatedAt":"2026-07-13T12:00:00Z","attemptId":"12345.1","repository":"BuildHound/BuildHound","pr":42,"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
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
  local description=$OWNERSHIP
  if [[ -f $TEST_ROOT/deleted ]]; then
    printf '{"compose":[]}\n'
    return
  fi
  if [[ -f $TEST_ROOT/compose-state.json ]]; then
    description=$(jq -er .description "$TEST_ROOT/compose-state.json")
  fi
  jq -cn --arg description "$description" '
    {compose:[{name:"mr42",composeId:"c1",serverId:null,description:$description,
               createdAt:"2026-07-13T12:00:00Z"}]}
  '
}

# Sets FAKE_APP_NAME instead of printing it: the appname_newline variant
# carries a trailing newline that command substitution would strip.
fake_app_name() {
  FAKE_APP_NAME=$PROVIDER-Ab12Cd
  if [[ -f $TEST_ROOT/create.json ]]; then
    FAKE_APP_NAME=$(jq -er '.appName + "-Ab12Cd"' "$TEST_ROOT/create.json")
  fi
  if [[ $FAKE_MODE == appname_newline ]]; then
    FAKE_APP_NAME+=$'\n'
  fi
}

fake_persisted_compose() {
  local app_name
  fake_app_name
  app_name=$FAKE_APP_NAME
  jq -c --arg appName "$app_name" '
    . + {
      environmentId:"env1",name:"mr42",appName:$appName,serverId:null,composeStatus:"idle",
      isolatedDeployment:(if has("isolatedDeployment") then .isolatedDeployment else true end),
      domains:[],mounts:[],backups:[],github:null,gitlab:null,bitbucket:null,gitea:null,server:null,
      environment:{environmentId:"env1",env:null,project:{env:null}}
    }
  ' "$TEST_ROOT/compose-state.json"
}

# Emulates Dokploy's isolated-deployment conversion of the live review stack:
# services keep their labels and long-form tmpfs mounts and gain the injected
# per-compose network. FAKE_MODE variants reproduce the routing defects the
# deploy-path verifier must reject.
# Intentional literal backticks: Traefik Host() rule syntax.
# shellcheck disable=SC2016
fake_converted_stack() {
  local app swarm_site swarm_server docker_label site_volumes db_networks extra_network
  fake_app_name
  app=$FAKE_APP_NAME
  swarm_site="      - traefik.swarm.network=$app"
  swarm_server="      - traefik.swarm.network=$app"
  docker_label=''
  site_volumes='    volumes:
      - type: tmpfs
        target: /tmp
        tmpfs:
          size: 33554432'
  db_networks="    networks:
      - $app"
  extra_network=''
  case "$FAKE_MODE" in
    stack_missing_swarm_label) swarm_site='' ;;
    stack_dual_network) docker_label="      - traefik.docker.network=$app" ;;
    stack_short_tmpfs) site_volumes='    tmpfs:
      - /tmp' ;;
    stack_extra_network)
      db_networks="    networks:
      - $app
      - dokploy-network"
      extra_network='  dokploy-network:
    name: dokploy-network
    external: true'
      ;;
  esac
  {
    printf 'version: "3.8"\nservices:\n'
    printf '  site:\n'
    printf '    image: ghcr.io/buildhound/site@sha256:%s\n' "$(printf '2%.0s' {1..64})"
    [[ -z $site_volumes ]] || printf '%s\n' "$site_volumes"
    printf '    networks:\n      - %s\n' "$app"
    printf '    deploy:\n      labels:\n'
    printf '      - traefik.enable=true\n'
    [[ -z $swarm_site ]] || printf '%s\n' "$swarm_site"
    [[ -z $docker_label ]] || printf '%s\n' "$docker_label"
    printf '      - traefik.http.routers.%s-site.rule=Host(`mr42.reviews.example.test`)\n' "$PROVIDER"
    printf '      - traefik.http.services.%s-site.loadbalancer.server.port=8080\n' "$PROVIDER"
    printf '  server:\n'
    printf '    image: ghcr.io/buildhound/server@sha256:%s\n' "$(printf '1%.0s' {1..64})"
    printf '    volumes:\n      - type: tmpfs\n        target: /tmp\n        tmpfs:\n          size: 67108864\n'
    printf '    networks:\n      - %s\n' "$app"
    printf '    deploy:\n      labels:\n'
    printf '      - traefik.enable=true\n'
    [[ -z $swarm_server ]] || printf '%s\n' "$swarm_server"
    printf '      - traefik.http.routers.%s-server.rule=Host(`mr42.dashboard.reviews.example.test`)\n' "$PROVIDER"
    printf '      - traefik.http.services.%s-server.loadbalancer.server.port=8080\n' "$PROVIDER"
    printf '  db:\n'
    printf '    image: timescale/timescaledb:latest-pg16@sha256:ba149561ad4ddff5940d6eb0a0df60aefd1355cee1a450928f271267038fc888\n'
    printf '%s\n' "$db_networks"
    printf 'networks:\n  %s:\n    name: %s\n    external: true\n' "$app" "$app"
    [[ -z $extra_network ]] || printf '%s\n' "$extra_network"
  }
}

dokploy_api() {
  local method=${1-} path=${2-} body=${3-}
  printf '%s|%s\n' "$method" "$path" >> "$TEST_ROOT/calls.log"
  case "$method|$path" in
    'GET|settings.getDokployVersion')
      if [[ $FAKE_MODE == version_drift ]]; then
        printf '"v0.29.13"\n'
      else
        printf '"v0.29.12"\n'
      fi
      ;;
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
              {composeId:"c4",createdAt:"four",description:({repository:"BuildHound/BuildHound",pr:44,retired:true,sha:$sha}|tojson)},
              {composeId:"c3",createdAt:"three",description:({repository:"Another/Repo",pr:44,sha:$sha}|tojson)}
            ]}
          '
          ;;
        retired)
          jq -cn --arg sha "$SHA" '
            {compose:[{
              name:"mr42",composeId:"c1",serverId:null,createdAt:"one",
              description:({attemptId:"12344.1",repository:"BuildHound/BuildHound",pr:42,retired:true,sha:$sha}|tojson)
            }]}
          '
          ;;
        cleanup|route_mixed|scrub|scrub_failed|running|no_row)
          fake_owned_environment
          ;;
        legacy_isolation)
          jq -cn --arg sha "$SHA" '
            {compose:[{
              name:"mr42",composeId:"c1",serverId:null,createdAt:"one",
              description:({repository:"BuildHound/BuildHound",pr:42,sha:$sha}|tojson)
            }]}
          '
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
      if jq -e 'has("composeFile")' >/dev/null <<< "$body"; then
        printf '%s\n' "$body" > "$TEST_ROOT/update.json"
        printf '%s\n' "$body" > "$TEST_ROOT/compose-state.json"
      elif [[ -f $TEST_ROOT/compose-state.json ]]; then
        jq -c --argjson patch "$body" '. * $patch' "$TEST_ROOT/compose-state.json" \
          > "$TEST_ROOT/compose-state.next.json"
        mv -- "$TEST_ROOT/compose-state.next.json" "$TEST_ROOT/compose-state.json"
      else
        printf '%s\n' "$body" > "$TEST_ROOT/compose-state.json"
      fi
      printf '{}\n'
      ;;
    'GET|compose.one?composeId=c1')
      if [[ ! -f $TEST_ROOT/compose-state.json ]]; then
        if [[ $FAKE_MODE == legacy_isolation ]]; then
          printf '{"composeId":"c1","composeStatus":"idle","isolatedDeployment":false}\n'
        else
          fake_app_name
          jq -cn --arg appName "$FAKE_APP_NAME" \
            '{composeId:"c1",composeStatus:"idle",appName:$appName}'
        fi
      elif [[ $FAKE_MODE == hidden && ! -f $TEST_ROOT/cleanup_started ]]; then
        fake_persisted_compose | jq -c '.command="curl attacker.invalid"'
      elif [[ $FAKE_MODE == isolation_drift && ! -f $TEST_ROOT/cleanup_started ]]; then
        fake_persisted_compose | jq -c '.isolatedDeployment=false'
      else
        fake_persisted_compose
      fi
      ;;
    'POST|compose.deploy')
      : > "$TEST_ROOT/deploy_started"
      jq -er .title <<< "$body" > "$TEST_ROOT/deploy-title"
      if [[ -f $TEST_ROOT/compose-state.json ]] &&
         jq -e '.composeFile | contains("services:\n  anchor:")' "$TEST_ROOT/compose-state.json" >/dev/null; then
        : > "$TEST_ROOT/materialized-anchor"
      fi
      printf '{}\n'
      ;;
    'GET|deployment.allByCompose?composeId=c1')
      if [[ ! -f $TEST_ROOT/deploy_started ]]; then
        case "$FAKE_MODE" in
          cleanup|route_mixed|scrub|scrub_failed)
            printf '[{"deploymentId":"d1","title":"%s","status":"done"}]\n' "$TITLE"
            ;;
          running)
            printf '[{"deploymentId":"d1","title":"%s","status":"running"}]\n' "$TITLE"
            ;;
          no_row) printf '[]\n' ;;
          *) printf '[]\n' ;;
        esac
      else
        deployed_title=$(< "$TEST_ROOT/deploy-title")
        case "$FAKE_MODE" in
          scrub_failed)
            printf '[{"deploymentId":"d1","title":"%s","status":"done"},{"deploymentId":"d2","title":"%s","status":"failed"}]\n' "$TITLE" "$deployed_title"
            ;;
          scrub|no_row)
            printf '[{"deploymentId":"d1","title":"%s","status":"done"},{"deploymentId":"d2","title":"%s","status":"success"}]\n' "$TITLE" "$deployed_title"
            ;;
          legacy_isolation)
            printf '[{"deploymentId":"d2","title":"%s","status":"success"}]\n' "$deployed_title"
            ;;
          terminal|terminal_cleanup_fail) printf '[{"deploymentId":"d1","title":"%s","status":"failed"}]\n' "$TITLE" ;;
          terminal_invalid_id) printf '[{"title":"%s","status":"failed"}]\n' "$TITLE" ;;
          uncertain)
            printf '[{"deploymentId":"d1","title":"%s","status":"success"},{"deploymentId":"d2","title":"%s","status":"success"}]\n' "$TITLE" "$TITLE"
            ;;
          *) printf '[{"deploymentId":"d1","title":"%s","status":"success"}]\n' "$TITLE" ;;
        esac
      fi
      ;;
    'GET|compose.getConvertedCompose?composeId=c1')
      if [[ -f $TEST_ROOT/compose-state.json ]] &&
         ! jq -e '.composeFile | contains("services:\n  anchor:")' "$TEST_ROOT/compose-state.json" >/dev/null; then
        fake_converted_stack | jq -Rs .
      elif [[ ! -f $TEST_ROOT/materialized-anchor ]]; then
        printf '%s\n' 'version: "3.8"
services:
  anchor:
    image: timescale/timescaledb:latest-pg16@sha256:ba149561ad4ddff5940d6eb0a0df60aefd1355cee1a450928f271267038fc888
    environment:
      LEAKED: secret
    networks:
      - bh-2e4f87f1c5b0890580d952d3-mr42-Ab12Cd
    deploy:
      replicas: 0
      placement:
        constraints:
          - node.labels.role == review
networks:
  bh-2e4f87f1c5b0890580d952d3-mr42-Ab12Cd:
    name: bh-2e4f87f1c5b0890580d952d3-mr42-Ab12Cd
    external: true' | jq -Rs .
      else
        printf '%s\n' 'version: "3.8"
services:
  anchor:
    image: timescale/timescaledb:latest-pg16@sha256:ba149561ad4ddff5940d6eb0a0df60aefd1355cee1a450928f271267038fc888
    networks:
      - bh-2e4f87f1c5b0890580d952d3-mr42-Ab12Cd
    deploy:
      replicas: 0
      placement:
        constraints:
          - node.labels.role == review
networks:
  bh-2e4f87f1c5b0890580d952d3-mr42-Ab12Cd:
    name: bh-2e4f87f1c5b0890580d952d3-mr42-Ab12Cd
    external: true' | jq -Rs .
      fi
      ;;
    'POST|compose.stop')
      if [[ $FAKE_MODE == terminal_cleanup_fail ]]; then
        return 91
      fi
      : > "$TEST_ROOT/cleanup_started"
      : > "$TEST_ROOT/stopped"
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
  "ghcr.io/buildhound/server@sha256:$(printf '1%.0s' {1..64})"
  "ghcr.io/buildhound/site@sha256:$(printf '2%.0s' {1..64})"
  "$ATTEMPT"
)

reset_fake version_drift
if deploy_review "${deploy_args[@]}" >/dev/null 2> "$TEST_ROOT/error.log"; then
  fail 'unsupported Dokploy version was accepted'
fi
grep -F 'review lifecycle requires Dokploy v0.29.12' "$TEST_ROOT/error.log" >/dev/null
assert_log_lacks 'GET|environment.one?environmentId=env1'
assert_log_lacks 'POST|compose.create'

reset_fake success
result=$(deploy_review "${deploy_args[@]}")
jq -e --arg provider "$PROVIDER" '
  .name == "mr42" and .composeId == "c1" and .deploymentId == "d1"
' >/dev/null <<< "$result"
jq -e --arg password "$BUILDHOUND_REVIEW_DB_PASSWORD" --arg token "$BUILDHOUND_REVIEW_TOKEN" '
  .sourceType == "raw" and .composeType == "stack" and .command == "" and .env == "" and
  .autoDeploy == false and .randomize == false and .isolatedDeployment == true and
  .githubId == null and .gitlabId == null and .bitbucketId == null and .giteaId == null and
  (.composeFile | contains($password)) and (.composeFile | contains($token))
' "$TEST_ROOT/update.json" >/dev/null
jq -e --arg network "$PROVIDER-Ab12Cd" '
  (.composeFile | contains("traefik.swarm.network=" + $network)) and
  (.composeFile | contains("${BUILDHOUND_REVIEW_NETWORK}") | not) and
  (.composeFile | contains("traefik.docker.network") | not)
' "$TEST_ROOT/update.json" >/dev/null
jq -e '.description | fromjson | .attemptId == "12345.1" and .repository == "BuildHound/BuildHound" and .pr == 42 and .sha == "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"' \
  "$TEST_ROOT/update.json" >/dev/null
assert_log_has 'POST|compose.update'
assert_log_has 'POST|compose.deploy'
assert_log_lacks 'POST|compose.stop'
update_call=$(grep -nFx 'POST|compose.update' "$TEST_ROOT/calls.log" | cut -d: -f1)
name_readback_call=$(grep -nFx 'GET|compose.one?composeId=c1' "$TEST_ROOT/calls.log" | head -n1 | cut -d: -f1)
readback_call=$(grep -nFx 'GET|compose.one?composeId=c1' "$TEST_ROOT/calls.log" | tail -n1 | cut -d: -f1)
converted_call=$(grep -nFx 'GET|compose.getConvertedCompose?composeId=c1' "$TEST_ROOT/calls.log" | cut -d: -f1)
deploy_call=$(grep -nFx 'POST|compose.deploy' "$TEST_ROOT/calls.log" | cut -d: -f1)
create_call=$(grep -nFx 'POST|compose.create' "$TEST_ROOT/calls.log" | cut -d: -f1)
# Isolation is persisted and verified before deploy; the materialized stack
# can only be verified after deploy (Dokploy materializes on deploy).
(( create_call < name_readback_call && name_readback_call < update_call &&
   update_call < readback_call && readback_call < deploy_call &&
   deploy_call < converted_call )) || \
  fail 'review deploy order was not persist, verify, deploy, verify-materialized'

# Materialized-stack routing defects (plan 088): verified after deploy (the
# converted file only exists then); each must fail closed and retire the
# attempt through exact-owned cleanup.
for stack_defect in stack_missing_swarm_label stack_dual_network stack_short_tmpfs stack_extra_network; do
  reset_fake "$stack_defect"
  if deploy_review "${deploy_args[@]}" >/dev/null 2> "$TEST_ROOT/error.log"; then
    fail "materialized stack defect $stack_defect was accepted"
  fi
  grep -F 'did not materialize a routable isolated review stack' "$TEST_ROOT/error.log" >/dev/null || \
    fail "materialized stack defect $stack_defect was not rejected explicitly"
  assert_log_has 'GET|compose.getConvertedCompose?composeId=c1'
  assert_log_has 'POST|compose.deploy'
  assert_log_has 'POST|compose.stop'
done

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

reset_fake retired
result=$(deploy_review "${deploy_args[@]}")
jq -e '.name == "mr42" and .composeId == "c1" and .deploymentId == "d1"' \
  >/dev/null <<< "$result"
assert_log_lacks 'POST|compose.create'
assert_log_has 'POST|compose.update'
assert_log_has 'POST|compose.deploy'
jq -e '
  .description | fromjson |
  .attemptId == "12345.1" and .retired == false and
  (.activatedAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"))
' "$TEST_ROOT/update.json" >/dev/null

reset_fake hidden
if deploy_review "${deploy_args[@]}" >/dev/null 2>&1; then fail 'hidden persisted state accepted'; fi
assert_log_lacks 'POST|compose.deploy'
assert_log_lacks 'POST|compose.cleanQueues'
assert_log_has 'POST|compose.stop'
[[ ! -f $TEST_ROOT/deleted ]] || fail 'failed deployment cleanup deleted its ownership record'

reset_fake isolation_drift
if deploy_review "${deploy_args[@]}" >/dev/null 2>&1; then
  fail 'persisted isolated-deployment drift was accepted'
fi
assert_log_lacks 'POST|compose.deploy'
assert_log_has 'POST|compose.stop'

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
assert_log_has 'POST|compose.stop'
[[ ! -f $TEST_ROOT/stopped ]] || fail 'failed stop still marked the compose stopped'

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
jq -e 'length == 2 and .[0].pr == 43 and .[0].composeId == "c2" and
       .[0].attemptId == null and .[0].retired == false and
       .[1].pr == 44 and .[1].composeId == "c4" and .[1].retired == true' \
  >/dev/null <<< "$reviews"
assert_eq "$(count_reviews "$REPO" env1 42)" 1

reset_fake invalid_metadata
if list_reviews "$REPO" env1 >/dev/null 2>&1; then fail 'invalid ownership metadata was listed'; fi
if count_reviews "$REPO" env1 >/dev/null 2>&1; then fail 'invalid ownership metadata was counted'; fi

# The exact-record and revoke-compose guards that revoke_review exercised are
# live in scrub_review's cleanup preamble; port each guard scenario here so the
# cleanup path stays covered after revoke_review was deleted (plan 091).
reset_fake cleanup
if scrub_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" 12345.2 >/dev/null 2>&1; then
  fail 'scrub accepted a different review attempt'
fi
assert_log_lacks 'POST|compose.stop'
assert_log_lacks 'POST|compose.update'

reset_fake route_mixed
if scrub_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" "$ATTEMPT" >/dev/null 2>&1; then
  fail 'scrub accepted a still-reachable dashboard route'
fi
[[ ! -f $TEST_ROOT/deleted ]] || fail 'route convergence failure deleted ownership record'

reset_fake running
if scrub_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" "$ATTEMPT" >/dev/null 2>&1; then
  fail 'scrub raced a running deployment'
fi
assert_log_lacks 'POST|compose.cleanQueues'
assert_log_lacks 'POST|compose.stop'

# no_row: an empty active-deployment set is idempotent-proceed, not a rejection.
# scrub's first _review_revoke_compose stops and then the full inert scrub runs.
reset_fake no_row
result=$(scrub_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" "$ATTEMPT")
jq -e '.scrubbed == "mr42" and .deploymentId == "d2"' >/dev/null <<< "$result"
assert_log_lacks 'POST|compose.cleanQueues'
assert_log_has 'POST|compose.stop'

reset_fake scrub
result=$(scrub_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" "$ATTEMPT")
jq -e '.scrubbed == "mr42" and .deploymentId == "d2"' >/dev/null <<< "$result"
assert_log_lacks 'POST|compose.delete'
assert_log_lacks 'GET|compose.getConvertedCompose?composeId=c1'
jq -e --arg password "$BUILDHOUND_REVIEW_DB_PASSWORD" --arg token "$BUILDHOUND_REVIEW_TOKEN" '
  .isolatedDeployment == true and
  (.composeFile | contains("services:\n  anchor:")) and
  (.composeFile | contains($password) | not) and (.composeFile | contains($token) | not) and
  (.description | fromjson |
    .retired == false and .activatedAt == "2026-07-13T12:00:00Z")
' "$TEST_ROOT/compose-state.json" >/dev/null
test "$(grep -c '^POST|compose.deploy$' "$TEST_ROOT/calls.log")" -eq 1 || \
  fail 'scrub did not materialize the inert manager-side file'
test "$(grep -c '^POST|compose.stop$' "$TEST_ROOT/calls.log")" -eq 2 || \
  fail 'scrub did not stop before and after inert materialization'

result=$(retire_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" "$ATTEMPT")
assert_eq "$result" '{"retired": "mr42"}'
jq -e '
  .isolatedDeployment == true and
  (.description | fromjson |
    .retired == true and .activatedAt == "2026-07-13T12:00:00Z" and
    (.retiredAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")))
' "$TEST_ROOT/compose-state.json" >/dev/null
updates=$(grep -c '^POST|compose.update$' "$TEST_ROOT/calls.log")
# Re-retirement must preserve the original retiredAt stamp, even when the
# clock has moved on — a regenerated stamp fails the exact-compose check
# whenever two retires straddle a second boundary (observed as a CI flake).
jq -c '.description = (.description | fromjson | .retiredAt = "2020-01-01T00:00:00Z" | tojson)' \
  "$TEST_ROOT/compose-state.json" > "$TEST_ROOT/compose-state.tmp"
mv -- "$TEST_ROOT/compose-state.tmp" "$TEST_ROOT/compose-state.json"
result=$(retire_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" "$ATTEMPT")
assert_eq "$result" '{"retired": "mr42"}'
test "$(grep -c '^POST|compose.update$' "$TEST_ROOT/calls.log")" -eq "$updates" || \
  fail 'idempotent retirement rewrote an already-retired anchor'
jq -e '(.description | fromjson | .retiredAt) == "2020-01-01T00:00:00Z"' \
  "$TEST_ROOT/compose-state.json" >/dev/null || \
  fail 'idempotent retirement regenerated the preserved retiredAt stamp'

reset_fake scrub_failed
if scrub_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" "$ATTEMPT" >/dev/null 2>&1; then
  fail 'scrub accepted a failed inert deployment'
fi

reset_fake legacy_isolation
result=$(scrub_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" '')
jq -e '.scrubbed == "mr42"' >/dev/null <<< "$result"
result=$(retire_review "$REPO" 42 env1 reviews.example.test c1 "$SHA" '')
assert_eq "$result" '{"retired": "mr42"}'
jq -e '
  .isolatedDeployment == true and
  (.description | fromjson |
    .retired == true and (has("attemptId") | not) and (has("activatedAt") | not))
' "$TEST_ROOT/compose-state.json" >/dev/null
legacy_description=$(jq -er .description "$TEST_ROOT/compose-state.json")
legacy_environment=$(jq -cn --arg description "$legacy_description" '
  {compose:[{name:"mr42",composeId:"c1",serverId:null,description:$description}]}
')
_review_owned_items "$legacy_environment" mr42 "$REPO" 42 "$SHA" '' >/dev/null || \
  fail 'legacy retirement poisoned ownership metadata'

if find "$TEST_ROOT" -maxdepth 1 -type d -name 'buildhound-review.*' | grep -q .; then
  fail 'review client left a private workspace behind'
fi
if find "$TEST_ROOT" -maxdepth 1 -type d -name 'buildhound-review-revoke.*' | grep -q .; then
  fail 'review revocation left a private workspace behind'
fi
if find "$TEST_ROOT" -maxdepth 1 -type d -name 'buildhound-review-retire.*' | grep -q .; then
  fail 'review retirement left a private workspace behind'
fi
if find "$TEST_ROOT" -maxdepth 1 -type d -name 'buildhound-review-scrub.*' | grep -q .; then
  fail 'review scrub left a private workspace behind'
fi

printf 'review shell client validated\n'
