#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
# shellcheck disable=SC1091
source "$root/deploy/dokploy/dokploy.sh"

test_root=$(mktemp -d "${TMPDIR:-/tmp}/buildhound-delivery-cli.XXXXXX")
export RUNNER_TEMP=$test_root
trap 'rm -rf -- "$test_root"' EXIT
trap 'rm -rf -- "$test_root"; exit 129' HUP
trap 'rm -rf -- "$test_root"; exit 130' INT
trap 'rm -rf -- "$test_root"; exit 143' TERM

fail_test() {
  printf 'delivery-cli-test: %s\n' "$1" >&2
  exit 1
}

assert_eq() {
  [[ $1 == "$2" ]] || fail_test "expected [$2], got [$1]"
}

assert_status() {
  local expected=$1 actual
  shift
  set +e
  "$@" > "$test_root/status-stdout" 2> "$test_root/status-stderr"
  actual=$?
  set -e
  [[ $actual -eq $expected ]] || fail_test "expected status $expected, got $actual"
}

manifest=$test_root/stack.yaml
production_manifest=$test_root/production-stack.yaml
guard=$test_root/volume-guard.sh
release=$test_root/release.json
# Intentional literal release-template placeholders.
# shellcheck disable=SC2016
printf 'services:\n  server:\n    image: ${BUILDHOUND_SERVER_IMAGE}\n    deploy:\n      placement:\n        constraints:\n          - node.labels.role == ${BUILDHOUND_APP_ROLE}\n  backup:\n    image: ${BUILDHOUND_BACKUP_IMAGE}\n  db:\n    image: ${BUILDHOUND_POSTGRES_IMAGE}\n    labels:\n      release: ${BUILDHOUND_RELEASE_ID:-manual}\n\n' > "$manifest"
printf 'services:\n  production: {}\n' > "$production_manifest"
printf '#!/bin/sh\nexit 0\n' > "$guard"

SERVER_IMAGE="ghcr.io/buildhound/server@sha256:$(printf '1%.0s' {1..64})"
SITE_IMAGE="ghcr.io/buildhound/site@sha256:$(printf '2%.0s' {1..64})"
BACKUP_IMAGE="ghcr.io/buildhound/backup@sha256:$(printf '3%.0s' {1..64})"
POSTGRES_IMAGE="ghcr.io/buildhound/db@sha256:$(printf '4%.0s' {1..64})"
SOURCE_SHA=$(printf 'a%.0s' {1..40})
MIGRATION_SHA=$(printf '5%.0s' {1..64})
HISTORY=$(jq -cn --arg sha "$MIGRATION_SHA" '[{id:"V1__initial",sha256:$sha}]')
HISTORY_HASH=$(printf '%s\n' "$HISTORY" | sha256_stdin)
MANIFEST_HASH=$(sha256_file "$manifest")
PRODUCTION_MANIFEST_HASH=$(sha256_file "$production_manifest")
GUARD_HASH=$(sha256_file "$guard")
export SERVER_IMAGE SITE_IMAGE HISTORY_HASH

jq -cn \
  --arg source "$SOURCE_SHA" \
  --arg server "$SERVER_IMAGE" \
  --arg site "$SITE_IMAGE" \
  --arg backup "$BACKUP_IMAGE" \
  --arg postgres "$POSTGRES_IMAGE" \
  --arg productionManifest "$PRODUCTION_MANIFEST_HASH" \
  --arg stagingManifest "$MANIFEST_HASH" \
  --arg guard "$GUARD_HASH" \
  --argjson history "$HISTORY" \
  --arg historyHash "$HISTORY_HASH" '
  {
    schema:3,
    sourceCommit:$source,
    serverImage:$server,
    siteImage:$site,
    backupImage:$backup,
    postgresImage:$postgres,
    productionManifestSha256:$productionManifest,
    stagingManifestSha256:$stagingManifest,
    volumeGuardSha256:$guard,
    migrationId:"V1__initial",
    migrationHistory:$history,
    migrationHistorySha256:$historyHash
  }
' > "$release"
cp -- "$release" "$test_root/original-release.json"
cp -- "$manifest" "$test_root/original-stack.yaml"
export TEST_RELEASE_SOURCE=$release
export TEST_MANIFEST_SOURCE=$manifest

RID=$(main release-id "$release")
[[ $RID =~ ^sha256:[0-9a-f]{64}$ ]] || fail_test 'release-id returned an invalid digest'
TITLE=$(release_title "$release")
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role prod
grep -F 'release manifest checksum differs from trusted stack' "$test_root/status-stderr" >/dev/null || \
  fail_test 'production accepted the staging manifest'
DRIFT_RID="sha256:$(printf '6%.0s' {1..64})"
DRIFT_TITLE="$DRIFT_RID|V1__initial|$HISTORY_HASH|$SOURCE_SHA"
export RID TITLE DRIFT_RID DRIFT_TITLE SOURCE_SHA

nul_manifest=$test_root/nul-stack.yaml
nul_rendered=$test_root/nul-rendered.yaml
nul_expected=$test_root/nul-expected.yaml
# NUL is invalid YAML but exercises byte preservation before later YAML validation in Dokploy.
# shellcheck disable=SC2016
printf 'left\0${BUILDHOUND_SERVER_IMAGE}\n' > "$nul_manifest"
render_release_stack "$nul_manifest" "$release" "$RID" "$nul_rendered" staging
printf 'left\0%s\n' "$SERVER_IMAGE" > "$nul_expected"
cmp -s "$nul_expected" "$nul_rendered" || fail_test 'release renderer dropped a NUL byte'

invalid_utf8_manifest=$test_root/invalid-utf8-stack.yaml
# shellcheck disable=SC2016
printf 'left\377${BUILDHOUND_SERVER_IMAGE}\n' > "$invalid_utf8_manifest"
if render_release_stack "$invalid_utf8_manifest" "$release" "$RID" "$test_root/invalid-rendered.yaml" staging \
    >/dev/null 2>&1; then
  fail_test 'release renderer normalized invalid UTF-8'
fi
prod_rendered=$test_root/prod-rendered.yaml
render_release_stack "$manifest" "$release" "$RID" "$prod_rendered" prod
grep -F 'node.labels.role == prod' "$prod_rendered" >/dev/null || \
  fail_test 'release renderer did not bind the production app role'

export DOKPLOY_URL=https://dokploy.example.test
export DOKPLOY_TOKEN=test-token
export DOKPLOY_REGISTRY_ID=registry1
export DOKPLOY_GIT_PROVIDER_ID=provider1
BUILDHOUND_REVIEW_DB_PASSWORD=$(printf 'b%.0s' {1..64})
BUILDHOUND_REVIEW_TOKEN=$(printf 'c%.0s' {1..64})
export BUILDHOUND_REVIEW_DB_PASSWORD BUILDHOUND_REVIEW_TOKEN
export FAKE_MODE=current
INTEGRATION_CALLS=0

dokploy_require_integrations() {
  INTEGRATION_CALLS=$((INTEGRATION_CALLS + 1))
  printf 'integration\n' >> "$test_root/review-call-order"
  printf '%s\n' "${3-}" >> "$test_root/integration-targets"
  [[ $# -eq 3 && $1 == BuildHound/BuildHound && $2 == ghcr.io &&
     $DOKPLOY_REGISTRY_ID == registry1 && $DOKPLOY_GIT_PROVIDER_ID == provider1 ]] || return 96
}

reset_api() {
  printf '0\n' > "$test_root/counter"
  : > "$test_root/calls"
  : > "$test_root/integration-targets"
  rm -f -- "$test_root"/body-*.json "$test_root"/compose-deployed "$test_root"/site-deployed \
    "$test_root"/missing-app-creds "$test_root"/compose-update-body.json \
    "$test_root"/application-update-body.json "$test_root"/predecessor-read
}

