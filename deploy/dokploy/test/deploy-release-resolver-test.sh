#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/../../.." && pwd)
TEST_ROOT=$(mktemp -d)
trap 'rm -rf -- "$TEST_ROOT"' EXIT
mkdir -p "$TEST_ROOT/bin" "$TEST_ROOT/runner"

SOURCE_RUN=100
SOURCE_SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

cat >"$TEST_ROOT/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

SOURCE_RUN=100
REVIEW_RUN=200
SOURCE_SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
REVIEW_SHA=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
REPOSITORY=BuildHound/BuildHound

emit_api() {
  case "$1" in
    "repos/$REPOSITORY/actions/runs/$SOURCE_RUN")
      jq -cn --arg repository "$REPOSITORY" --arg sha "$SOURCE_SHA" \
        '{conclusion:"success",workflow_id:10,event:"push",repository:{full_name:$repository},head_branch:"main",head_sha:$sha}'
      ;;
    "repos/$REPOSITORY/actions/workflows/publish-deploy-images.yml")
      printf '%s\n' '{"id":10}'
      ;;
    "repos/$REPOSITORY/commits/$SOURCE_SHA/pulls")
      jq -cn --arg repository "$REPOSITORY" --arg source "$SOURCE_SHA" --arg review "$REVIEW_SHA" \
        '[{number:24,merged_at:"2026-07-14T15:56:50Z",merge_commit_sha:$source,head:{sha:$review,repo:{full_name:$repository}},base:{ref:"main",repo:{full_name:$repository}}}]'
      ;;
    "repos/$REPOSITORY/issues/24/labels")
      printf '%s\n' '[{"name":"deploy-review"}]'
      ;;
    "repos/$REPOSITORY/actions/runs/$SOURCE_RUN/artifacts?per_page=100")
      jq -cn --arg name "release-$SOURCE_SHA" '{artifacts:[{name:$name,expired:false}]}'
      ;;
    "repos/$REPOSITORY/commits/$REVIEW_SHA/statuses?per_page=100")
      jq -cn --arg url "https://github.com/$REPOSITORY/actions/runs/$REVIEW_RUN" \
        '[{context:"buildhound/review-deployed/pr-24",state:"success",target_url:$url}]'
      ;;
    "repos/$REPOSITORY/actions/runs/$REVIEW_RUN")
      head_repository=${FAKE_REVIEW_HEAD_REPOSITORY:-$REPOSITORY}
      head_sha=${FAKE_REVIEW_HEAD_SHA:-$REVIEW_SHA}
      jq -cn --arg repository "$REPOSITORY" --arg headRepository "$head_repository" --arg sha "$head_sha" \
        '{status:"completed",conclusion:"success",workflow_id:11,event:"pull_request_target",repository:{full_name:$repository},head_repository:{full_name:$headRepository},head_sha:$sha,run_attempt:1,pull_requests:[]}'
      ;;
    "repos/$REPOSITORY/actions/workflows/review-environment.yml")
      printf '%s\n' '{"id":11}'
      ;;
    "repos/$REPOSITORY/actions/runs/$REVIEW_RUN/artifacts?per_page=100")
      printf '%s\n' '{"artifacts":[{"name":"review-attestation","expired":false}]}'
      ;;
    *)
      printf 'unexpected gh api endpoint: %s\n' "$1" >&2
      exit 1
      ;;
  esac
}

case "${1-} ${2-}" in
  "api "*)
    response=$(emit_api "$2")
    if [ "${3-}" = --jq ]; then
      jq -r "$4" <<<"$response"
    else
      printf '%s\n' "$response"
    fi
    ;;
  "run download")
    shift 2
    run_id=$1
    shift
    name=
    dir=
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --name) name=$2; shift 2 ;;
        --dir) dir=$2; shift 2 ;;
        *) printf 'unexpected gh run download argument: %s\n' "$1" >&2; exit 1 ;;
      esac
    done
    [ "$run_id" = "$REVIEW_RUN" ]
    [ "$name" = review-attestation ]
    mkdir -p "$dir"
    jq -cn --arg repository "$REPOSITORY" --arg sha "$REVIEW_SHA" \
      --arg server "ghcr.io/buildhound/server@sha256:$(printf '1%.0s' {1..64})" \
      --arg site "ghcr.io/buildhound/site@sha256:$(printf '2%.0s' {1..64})" \
      '{schema:1,repository:$repository,runId:"200",attemptId:"200.1",pr:24,headSha:$sha,composeId:"compose-24",deploymentId:"deployment-24",serverImage:$server,siteImage:$site}' \
      >"$dir/review.json"
    ;;
  *)
    printf 'unexpected gh command: %s\n' "$*" >&2
    exit 1
    ;;
esac
EOF
chmod +x "$TEST_ROOT/bin/gh"

awk '
  /^      - id: resolve$/ { found = 1; next }
  found && /^        run: \|$/ { in_run = 1; next }
  in_run && $0 !~ /^          / && $0 !~ /^$/ { exit }
  in_run { sub(/^          /, ""); print }
' "$ROOT/.github/workflows/deploy-release.yml" >"$TEST_ROOT/resolver.sh"

run_resolver() {
  output=$1
  shift
  env "$@" \
    PATH="$TEST_ROOT/bin:$PATH" \
    RUNNER_TEMP="$TEST_ROOT/runner" \
    GITHUB_OUTPUT="$output" \
    GITHUB_REPOSITORY=BuildHound/BuildHound \
    GITHUB_REF=refs/heads/main \
    EVENT_NAME=workflow_run \
    DEFAULT_BRANCH=main \
    EVENT_SOURCE_RUN="$SOURCE_RUN" \
    EVENT_SOURCE_SHA="$SOURCE_SHA" \
    INPUT_TARGET='' \
    INPUT_STAGING_RUN='' \
    INPUT_SOURCE_RUN='' \
    INPUT_RELEASE_ARTIFACT='' \
    INPUT_BACKUP_OBJECT='' \
    INPUT_BOOTSTRAP='' \
    INPUT_ROLLBACK='' \
    bash "$TEST_ROOT/resolver.sh"
}

run_resolver "$TEST_ROOT/output"

grep -Fx 'eligible=true' "$TEST_ROOT/output" >/dev/null
grep -Fx 'target=staging' "$TEST_ROOT/output" >/dev/null
grep -Fx 'review_run_id=200' "$TEST_ROOT/output" >/dev/null

if run_resolver "$TEST_ROOT/foreign-output" FAKE_REVIEW_HEAD_REPOSITORY=attacker/example; then
  printf 'resolver accepted a foreign review head repository\n' >&2
  exit 1
fi
if run_resolver "$TEST_ROOT/wrong-sha-output" FAKE_REVIEW_HEAD_SHA=cccccccccccccccccccccccccccccccccccccccc; then
  printf 'resolver accepted a mismatched review head SHA\n' >&2
  exit 1
fi

printf 'deploy release resolver validates merged review run identity without PR associations\n'
