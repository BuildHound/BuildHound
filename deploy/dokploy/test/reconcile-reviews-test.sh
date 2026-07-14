#!/bin/sh
set -eu

root=$(mktemp -d)
bin="$root/bin"
mkdir -p "$bin"
trap 'rm -rf "$root"' EXIT
delete_log="$root/deletes"
attempt_log="$root/attempts"
retire_log="$root/retires"
order_log="$root/order"
real_jq=$(command -v jq)
export DELETE_LOG="$delete_log" ATTEMPT_LOG="$attempt_log" RETIRE_LOG="$retire_log" ORDER_LOG="$order_log" REAL_JQ="$real_jq"

cat > "$bin/bash" <<'EOF'
#!/bin/sh
set -eu
command=$2
shift 2
case "$command" in
  list-reviews)
    printf '%s\n' "$REVIEWS_JSON"
    ;;
  scrub-review)
    pr=
    attempt=
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --pr) pr=$2; shift 2 ;;
        --expected-attempt-id) attempt=$2; shift 2 ;;
        *) shift ;;
      esac
    done
    printf '%s\n' "$pr" >> "$DELETE_LOG"
    printf '%s|%s\n' "$pr" "$attempt" >> "$ATTEMPT_LOG"
    printf 'scrub:%s\n' "$pr" >> "$ORDER_LOG"
    if [ "${FAIL_DELETE_PR:-}" = "$pr" ]; then exit 1; fi
    ;;
  retire-review)
    pr=
    attempt=
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --pr) pr=$2; shift 2 ;;
        --expected-attempt-id) attempt=$2; shift 2 ;;
        *) shift ;;
      esac
    done
    printf '%s|%s\n' "$pr" "$attempt" >> "$RETIRE_LOG"
    printf 'retire:%s\n' "$pr" >> "$ORDER_LOG"
    if [ "${FAIL_RETIRE_PR:-}" = "$pr" ]; then exit 1; fi
    ;;
  *)
    exit 2
    ;;
esac
EOF

cat > "$bin/gh" <<'EOF'
#!/bin/sh
set -eu
case "$*" in
  "api repos/BuildHound/BuildHound --jq .owner.type")
    printf 'image:%s\n' "${REVIEW_PR:-}" >> "$ORDER_LOG"
    printf 'Organization\n'; exit 0
    ;;
  "api repos/BuildHound/BuildHound/actions/workflows/review-images.yml --jq .id") printf '99\n'; exit 0 ;;
  "api repos/BuildHound/BuildHound/actions/workflows/review-environment.yml --jq .id") printf '100\n'; exit 0 ;;
  *"packages/container/buildhound-server/versions"*|*"packages/container/buildhound-site/versions"*)
    if [ "${FAIL_IMAGE_PR:-}" = "${REVIEW_PR:-}" ]; then exit 1; fi
    printf '[[]]\n'; exit 0
    ;;