next_call() {
  local number
  number=$(< "$test_root/counter")
  number=$((number + 1))
  printf '%s\n' "$number" > "$test_root/counter"
  printf '%s\n' "$number"
}

fake_release_compose() {
  jq -cn --arg serverId "${COMPOSE_SERVER_ID-}" \
    --argjson domains "${COMPOSE_DOMAINS-[]}" --argjson mounts "${COMPOSE_MOUNTS-[]}" '
    {
      composeId:"c1",name:"BuildHound",appName:"buildhound",environmentId:"env1",
      serverId:(if $serverId == "" then null else $serverId end),
      env:"BUILDHOUND_DB_PASSWORD=compose-secret",domains:$domains,mounts:$mounts,backups:[],
      environment:{env:"BUILDHOUND_HOST_RPM=600",project:{env:"BUILDHOUND_QUERY_RPM=120\nBUILDHOUND_DASHBOARD_HOST=dashboard.example.test"}},
      github:null,gitlab:null,bitbucket:null,gitea:null
    }
  '
}

dokploy_api() {
  local method=${1-} path=${2-} body=${3-} number auto_deploy placement mounts ports endpoint_spec compose_drift
  number=$(next_call)
  printf '%s|%s\n' "$method" "$path" >> "$test_root/calls"
  if [[ -n $body ]]; then printf '%s\n' "$body" > "$test_root/body-$number.json"; fi
  case "$FAKE_MODE|$method|$path" in
    'current|GET|deployment.allByCompose?composeId=c1')
      jq -cn --arg title "$TITLE" \
        '[{deploymentId:"current",status:"done",title:$title,createdAt:"2026-07-13T12:00:00Z"}]'
      ;;
    'manual|GET|deployment.allByCompose?composeId=c1')
      printf '[{"deploymentId":"manual","status":"done","title":"Manual deployment","createdAt":"2026-07-13T12:00:00Z"}]\n'
      ;;
    'empty|GET|deployment.allByCompose?composeId=c1')
      printf '[]\n'
      ;;
    'manual_over_release|GET|deployment.allByCompose?composeId=c1')
      jq -cn --arg title "$TITLE" '[
        {deploymentId:"manual",status:"done",title:"Manual deployment",createdAt:"2026-07-14T12:00:00Z"},
        {deploymentId:"older-release",status:"done",title:$title,createdAt:"2026-07-13T12:00:00Z"}
      ]'
      ;;
    'terminal_invalid_id|GET|deployment.allByCompose?composeId=c1')
      printf '[{"status":"failed","title":"invalid-id-title"}]\n'
      ;;
    'deploy_predecessor_drift|GET|deployment.allByCompose?composeId=c1')
      if [[ -f $test_root/predecessor-read ]]; then
        jq -cn --arg title "$DRIFT_TITLE" \
          '[{deploymentId:"drifted",status:"done",title:$title,createdAt:"2026-07-13T12:00:01Z"}]'
      else
        : > "$test_root/predecessor-read"
        jq -cn --arg title "$TITLE" \
          '[{deploymentId:"current",status:"done",title:$title,createdAt:"2026-07-13T12:00:00Z"}]'
      fi
      ;;
    'deploy|GET|deployment.allByCompose?composeId=c1'|'deploy_terminal|GET|deployment.allByCompose?composeId=c1'|'deploy_site_terminal|GET|deployment.allByCompose?composeId=c1'|'deploy_registry_drift|GET|deployment.allByCompose?composeId=c1')
      if [[ -f $test_root/compose-deployed ]]; then
        if [[ $FAKE_MODE == deploy_terminal ]]; then
          jq -cn --arg title "$TITLE" \
            '[{deploymentId:"compose-new",status:"failed",title:$title}]'
        elif [[ ${STALE_RELEASE_BASELINES-false} == true ]]; then
          jq -cn --arg title "$TITLE" '[
            {deploymentId:"compose-intervening",status:"success",title:$title},
            {deploymentId:"compose-new",status:"success",title:$title}
          ]'
        else
          jq -cn --arg title "$TITLE" \
            '[{deploymentId:"compose-new",status:"success",title:$title}]'
        fi
      else
        if [[ $FAKE_MODE == deploy && ! -f $test_root/sources-mutated ]]; then
          printf '{}\n' > "$TEST_RELEASE_SOURCE"
          printf 'attacker-controlled-stack\n' > "$TEST_MANIFEST_SOURCE"
          : > "$test_root/sources-mutated"
        fi
        if [[ ${STALE_RELEASE_BASELINES-false} == true && -f $test_root/compose-update-body.json ]]; then
          jq -cn --arg title "$TITLE" \
            '[{deploymentId:"compose-intervening",status:"success",title:$title}]'
        else
          printf '[]\n'
        fi
      fi
      ;;
    'deploy|GET|deployment.all?applicationId=a1'|'deploy_terminal|GET|deployment.all?applicationId=a1'|'deploy_site_terminal|GET|deployment.all?applicationId=a1'|'deploy_registry_drift|GET|deployment.all?applicationId=a1'|'deploy_predecessor_drift|GET|deployment.all?applicationId=a1')
      if [[ -f $test_root/site-deployed ]]; then
        if [[ ${STALE_RELEASE_BASELINES-false} == true ]]; then
          jq -cn --arg title "$TITLE" '[
            {deploymentId:"site-intervening",status:"done",title:$title},
            {deploymentId:"site-new",status:"done",title:$title}
          ]'
        else
          jq -cn --arg title "$TITLE" --arg status "$(if [[ $FAKE_MODE == deploy_site_terminal ]]; then printf '%s' failed; else printf '%s' 'done'; fi)" \
            '[{deploymentId:"site-new",status:$status,title:$title}]'
        fi
      elif [[ ${STALE_RELEASE_BASELINES-false} == true && -f $test_root/compose-deployed ]]; then
        jq -cn --arg title "$TITLE" \
          '[{deploymentId:"site-intervening",status:"done",title:$title}]'
      else
        printf '[]\n'
      fi
      ;;
    'deploy|POST|compose.update'|'deploy_terminal|POST|compose.update'|'deploy_site_terminal|POST|compose.update'|'deploy_registry_drift|POST|compose.update')
      printf '%s\n' "$body" > "$test_root/compose-update-body.json"
      printf '{}\n'
      ;;
    'deploy|POST|application.update'|'deploy_terminal|POST|application.update'|'deploy_site_terminal|POST|application.update'|'deploy_registry_drift|POST|application.update')
      printf '%s\n' "$body" > "$test_root/application-update-body.json"
      printf '{}\n'
      ;;
    'deploy|GET|compose.one?composeId=c1'|'deploy_terminal|GET|compose.one?composeId=c1'|'deploy_site_terminal|GET|compose.one?composeId=c1'|'deploy_registry_drift|GET|compose.one?composeId=c1'|'deploy_predecessor_drift|GET|compose.one?composeId=c1')
      if [[ -f $test_root/compose-update-body.json ]]; then
        compose_drift=false
        if [[ ${COMPOSE_DRIFT_AFTER_DEPLOY-false} == true && -f $test_root/compose-deployed ]]; then
          compose_drift=true
        fi
        fake_release_compose | jq -c \
          --slurpfile update "$test_root/compose-update-body.json" \
          --arg serverId "${COMPOSE_PERSISTED_SERVER_ID-${COMPOSE_SERVER_ID-}}" \
          --arg command "${COMPOSE_PERSISTED_COMMAND-}" \
          --argjson composeDrift "$compose_drift" \
          --argjson domains "${COMPOSE_PERSISTED_DOMAINS-${COMPOSE_DOMAINS-[]}}" '
          . + $update[0] |
          .serverId = (if $serverId == "" then null else $serverId end) |
          .domains = $domains |
          (if $command == "" then . else .command = $command end) |
          if $composeDrift then
            .environment.project.env = "BUILDHOUND_DASHBOARD_HOST=attacker.example.test"
          else . end
        '
      else
        fake_release_compose
      fi
      ;;
    'deploy|POST|compose.deploy'|'deploy_terminal|POST|compose.deploy'|'deploy_site_terminal|POST|compose.deploy'|'deploy_registry_drift|POST|compose.deploy')
      : > "$test_root/compose-deployed"; printf '{}\n'
      ;;
    'deploy|POST|application.deploy'|'deploy_terminal|POST|application.deploy'|'deploy_site_terminal|POST|application.deploy'|'deploy_registry_drift|POST|application.deploy')
      : > "$test_root/site-deployed"; printf '{}\n'
      ;;
    'deploy|GET|application.one?applicationId=a1'|'deploy_terminal|GET|application.one?applicationId=a1'|'deploy_site_terminal|GET|application.one?applicationId=a1'|'deploy_registry_drift|GET|application.one?applicationId=a1'|'deploy_predecessor_drift|GET|application.one?applicationId=a1'|'application_binding_drift|GET|application.one?applicationId=a1')
      if [[ -f $test_root/application-update-body.json ]]; then
        auto_deploy=$(jq -cr '.autoDeploy' "$test_root/application-update-body.json")
        placement=$(jq -cr '.placementSwarm' "$test_root/application-update-body.json")
      elif [[ $FAKE_MODE == application_binding_drift ]]; then
        jq -cn --arg image "$SITE_IMAGE" --arg serverId "${COMPOSE_SERVER_ID-}" \
          '{applicationId:"a1",dockerImage:$image,sourceType:"docker",registryId:null,
            buildRegistryId:null,rollbackRegistryId:null,registry:null,buildRegistry:null,
            rollbackRegistry:null,autoDeploy:false,placementSwarm:{Constraints:["node.labels.role==staging"]},
            registryUrl:"ghcr.io",username:"robot",password:"pull-token",environmentId:"wrong-environment",serverId:(if $serverId == "" then null else $serverId end),
            env:"BUILDHOUND_SITE_HOST=site.example.test\nBUILDHOUND_SITE_DASHBOARD_URL=https://wrong-dashboard.example.test\nBUILDHOUND_SITE_NOINDEX=false",
            redirects:[],security:[],domains:[{domainId:"domain1",host:"wrong-site.example.test",https:true,port:8080,path:"/",domainType:"application",applicationId:"a1",composeId:null,previewDeploymentId:null,certificateType:"none",customCertResolver:null,customEntrypoint:null,internalPath:"/",stripPath:false,middlewares:[],forwardAuthEnabled:false,serviceName:null}]}'
        return 0
      else
        auto_deploy=${APPLICATION_AUTO_DEPLOY_BEFORE-true}
        placement=${APPLICATION_PLACEMENT_BEFORE-null}
      fi
      auto_deploy=${APPLICATION_PERSISTED_AUTO_DEPLOY-$auto_deploy}
      placement=${APPLICATION_PERSISTED_PLACEMENT-$placement}
      mounts=${APPLICATION_MOUNTS_BEFORE-[]}
      ports=${APPLICATION_PORTS_BEFORE-[]}
      endpoint_spec=${APPLICATION_ENDPOINT_SPEC_BEFORE-null}
      if [[ -f $test_root/application-update-body.json ]]; then
        mounts=${APPLICATION_PERSISTED_MOUNTS-$mounts}
        ports=${APPLICATION_PERSISTED_PORTS-$ports}
        endpoint_spec=${APPLICATION_PERSISTED_ENDPOINT_SPEC-$endpoint_spec}
      fi
      if [[ -f $test_root/site-deployed ]]; then
        mounts=${APPLICATION_DEPLOYED_MOUNTS-$mounts}
        ports=${APPLICATION_DEPLOYED_PORTS-$ports}
        endpoint_spec=${APPLICATION_DEPLOYED_ENDPOINT_SPEC-$endpoint_spec}
      fi
      if [[ ${APPLICATION_DRIFT_AFTER_COMPOSE-false} == true &&
            -f $test_root/compose-deployed && ! -f $test_root/site-deployed ]]; then
        mounts='[{"mountId":"drift","hostPath":"/attacker","containerPath":"/data"}]'
      fi
      if [[ -f $test_root/missing-app-creds ]]; then
        jq -cn --arg image "$SITE_IMAGE" --arg serverId "${COMPOSE_SERVER_ID-}" --argjson autoDeploy "$auto_deploy" --argjson placement "$placement" --argjson mounts "$mounts" --argjson ports "$ports" --argjson endpointSpec "$endpoint_spec" \
          '{applicationId:"a1",dockerImage:$image,sourceType:"docker",registryId:null,
            buildRegistryId:null,rollbackRegistryId:null,registry:null,buildRegistry:null,
            rollbackRegistry:null,autoDeploy:$autoDeploy,placementSwarm:$placement,
            registryUrl:"ghcr.io",username:"",password:"",environmentId:"env1",serverId:(if $serverId == "" then null else $serverId end),
            env:"BUILDHOUND_SITE_HOST=site.example.test\nBUILDHOUND_SITE_DASHBOARD_URL=https://dashboard.example.test\nBUILDHOUND_SITE_NOINDEX=true",
            redirects:[],security:[],mounts:$mounts,ports:$ports,endpointSpecSwarm:$endpointSpec,domains:[{domainId:"domain1",host:"site.example.test",https:true,port:8080,path:"/",domainType:"application",applicationId:"a1",composeId:null,previewDeploymentId:null,certificateType:"none",customCertResolver:null,customEntrypoint:null,internalPath:"/",stripPath:false,middlewares:[],forwardAuthEnabled:false,serviceName:null}]}'
      elif [[ $FAKE_MODE == deploy_registry_drift && -f $test_root/site-deployed ]]; then
        jq -cn --arg image "$SITE_IMAGE" --arg serverId "${COMPOSE_SERVER_ID-}" --argjson autoDeploy "$auto_deploy" --argjson placement "$placement" --argjson mounts "$mounts" --argjson ports "$ports" --argjson endpointSpec "$endpoint_spec" \
          '{applicationId:"a1",dockerImage:$image,sourceType:"docker",registryId:"registry2",
            buildRegistryId:null,rollbackRegistryId:null,registry:{registryId:"registry2"},
            buildRegistry:null,rollbackRegistry:null,autoDeploy:$autoDeploy,placementSwarm:$placement,
            registryUrl:"ghcr.io",username:"robot",password:"pull-token",environmentId:"env1",serverId:(if $serverId == "" then null else $serverId end),
            env:"BUILDHOUND_SITE_HOST=site.example.test\nBUILDHOUND_SITE_DASHBOARD_URL=https://dashboard.example.test\nBUILDHOUND_SITE_NOINDEX=true",
            redirects:[],security:[],mounts:$mounts,ports:$ports,endpointSpecSwarm:$endpointSpec,domains:[{domainId:"domain1",host:"site.example.test",https:true,port:8080,path:"/",domainType:"application",applicationId:"a1",composeId:null,previewDeploymentId:null,certificateType:"none",customCertResolver:null,customEntrypoint:null,internalPath:"/",stripPath:false,middlewares:[],forwardAuthEnabled:false,serviceName:null}]}'
      else
        jq -cn --arg image "$SITE_IMAGE" --arg serverId "${COMPOSE_SERVER_ID-}" --argjson autoDeploy "$auto_deploy" --argjson placement "$placement" --argjson mounts "$mounts" --argjson ports "$ports" --argjson endpointSpec "$endpoint_spec" \
          '{applicationId:"a1",dockerImage:$image,sourceType:"docker",registryId:null,
            buildRegistryId:null,rollbackRegistryId:null,registry:null,buildRegistry:null,
            rollbackRegistry:null,autoDeploy:$autoDeploy,placementSwarm:$placement,
            registryUrl:"ghcr.io",username:"robot",password:"pull-token",environmentId:"env1",serverId:(if $serverId == "" then null else $serverId end),
            env:"BUILDHOUND_SITE_HOST=site.example.test\nBUILDHOUND_SITE_DASHBOARD_URL=https://dashboard.example.test\nBUILDHOUND_SITE_NOINDEX=true",
            redirects:[],security:[],mounts:$mounts,ports:$ports,endpointSpecSwarm:$endpointSpec,domains:[{domainId:"domain1",host:"site.example.test",https:true,port:8080,path:"/",domainType:"application",applicationId:"a1",composeId:null,previewDeploymentId:null,certificateType:"none",customCertResolver:null,customEntrypoint:null,internalPath:"/",stripPath:false,middlewares:[],forwardAuthEnabled:false,serviceName:null}]}'
      fi
      ;;
    *)
      printf 'unexpected API call: %s %s in %s\n' "$method" "$path" "$FAKE_MODE" >&2
      return 90
      ;;
  esac
}

reset_api
assert_eq "$(main current-release-id --compose-id c1)" "$RID"

# Production has no site configuration arguments: preserve its historic
# Application response contract rather than requiring staging-only binding fields.
jq -cn --arg image "$SITE_IMAGE" '
  {applicationId:"a1",dockerImage:$image,sourceType:"docker",autoDeploy:false,
   registryId:null,buildRegistryId:null,rollbackRegistryId:null,registry:null,
   buildRegistry:null,rollbackRegistry:null,registryUrl:"ghcr.io",username:"robot",
   password:"pull-token",placementSwarm:{Constraints:["node.labels.role==prod"]}}
' > "$test_root/legacy-production-app.json"
cp -- "$test_root/legacy-production-app.json" "$test_root/legacy-production-app-before.json"
require_exact_release_application_state "$test_root/legacy-production-app.json" \
  "$test_root/legacy-production-app-before.json" a1 "$SITE_IMAGE" ghcr.io prod '' '' '' '' '' || \
  fail_test 'production compatibility unexpectedly requires staging Application binding fields'

jq -cn --arg image "$SITE_IMAGE" '
  {applicationId:"a1",dockerImage:$image,sourceType:"docker",autoDeploy:false,
   registryId:null,buildRegistryId:null,rollbackRegistryId:null,registry:null,
   buildRegistry:null,rollbackRegistry:null,registryUrl:"ghcr.io",username:"robot",
   password:"pull-token",environmentId:"env1",serverId:null,
   env:"BUILDHOUND_SITE_HOST=site.example.test\nBUILDHOUND_SITE_DASHBOARD_URL=https://dashboard.example.test\nBUILDHOUND_SITE_NOINDEX=true",
   redirects:[],security:[],mounts:[],ports:[],endpointSpecSwarm:null,
   domains:[{domainId:"domain1",host:"site.example.test",https:true,port:8080,path:"/",domainType:"application",applicationId:"a1",composeId:null,previewDeploymentId:null,certificateType:"none",customCertResolver:null,customEntrypoint:null,internalPath:"/",stripPath:false,middlewares:[],forwardAuthEnabled:false,serviceName:null}],placementSwarm:{Constraints:["node.labels.role==staging"]}}