esac
url=$2
case "$url" in
  */actions/runs/*/attempts/*)
    rest=${url#*/actions/runs/}
    run_id=${rest%%/*}
    run_attempt=${url##*/}
    key="$run_id.$run_attempt"
    printf '%s\n' "$RUNS_JSON" | "$REAL_JQ" -er --arg key "$key" '.[$key]'
    ;;
  */pulls/*)
    pr=${url##*/}
    if [ "${GH_FAIL_PR:-}" = "$pr" ]; then exit 1; fi
    state=$(printf '%s\n' "$PR_STATES" | "$REAL_JQ" -er --arg pr "$pr" '.[$pr]')
    printf '{"state":"%s"}\n' "$state"
    ;;
  */issues/*/labels)
    rest=${url#*/issues/}
    pr=${rest%%/*}
    if [ "${GH_FAIL_PR:-}" = "$pr" ]; then exit 1; fi
    printf '%s\n' "$PR_LABELS" | "$REAL_JQ" -r --arg pr "$pr" '.[$pr] // [] | .[]'
    ;;
  *)
    exit 2
    ;;
esac
EOF

cat > "$bin/date" <<'EOF'
#!/bin/sh
set -eu
if [ "$2" = '+%s' ]; then
  printf '2000000000\n'
  exit 0
fi
case "$3" in
  fresh) printf '1999999999\n' ;;
  expired) printf '1000000000\n' ;;
  future) printf '2000000400\n' ;;
  *) exit 1 ;;
esac
EOF
chmod +x "$bin/bash" "$bin/gh" "$bin/date"

sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
review() {
  attempt=${3:-}
  retired=${4:-false}
  if [ -n "$attempt" ]; then
    printf '{"attemptId":"%s","pr":%s,"retired":%s,"createdAt":%s,"composeId":"compose-%s","sha":"%s"}' \
      "$attempt" "$1" "$retired" "$2" "$1" "$sha"
  else
    printf '{"pr":%s,"retired":%s,"createdAt":%s,"composeId":"compose-%s","sha":"%s"}' \
      "$1" "$retired" "$2" "$1" "$sha"
  fi
}

run_evidence() {
  pr=$1
  attempt_id=$2
  status=$3
  conclusion=$4
  run_attempt=${attempt_id#*.}
  # shellcheck disable=SC2016
  "$real_jq" -cn --arg repository BuildHound/BuildHound --arg sha "$sha" \
    --arg branch main --arg status "$status" --arg conclusion "$conclusion" \
    --argjson pr "$pr" --argjson runAttempt "$run_attempt" '
      {workflow_id:100,run_attempt:$runAttempt,event:"pull_request_target",
       repository:{full_name:$repository},
       pull_requests:[{number:$pr,head:{sha:$sha},base:{ref:$branch}}],
       status:$status,conclusion:(if $conclusion == "null" then null else $conclusion end)}
    '
}

run_reconcile() {
  ttl=$1
  reviews=$2
  states=$3
  labels=$4
  fail_delete=${5:-}
  fail_gh=${6:-}
  fail_image=${7:-}
  runs=${8-}
  fail_retire=${9:-}
  if [ -z "$runs" ]; then runs='{}'; fi
  : > "$delete_log"
  : > "$attempt_log"
  : > "$retire_log"
  : > "$order_log"
  PATH="$bin:$PATH" \
    TTL_HOURS="$ttl" \
    GITHUB_REPOSITORY=BuildHound/BuildHound \
    DEFAULT_BRANCH=main \
    ENVIRONMENT_ID=review \
    DNS_SUFFIX=review.buildhound.dev \
    REVIEWS_JSON="$reviews" \
    PR_STATES="$states" \
    PR_LABELS="$labels" \
    FAIL_DELETE_PR="$fail_delete" \
    GH_FAIL_PR="$fail_gh" \
    FAIL_IMAGE_PR="$fail_image" \
    FAIL_RETIRE_PR="$fail_retire" \
    RUNS_JSON="$runs" \
    sh deploy/dokploy/reconcile-reviews.sh
}

assert_attempts() {
  expected=$1
  actual=$(paste -sd, "$attempt_log")
  if [ "$actual" != "$expected" ]; then
    printf 'expected attempts %s, got %s\n' "$expected" "$actual" >&2
    exit 1
  fi
}

assert_deletes() {
  expected=$1
  actual=$(paste -sd, "$delete_log")
  if [ "$actual" != "$expected" ]; then
    printf 'expected deletes %s, got %s\n' "$expected" "$actual" >&2
    exit 1
  fi
}

assert_retires() {
  expected=$1
  actual=$(paste -sd, "$retire_log")
  if [ "$actual" != "$expected" ]; then
    printf 'expected retirements %s, got %s\n' "$expected" "$actual" >&2
    exit 1
  fi
}

assert_order() {
  expected=$1
  actual=$(paste -sd, "$order_log")
  if [ "$actual" != "$expected" ]; then
    printf 'expected cleanup order %s, got %s\n' "$expected" "$actual" >&2
    exit 1
  fi
}

for invalid_ttl in '' 0 01 '1+1' 87601 999999; do
  if run_reconcile "$invalid_ttl" '[]' '{}' '{}'; then exit 1; fi
  assert_deletes ''
done

bad_created=$(review 1 null)
closed=$(review 2 '"fresh"')
if run_reconcile 1 "[$bad_created,$closed]" '{"1":"closed","2":"closed"}' '{}'; then exit 1; fi
assert_deletes 2
assert_retires '2|'
assert_order 'scrub:2,image:2,retire:2'

first=$(review 1 '"fresh"')
second=$(review 2 '"fresh"')
if run_reconcile 1 "[$first,$second]" '{"1":"closed","2":"closed"}' '{}' 1; then exit 1; fi
assert_deletes 1,2
assert_retires '2|'

expired=$(review 3 '"expired"')
run_reconcile 1 "[$expired]" '{"3":"open"}' '{"3":["deploy-review"]}'
assert_deletes 3
assert_retires '3|'

fresh=$(review 4 '"fresh"')
run_reconcile 1 "[$fresh]" '{"4":"open"}' '{"4":["deploy-review"]}'
assert_deletes ''
assert_retires ''

retired=$(review 12 '"fresh"' '' true)
run_reconcile 1 "[$retired]" '{}' '{}'
assert_deletes ''
assert_retires ''

failed_lookup=$(review 5 '"fresh"')
later_closed=$(review 6 '"fresh"')
if run_reconcile 1 "[$failed_lookup,$later_closed]" '{"5":"open","6":"closed"}' '{"5":["deploy-review"]}' '' 5; then exit 1; fi
assert_deletes 6
assert_retires '6|'

image_cleanup_retry=$(review 7 '"fresh"')
if run_reconcile 1 "[$image_cleanup_retry]" '{"7":"closed"}' '{}' '' '' 7; then exit 1; fi
assert_deletes 7
assert_retires ''

failed=$(review 8 '"fresh"' 800.1)
cancelled=$(review 9 '"fresh"' 900.1)
active=$(review 10 '"fresh"' 1000.1)
successful=$(review 11 '"fresh"' 1100.1)
failed_run=$(run_evidence 8 800.1 completed failure)
cancelled_run=$(run_evidence 9 900.1 completed cancelled)
active_run=$(run_evidence 10 1000.1 in_progress null)
successful_run=$(run_evidence 11 1100.1 completed success)
# shellcheck disable=SC2016
runs=$("$real_jq" -cn --argjson failed "$failed_run" --argjson cancelled "$cancelled_run" \
  --argjson active "$active_run" --argjson successful "$successful_run" \
  '{"800.1":$failed,"900.1":$cancelled,"1000.1":$active,"1100.1":$successful}')
run_reconcile 1 "[$failed,$cancelled,$active,$successful]" \
  '{"8":"open","9":"open","10":"open","11":"open"}' \
  '{"8":["deploy-review"],"9":["deploy-review"],"10":["deploy-review"],"11":["deploy-review"]}' \
  '' '' '' "$runs"
assert_deletes 8,9
assert_attempts '8|800.1,9|900.1'
assert_retires '8|800.1,9|900.1'

retire_failure=$(review 13 '"fresh"')
later_retire=$(review 14 '"fresh"')
if run_reconcile 1 "[$retire_failure,$later_retire]" \
  '{"13":"closed","14":"closed"}' '{}' '' '' '' '{}' 13; then exit 1; fi
assert_deletes 13,14
assert_retires '13|,14|'

printf 'review reconciliation validated\n'