' > "$test_root/bound-site-app.json"
# Dokploy v0.29.12 has three equivalent inactive endpoint representations.
# Bind each exact object form directly, while the main fixture exercises null.
jq '.endpointSpecSwarm = {Mode:"vip"}' "$test_root/bound-site-app.json" > "$test_root/vip-endpoint-app.json"
require_site_application_binding "$test_root/vip-endpoint-app.json" a1 env1 '' \
  https://site.example.test https://dashboard.example.test true || \
  fail_test 'inactive vip endpoint object failed the binding check'
require_exact_release_application_state "$test_root/vip-endpoint-app.json" \
  "$test_root/vip-endpoint-app.json" a1 "$SITE_IMAGE" ghcr.io staging env1 '' \
  https://site.example.test https://dashboard.example.test true || \
  fail_test 'inactive vip endpoint object failed the exact-state check'
jq '.endpointSpecSwarm = {Mode:"vip",Ports:[]}' "$test_root/bound-site-app.json" > "$test_root/vip-empty-ports-endpoint-app.json"
require_site_application_binding "$test_root/vip-empty-ports-endpoint-app.json" a1 env1 '' \
  https://site.example.test https://dashboard.example.test true || \
  fail_test 'inactive vip empty-ports endpoint object failed the binding check'
require_exact_release_application_state "$test_root/vip-empty-ports-endpoint-app.json" \
  "$test_root/vip-empty-ports-endpoint-app.json" a1 "$SITE_IMAGE" ghcr.io staging env1 '' \
  https://site.example.test https://dashboard.example.test true || \
  fail_test 'inactive vip empty-ports endpoint object failed the exact-state check'
jq '.domains += [{host:"attacker.example.test"}]' "$test_root/bound-site-app.json" > "$test_root/extra-domain-app.json"
if require_site_application_binding "$test_root/extra-domain-app.json" a1 env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'extra site Application domain passed the binding check'
fi
jq '.env += "\nBUILDHOUND_SITE_NOINDEX=false"' "$test_root/bound-site-app.json" > "$test_root/duplicate-env-app.json"
if require_site_application_binding "$test_root/duplicate-env-app.json" a1 env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'duplicate conflicting site environment entry passed the binding check'
fi
jq '.domains[0].port = 3000' "$test_root/bound-site-app.json" > "$test_root/wrong-port-app.json"
if require_site_application_binding "$test_root/wrong-port-app.json" a1 env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'wrong site domain port passed the binding check'
fi
jq '.domains[0].certificateType = "letsencrypt" | .domains[0].customCertResolver = "attacker"' \
  "$test_root/bound-site-app.json" > "$test_root/certificate-drift-app.json"
if require_site_application_binding "$test_root/certificate-drift-app.json" a1 env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'site certificate/resolver drift passed the binding check'
fi
jq '.domains[0].middlewares = ["redirect-to-attacker"]' \
  "$test_root/bound-site-app.json" > "$test_root/middleware-drift-app.json"
if require_site_application_binding "$test_root/middleware-drift-app.json" a1 env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'site middleware drift passed the binding check'
fi
jq '.domains[0].forwardAuthEnabled = true' \
  "$test_root/bound-site-app.json" > "$test_root/forward-auth-drift-app.json"
if require_site_application_binding "$test_root/forward-auth-drift-app.json" a1 env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'site forward-auth drift passed the binding check'
fi
jq '.redirects = [{redirectId:"redirect1",regex:".*",replacement:"https://attacker.example"}]' \
  "$test_root/bound-site-app.json" > "$test_root/redirect-drift-app.json"
if require_site_application_binding "$test_root/redirect-drift-app.json" a1 env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'pre-update Application redirect drift passed the binding check'
fi
if require_exact_release_application_state "$test_root/redirect-drift-app.json" \
    "$test_root/bound-site-app.json" a1 "$SITE_IMAGE" ghcr.io staging env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'post-update Application redirect drift passed the exact-state check'
fi
jq '.security = [{securityId:"security1",username:"attacker",password:"secret"}]' \
  "$test_root/bound-site-app.json" > "$test_root/security-drift-app.json"
if require_site_application_binding "$test_root/security-drift-app.json" a1 env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'pre-update Application security drift passed the binding check'
fi
if require_exact_release_application_state "$test_root/security-drift-app.json" \
    "$test_root/bound-site-app.json" a1 "$SITE_IMAGE" ghcr.io staging env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'post-update Application security drift passed the exact-state check'
fi
jq '.mounts = [{mountId:"mount1",hostPath:"/attacker",containerPath:"/data"}]' \
  "$test_root/bound-site-app.json" > "$test_root/mount-drift-app.json"
if require_site_application_binding "$test_root/mount-drift-app.json" a1 env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'pre-update Application mount drift passed the binding check'
fi
if require_exact_release_application_state "$test_root/mount-drift-app.json" \
    "$test_root/bound-site-app.json" a1 "$SITE_IMAGE" ghcr.io staging env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'post-update Application mount drift passed the exact-state check'
fi
jq '.ports = [{publishedPort:8443,targetPort:8080}]' "$test_root/bound-site-app.json" > "$test_root/port-drift-app.json"
if require_site_application_binding "$test_root/port-drift-app.json" a1 env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'pre-update Application published port passed the binding check'
fi
if require_exact_release_application_state "$test_root/port-drift-app.json" \
    "$test_root/bound-site-app.json" a1 "$SITE_IMAGE" ghcr.io staging env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'post-update Application published port passed the exact-state check'
fi
jq '.endpointSpecSwarm.Ports = [{PublishedPort:8443,TargetPort:8080,Protocol:"tcp"}]' \
  "$test_root/bound-site-app.json" > "$test_root/endpoint-port-drift-app.json"
if require_site_application_binding "$test_root/endpoint-port-drift-app.json" a1 env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'pre-update Application endpoint publish state passed the binding check'
fi
if require_exact_release_application_state "$test_root/endpoint-port-drift-app.json" \
    "$test_root/bound-site-app.json" a1 "$SITE_IMAGE" ghcr.io staging env1 '' \
    https://site.example.test https://dashboard.example.test true; then
  fail_test 'post-update Application endpoint publish state passed the exact-state check'
fi

reset_api
assert_eq "$(main current-source-commit --compose-id c1)" "$SOURCE_SHA"

reset_api
FAKE_MODE=manual
assert_status 1 main current-source-commit --compose-id c1
grep -F 'lacks tracked source lineage' "$test_root/status-stderr" >/dev/null || \
  fail_test 'untracked current deployment did not fail closed'
FAKE_MODE=current

reset_api
FAKE_MODE=terminal_invalid_id
assert_status 1 wait_for_deployment 'deployment.allByCompose?composeId=c1' '[]' invalid-id-title
grep -F 'invalid or missing ID' "$test_root/status-stderr" >/dev/null || \
  fail_test 'terminal evidence with invalid ID did not fail closed'

reset_api
assert_status 2 main current-release-id --bad c1
[[ ! -s $test_root/calls ]] || fail_test 'invalid current-release arguments reached the API'
assert_status 2 main current-release-id --compose-id
[[ ! -s $test_root/calls ]] || fail_test 'missing current-release value reached the API'
assert_status 2 main current-release-id --compose-id 'c1&applicationId=attacker'
[[ ! -s $test_root/calls ]] || fail_test 'invalid current-release ID reached the API'
assert_status 2 main current-source-commit --compose-id 'c1&applicationId=attacker'
[[ ! -s $test_root/calls ]] || fail_test 'invalid current-source ID reached the API'

reset_api
assert_status 2 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1
[[ ! -s $test_root/calls ]] || fail_test 'missing app role reached Dokploy APIs'
assert_status 2 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role production
grep -F 'app role must be staging or prod' "$test_root/status-stderr" >/dev/null || \
  fail_test 'invalid app role was not rejected explicitly'
[[ ! -s $test_root/calls ]] || fail_test 'invalid app role reached Dokploy APIs'

jq --arg image "registry.example.test/buildhound/backup@sha256:$(printf '3%.0s' {1..64})" \
  '.backupImage = $image' "$release" > "$test_root/mixed-registry-release.json"
reset_api
assert_status 1 main deploy-release "$test_root/mixed-registry-release.json" \
  --manifest "$manifest" --volume-guard "$guard" --base-repo BuildHound/BuildHound \
  --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
[[ ! -s $test_root/calls ]] || fail_test 'mixed release registry reached Dokploy APIs'

xtrace_secret=xtrace-secret-must-not-appear
set +e
DOKPLOY_URL=https://127.0.0.1:9 DOKPLOY_TOKEN=$xtrace_secret \
  bash -x "$root/deploy/dokploy/dokploy.sh" current-release-id --compose-id c1 \
  > "$test_root/xtrace-stdout" 2> "$test_root/xtrace-stderr"
xtrace_status=$?
set -e
[[ $xtrace_status -ne 0 ]] || fail_test 'xtrace transport probe unexpectedly succeeded'
if grep -Fq -- "$xtrace_secret" "$test_root/xtrace-stdout" "$test_root/xtrace-stderr"; then
  fail_test 'inherited xtrace exposed the Dokploy token'
fi

reset_api
FAKE_MODE=manual
main require-manual-current --compose-id c1

reset_api
FAKE_MODE=current
assert_eq "$(main staging-bootstrap-state --compose-id c1)" established

reset_api
FAKE_MODE=manual
assert_eq "$(main staging-bootstrap-state --compose-id c1)" bootstrap

reset_api
FAKE_MODE=empty
assert_status 1 main staging-bootstrap-state --compose-id c1
grep -F 'no current successful deployment found' "$test_root/status-stderr" >/dev/null || \
  fail_test 'anchorless compose was not rejected explicitly'

reset_api
FAKE_MODE=manual_over_release
assert_status 1 main staging-bootstrap-state --compose-id c1
grep -F 'supersedes an existing release history' "$test_root/status-stderr" >/dev/null || \
  fail_test 'manual deployment atop release history re-opened the bootstrap path'
FAKE_MODE=current

reset_api
assert_status 2 main staging-bootstrap-state --bad c1
[[ ! -s $test_root/calls ]] || fail_test 'invalid staging-bootstrap-state arguments reached the API'
assert_status 2 main staging-bootstrap-state --compose-id 'c1&applicationId=attacker'
[[ ! -s $test_root/calls ]] || fail_test 'invalid staging-bootstrap-state ID reached the API'

reset_api
FAKE_MODE=deploy
: > "$test_root/missing-app-creds"
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
grep -F 'lacks Dokploy v0.29 Docker-provider pull credentials' "$test_root/status-stderr" >/dev/null || \
  fail_test 'missing site pull credentials were not rejected explicitly'
if grep -q '^POST|' "$test_root/calls"; then
  fail_test 'missing site pull credentials reached a Dokploy mutation'
fi

reset_api
FAKE_MODE=deploy
COMPOSE_SERVER_ID=server1
: > "$test_root/missing-app-creds"
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
assert_eq "$(cat "$test_root/integration-targets")" server1
unset COMPOSE_SERVER_ID

reset_api
FAKE_MODE=deploy
COMPOSE_SERVER_ID='server1&registryId=attacker'
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
[[ ! -s $test_root/integration-targets ]] || fail_test 'invalid Compose server target reached registry preflight'
if grep -q '^POST|' "$test_root/calls"; then
  fail_test 'invalid Compose server target reached a Dokploy mutation'
fi
unset COMPOSE_SERVER_ID

reset_api
FAKE_MODE=deploy
COMPOSE_DOMAINS='[{"domainId":"domain1"}]'
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
[[ ! -s $test_root/integration-targets ]] || fail_test 'attached Compose domain reached registry preflight'
if grep -q '^POST|' "$test_root/calls"; then
  fail_test 'attached Compose domain reached a Dokploy mutation'
fi
unset COMPOSE_DOMAINS

reset_api
FAKE_MODE=deploy
COMPOSE_SERVER_ID=server1
COMPOSE_PERSISTED_SERVER_ID=server2
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
grep -F 'persisted unexpected release Compose state' "$test_root/status-stderr" >/dev/null || \
  fail_test 'remote Compose target drift was not rejected explicitly'
if grep -q '^POST|compose.deploy$' "$test_root/calls"; then
  fail_test 'remote Compose target drift reached deployment'
fi
unset COMPOSE_SERVER_ID COMPOSE_PERSISTED_SERVER_ID

cp -- "$test_root/original-release.json" "$release"
cp -- "$test_root/original-stack.yaml" "$manifest"
reset_api
FAKE_MODE=deploy
COMPOSE_PERSISTED_COMMAND='docker compose -f attacker.yaml up -d'
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
grep -F 'persisted unexpected release Compose state' "$test_root/status-stderr" >/dev/null || \
  fail_test 'persisted custom Compose command was not rejected explicitly'
if grep -q '^POST|compose.deploy$' "$test_root/calls"; then
  fail_test 'persisted custom Compose command reached deployment'
fi
unset COMPOSE_PERSISTED_COMMAND

reset_api
FAKE_MODE=deploy
APPLICATION_PERSISTED_AUTO_DEPLOY=true
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
grep -F 'persisted unexpected release Application state' "$test_root/status-stderr" >/dev/null || \
  fail_test 'persisted site auto-deploy was not rejected explicitly'
if grep -q '^POST|application.deploy$' "$test_root/calls"; then
  fail_test 'persisted site auto-deploy reached deployment'
fi
unset APPLICATION_PERSISTED_AUTO_DEPLOY

reset_api
FAKE_MODE=deploy
APPLICATION_PERSISTED_PLACEMENT='{"Constraints":["node.labels.role==prod"]}'
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
grep -F 'persisted unexpected release Application state' "$test_root/status-stderr" >/dev/null || \
  fail_test 'persisted site placement drift was not rejected explicitly'
if grep -q '^POST|application.deploy$' "$test_root/calls"; then
  fail_test 'persisted site placement drift reached deployment'
fi
unset APPLICATION_PERSISTED_PLACEMENT

# A staging site Application must be transform-free before the first release
# mutation; a persisted mount is therefore a hard fail before compose.update.
reset_api
FAKE_MODE=deploy
APPLICATION_MOUNTS_BEFORE='[{"mountId":"mount1","hostPath":"/attacker","containerPath":"/data"}]'
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
if grep -q '^POST|' "$test_root/calls"; then
  fail_test 'persisted site mount reached a Dokploy mutation'
fi
unset APPLICATION_MOUNTS_BEFORE

# A published port injected after application.update must be detected before
# deployment, proving the Application port set remains the pre-bound empty set.
reset_api
FAKE_MODE=deploy
APPLICATION_PERSISTED_PORTS='[{"publishedPort":8443,"targetPort":8080}]'
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
grep -F 'persisted unexpected release Application state' "$test_root/status-stderr" >/dev/null || \
  fail_test 'persisted site published port was not rejected explicitly'
if grep -q '^POST|application.deploy$' "$test_root/calls"; then
  fail_test 'persisted site published port reached deployment'
fi
unset APPLICATION_PERSISTED_PORTS

# Swarm's advanced endpoint publisher is distinct from Dokploy's ports array;
# it must remain at the exact inactive endpoint spec before deployment.
reset_api
FAKE_MODE=deploy
APPLICATION_PERSISTED_ENDPOINT_SPEC='{"Mode":"vip","Ports":[{"PublishedPort":8443,"TargetPort":8080}]}'
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
grep -F 'persisted unexpected release Application state' "$test_root/status-stderr" >/dev/null || \
  fail_test 'persisted site endpoint publisher was not rejected explicitly'
if grep -q '^POST|application.deploy$' "$test_root/calls"; then
  fail_test 'persisted site endpoint publisher reached deployment'
fi
unset APPLICATION_PERSISTED_ENDPOINT_SPEC

# The site dashboard link is independently bound to the effective Compose
# environment, before any update call can mutate either target.
reset_api
FAKE_MODE=deploy
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://other-dashboard.example.test --site-noindex true
grep -F 'site dashboard URL does not match the release Compose dashboard host' "$test_root/status-stderr" >/dev/null || \
  fail_test 'cross-environment dashboard URL was not rejected explicitly'
if grep -q '^POST|' "$test_root/calls"; then
  fail_test 'cross-environment dashboard URL reached a Dokploy mutation'
fi

# Re-read after deployment must retain the same empty mount set, not merely
# the one observed immediately after application.update.
reset_api
FAKE_MODE=deploy
APPLICATION_DEPLOYED_MOUNTS='[{"mountId":"mount1","hostPath":"/attacker","containerPath":"/data"}]'
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
grep -F 'deployed site image, pull credentials, or registry isolation differs from release' "$test_root/status-stderr" >/dev/null || \
  fail_test 'post-deployment site mount was not rejected explicitly'
unset APPLICATION_DEPLOYED_MOUNTS

reset_api
FAKE_MODE=deploy
APPLICATION_DEPLOYED_ENDPOINT_SPEC='{"Mode":"vip","Ports":[{"PublishedPort":8443,"TargetPort":8080}]}'
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
grep -F 'deployed site image, pull credentials, or registry isolation differs from release' "$test_root/status-stderr" >/dev/null || \
  fail_test 'post-deployment site endpoint publisher was not rejected explicitly'
unset APPLICATION_DEPLOYED_ENDPOINT_SPEC

reset_api
FAKE_MODE=application_binding_drift
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
if grep -q '^POST|' "$test_root/calls"; then
  fail_test 'stale Application environment or site configuration reached a Dokploy mutation'
fi

# A mistyped target ID must never reach an update/deploy endpoint, even when
# the Compose target and release BOM are otherwise valid.
reset_api
FAKE_MODE=deploy
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id stale_a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
if grep -q '^POST|' "$test_root/calls"; then
  fail_test 'mistyped site Application ID reached a Dokploy mutation'
fi

reset_api
FAKE_MODE=deploy
evidence=$(main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true)
jq -e --arg rid "$RID" --arg history "$HISTORY_HASH" '
  . == {
    releaseId:$rid,
    migrationId:"V1__initial",
    migrationHistorySha256:$history,
    composeDeploymentId:"compose-new",
    siteDeploymentId:"site-new"
  }
' >/dev/null <<< "$evidence"

reset_api
FAKE_MODE=deploy
evidence_file=$test_root/release-evidence.json
evidence=$(main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true \
  --evidence-file "$evidence_file")
cmp -s <(printf '%s\n' "$evidence") "$evidence_file" || fail_test 'success evidence file differs from stdout evidence'

expected_calls=$(cat <<'EOF'
GET|compose.one?composeId=c1
GET|application.one?applicationId=a1
GET|deployment.allByCompose?composeId=c1
GET|deployment.all?applicationId=a1
GET|deployment.allByCompose?composeId=c1
POST|compose.update
GET|compose.one?composeId=c1
GET|deployment.allByCompose?composeId=c1
POST|application.update
GET|application.one?applicationId=a1
GET|deployment.allByCompose?composeId=c1
POST|compose.deploy
GET|deployment.allByCompose?composeId=c1
GET|application.one?applicationId=a1
GET|compose.one?composeId=c1
GET|deployment.all?applicationId=a1
POST|application.deploy
GET|deployment.all?applicationId=a1
GET|application.one?applicationId=a1
EOF
)
assert_eq "$(cat "$test_root/calls")" "$expected_calls"

printf 'services:\n  server:\n    image: %s\n    deploy:\n      placement:\n        constraints:\n          - node.labels.role == staging\n  backup:\n    image: %s\n  db:\n    image: %s\n    labels:\n      release: %s\n\n' \
  "$SERVER_IMAGE" "$BACKUP_IMAGE" "$POSTGRES_IMAGE" "$RID" > "$test_root/expected-stack.yaml"
jq -jr '.composeFile' "$test_root/compose-update-body.json" > "$test_root/actual-stack.yaml"
cmp -s "$test_root/expected-stack.yaml" "$test_root/actual-stack.yaml" || \
  fail_test 'deploy-release changed trusted manifest bytes'
jq -e --arg id c1 '
  (keys | sort) == ([
    "composeId","composeFile","composeType","sourceType","command","autoDeploy",
    "enableSubmodules","composePath","suffix","randomize","isolatedDeployment",
    "isolatedDeploymentsVolume","triggerType","watchPaths","repository","owner","branch",
    "githubId","gitlabProjectId","gitlabRepository","gitlabOwner","gitlabBranch",
    "gitlabPathNamespace","gitlabId","bitbucketRepository","bitbucketRepositorySlug",
    "bitbucketOwner","bitbucketBranch","bitbucketId","giteaRepository","giteaOwner",
    "giteaBranch","giteaId","customGitUrl","customGitBranch","customGitSSHKeyId"
  ] | sort) and
  .composeId == $id and .composeType == "stack" and .sourceType == "raw" and
  (.composeFile | type) == "string" and .command == "" and .autoDeploy == false and
  .enableSubmodules == false and .composePath == "./docker-compose.yml" and
  .suffix == "" and .randomize == false and .isolatedDeployment == false and
  .isolatedDeploymentsVolume == false and .triggerType == "push" and .watchPaths == [] and
  ([.repository,.owner,.branch,.githubId,.gitlabProjectId,.gitlabRepository,.gitlabOwner,
    .gitlabBranch,.gitlabPathNamespace,.gitlabId,.bitbucketRepository,
    .bitbucketRepositorySlug,.bitbucketOwner,.bitbucketBranch,.bitbucketId,
    .giteaRepository,.giteaOwner,.giteaBranch,.giteaId,.customGitUrl,.customGitBranch,
    .customGitSSHKeyId] | all(. == null))
' "$test_root/compose-update-body.json" >/dev/null
jq -e --arg image "$SITE_IMAGE" '
  . == {applicationId:"a1",dockerImage:$image,sourceType:"docker",autoDeploy:false,registryId:null,
        buildRegistryId:null,rollbackRegistryId:null,
        placementSwarm:{Constraints:["node.labels.role==staging"]}}
' "$test_root/body-9.json" >/dev/null
jq -e --arg title "$TITLE" \
  '. == {composeId:"c1",title:$title}' "$test_root/body-12.json" >/dev/null
jq -e --arg title "$TITLE" \
  '. == {applicationId:"a1",title:$title}' "$test_root/body-17.json" >/dev/null

# A site binding can drift while Compose is running. Staging must detect that
# after recording Compose success and before submitting application.deploy.
reset_api
FAKE_MODE=deploy
APPLICATION_DRIFT_AFTER_COMPOSE=true
evidence_file=$test_root/application-window-drift-evidence.json
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true \
  --evidence-file "$evidence_file"
grep -F 'site Application drifted while the release Compose deployed' "$test_root/status-stderr" >/dev/null || \
  fail_test 'Application drift during the Compose wait was not rejected explicitly'
if grep -q '^POST|application.deploy$' "$test_root/calls"; then
  fail_test 'Application drift during the Compose wait reached site deployment'
fi
jq -e '.composeDeploymentId == "compose-new" and .siteDeploymentId == null' \
  "$evidence_file" >/dev/null || fail_test 'Application window drift lost Compose evidence'
unset APPLICATION_DRIFT_AFTER_COMPOSE

# Compose environment/server state can also drift during its deployment. The
# staging site must remain blocked even though the Compose deployment succeeded.
reset_api
FAKE_MODE=deploy
COMPOSE_DRIFT_AFTER_DEPLOY=true
evidence_file=$test_root/compose-window-drift-evidence.json
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true \
  --evidence-file "$evidence_file"
grep -F 'release Compose drifted while it deployed' "$test_root/status-stderr" >/dev/null || \
  fail_test 'Compose drift during the deployment wait was not rejected explicitly'
if grep -q '^POST|application.deploy$' "$test_root/calls"; then
  fail_test 'Compose drift during the deployment wait reached site deployment'
fi
jq -e '.composeDeploymentId == "compose-new" and .siteDeploymentId == null' \
  "$evidence_file" >/dev/null || fail_test 'Compose window drift lost deployment evidence'
unset COMPOSE_DRIFT_AFTER_DEPLOY

# Same-title deployments arriving in both old baseline windows belong to the
# other actor. Refreshed staging baselines must exclude them from this run.
reset_api
FAKE_MODE=deploy
STALE_RELEASE_BASELINES=true
evidence=$(main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true)
jq -e '
  .composeDeploymentId == "compose-new" and .siteDeploymentId == "site-new"
' >/dev/null <<< "$evidence" || fail_test 'intervening same-title deployment was attributed to this run'
assert_eq "$(cat "$test_root/calls")" "$expected_calls"
unset STALE_RELEASE_BASELINES

# Production preserves its established joint submission contract: both deploy
# requests are accepted before Compose polling begins, then Compose/site are
# polled in that order.
reset_api
FAKE_MODE=deploy
evidence=$(main deploy-release "$release" --manifest "$production_manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role prod)
jq -e '
  .composeDeploymentId == "compose-new" and .siteDeploymentId == "site-new"
' >/dev/null <<< "$evidence"
expected_production_calls=$(cat <<'EOF'
GET|compose.one?composeId=c1
GET|application.one?applicationId=a1
GET|deployment.allByCompose?composeId=c1
GET|deployment.all?applicationId=a1
GET|deployment.allByCompose?composeId=c1
POST|compose.update
GET|compose.one?composeId=c1
POST|application.update
GET|application.one?applicationId=a1
POST|compose.deploy
POST|application.deploy
GET|deployment.allByCompose?composeId=c1
GET|deployment.all?applicationId=a1
GET|application.one?applicationId=a1
EOF
)
assert_eq "$(cat "$test_root/calls")" "$expected_production_calls"

# Production without a site remains Compose-only.
reset_api
FAKE_MODE=deploy
main deploy-release "$release" --manifest "$production_manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --skip-site --app-role prod >/dev/null
if grep -q 'application' "$test_root/calls"; then
  fail_test 'production skip-site deploy touched an application endpoint'
fi

# --skip-site is an explicit target compatibility/emergency path: dashboard-only
# deploy touches no application.* endpoint and reports a null site deployment.
reset_api
FAKE_MODE=deploy
assert_status 2 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --skip-site --app-role staging
grep -F 'skip-site conflicts with a site application ID' "$test_root/status-stderr" >/dev/null || \
  fail_test 'skip-site with a site application ID was not rejected explicitly'
[[ ! -s $test_root/calls ]] || fail_test 'conflicting skip-site arguments reached the API'

reset_api
FAKE_MODE=deploy
assert_status 2 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --skip-site --app-role staging \
  --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
grep -F 'skip-site conflicts with site configuration' "$test_root/status-stderr" >/dev/null || \
  fail_test 'skip-site accepted a site configuration contract'
[[ ! -s $test_root/calls ]] || fail_test 'skip-site configuration reached the API'

reset_api
FAKE_MODE=deploy
evidence=$(main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --skip-site --app-role staging)
jq -e --arg rid "$RID" --arg history "$HISTORY_HASH" '
  . == {
    releaseId:$rid,
    migrationId:"V1__initial",
    migrationHistorySha256:$history,
    composeDeploymentId:"compose-new",
    siteDeploymentId:null
  }
' >/dev/null <<< "$evidence"
if grep -q 'application' "$test_root/calls"; then
  fail_test 'skip-site deploy touched an application endpoint'
fi
expected_skip_calls=$(cat <<'EOF'
GET|compose.one?composeId=c1
GET|deployment.allByCompose?composeId=c1
GET|deployment.allByCompose?composeId=c1
POST|compose.update
GET|compose.one?composeId=c1
GET|deployment.allByCompose?composeId=c1
POST|compose.deploy
GET|deployment.allByCompose?composeId=c1
EOF
)
assert_eq "$(cat "$test_root/calls")" "$expected_skip_calls"

cp -- "$test_root/original-release.json" "$release"
cp -- "$test_root/original-stack.yaml" "$manifest"
reset_api
FAKE_MODE=deploy_predecessor_drift
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true \
  --expected-current-release-id "$RID"
grep -F 'current release changed after backup selection' "$test_root/status-stderr" >/dev/null || \
  fail_test 'predecessor drift was not rejected explicitly'
if grep -q '^POST|' "$test_root/calls"; then
  fail_test 'predecessor drift reached a Dokploy mutation'
fi

cp -- "$test_root/original-release.json" "$release"
cp -- "$test_root/original-stack.yaml" "$manifest"
reset_api
FAKE_MODE=deploy_terminal
evidence_file=$test_root/compose-failure-evidence.json
assert_status 42 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true \
  --evidence-file "$evidence_file"
grep -F 'Dokploy deployment reached a failed terminal state' "$test_root/status-stderr" >/dev/null || \
  fail_test 'terminal release failure lost its distinct diagnostic'
[[ $(grep -c '^GET|deployment.all?applicationId=a1$' "$test_root/calls") -eq 1 ]] || \
  fail_test 'terminal Compose failure continued polling the site deployment'
if grep -q '^POST|application.deploy$' "$test_root/calls"; then
  fail_test 'terminal Compose failure submitted the site deployment'
fi
jq -e --arg rid "$RID" --arg history "$HISTORY_HASH" '
  . == {releaseId:$rid,migrationId:"V1__initial",migrationHistorySha256:$history,
        composeDeploymentId:"compose-new",siteDeploymentId:null}
' "$evidence_file" >/dev/null || fail_test 'Compose terminal failure did not retain its observed deployment ID'

cp -- "$test_root/original-release.json" "$release"
cp -- "$test_root/original-stack.yaml" "$manifest"
reset_api
FAKE_MODE=deploy_site_terminal
evidence_file=$test_root/site-failure-evidence.json
assert_status 42 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true \
  --evidence-file "$evidence_file"
jq -e --arg rid "$RID" --arg history "$HISTORY_HASH" '
  . == {releaseId:$rid,migrationId:"V1__initial",migrationHistorySha256:$history,
        composeDeploymentId:"compose-new",siteDeploymentId:"site-new"}
' "$evidence_file" >/dev/null || fail_test 'site failure did not retain both observed deployment IDs'

cp -- "$test_root/original-release.json" "$release"
cp -- "$test_root/original-stack.yaml" "$manifest"
reset_api
FAKE_MODE=deploy_registry_drift
assert_status 1 main deploy-release "$release" --manifest "$manifest" --volume-guard "$guard" \
  --base-repo BuildHound/BuildHound --compose-id c1 --site-application-id a1 --app-role staging --site-url https://site.example.test --site-dashboard-url https://dashboard.example.test --site-noindex true
grep -F 'registry isolation differs' "$test_root/status-stderr" >/dev/null || \
  fail_test 'final registry drift was not rejected explicitly'

deploy_review() {
  [[ $# -eq 11 && $1 == BuildHound/BuildHound && $2 == BuildHound/BuildHound &&
     $3 == 42 && $4 == "$SOURCE_SHA" && $5 == open && $6 == true && $7 == env1 &&
     $8 == reviews.example.test && $9 == "$SERVER_IMAGE" &&
     ${10} == "$SITE_IMAGE" && ${11} == 12345.1 ]] || return 91
  printf '{"name":"mr42","composeId":"c42","deploymentId":"d42"}\n'
}
_review_require_supported_dokploy_version() {
  printf 'version\n' >> "$test_root/review-call-order"
  [[ ${VERSION_OK:-true} == true ]]
}
list_reviews() {
  [[ $# -eq 2 && $1 == BuildHound/BuildHound && $2 == env1 ]] || return 92
  printf '[{"pr":42}]\n'
}
count_reviews() {
  [[ $# -eq 3 && $1 == BuildHound/BuildHound && $2 == env1 && $3 == 42 ]] || return 93
  printf '1\n'
}
scrub_review() {
  [[ $# -eq 7 && $1 == BuildHound/BuildHound && $2 == 42 && $3 == env1 &&
     $4 == reviews.example.test && $5 == c42 && $6 == "$SOURCE_SHA" && $7 == 12345.1 ]] || return 95
  printf '{"scrubbed": "mr42", "deploymentId": "d43"}\n'
}
retire_review() {
  [[ $# -eq 7 && $1 == BuildHound/BuildHound && $2 == 42 && $3 == env1 &&
     $4 == reviews.example.test && $5 == c42 && $6 == "$SOURCE_SHA" && $7 == 12345.1 ]] || return 96
  printf '{"retired": "mr42"}\n'
}

INTEGRATION_CALLS=0
: > "$test_root/review-call-order"
if main deploy-review --base-repo BuildHound/BuildHound --head-repo BuildHound/BuildHound \
    --pr 42 --sha "$SOURCE_SHA" --state closed --label-present --environment-id env1 \
    --dns-suffix reviews.example.test --server-image "$SERVER_IMAGE" \
    --site-image "$SITE_IMAGE" --attempt-id 12345.1 >/dev/null 2>&1; then
  fail_test 'ineligible review input was accepted'
fi
[[ $INTEGRATION_CALLS -eq 0 ]] || fail_test 'ineligible review input reached integration APIs'
test ! -s "$test_root/review-call-order" || fail_test 'ineligible review input reached the version API'

VERSION_OK=false
if main deploy-review --base-repo BuildHound/BuildHound \
    --head-repo BuildHound/BuildHound --pr 42 --sha "$SOURCE_SHA" --state open \
    --label-present --environment-id env1 --dns-suffix reviews.example.test \
    --server-image "$SERVER_IMAGE" --site-image "$SITE_IMAGE" --attempt-id 12345.1 \
    >/dev/null 2>&1; then
  fail_test 'unsupported Dokploy version reached the review integration preflight'
fi
VERSION_OK=true
assert_eq "$(< "$test_root/review-call-order")" version
: > "$test_root/review-call-order"

review_result=$(main deploy-review --base-repo BuildHound/BuildHound \
  --head-repo BuildHound/BuildHound --pr 42 --sha "$SOURCE_SHA" --state open \
  --label-present --environment-id env1 --dns-suffix reviews.example.test \
  --server-image "$SERVER_IMAGE" --site-image "$SITE_IMAGE" --attempt-id 12345.1)
jq -e '.name == "mr42" and .composeId == "c42" and .deploymentId == "d42"' \
  >/dev/null <<< "$review_result"
assert_eq "$(< "$test_root/review-call-order")" $'version\nintegration'
assert_eq "$(main list-reviews --base-repo BuildHound/BuildHound --environment-id env1)" '[{"pr":42}]'
assert_eq "$(main count-reviews --base-repo BuildHound/BuildHound --environment-id env1 --exclude-pr 42)" 1
assert_eq "$(main scrub-review --base-repo BuildHound/BuildHound --pr 42 --environment-id env1 \
  --dns-suffix reviews.example.test --expected-compose-id c42 --expected-sha "$SOURCE_SHA" \
  --expected-attempt-id 12345.1)" \
  '{"scrubbed": "mr42", "deploymentId": "d43"}'
assert_eq "$(main retire-review --base-repo BuildHound/BuildHound --pr 42 --environment-id env1 \
  --dns-suffix reviews.example.test --expected-compose-id c42 --expected-sha "$SOURCE_SHA" \
  --expected-attempt-id 12345.1)" \
  '{"retired": "mr42"}'

if find "$test_root" -maxdepth 1 -type d -name 'buildhound-release.*' | grep -q .; then
  fail_test 'deploy-release left a private workspace behind'
fi

printf 'Dokploy delivery CLI validated\n'
