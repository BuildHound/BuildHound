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
output_log="$root/output"
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
  *"packages/container/buildhound-server/versions"*|*"packages/container/buildhound-site/versions"*)
    if [ "${FAIL_IMAGE_PR:-}" = "${REVIEW_PR:-}" ]; then exit 1; fi
    printf '[[]]\n'; exit 0
    ;;
  *"issues?labels=deploy-review&state=open"*)
    printf '%s\n' "$DESIRED_PRS" | "$REAL_JQ" -r '.[]'
    exit 0
    ;;
esac
url=$2
case "$url" in
  */issues/*)
    pr=${url##*/issues/}
    if [ "${GH_FAIL_PR:-}" = "$pr" ]; then exit 1; fi
    state=$(printf '%s\n' "$PR_STATES" | "$REAL_JQ" -er --arg pr "$pr" '.[$pr]')
    labels=$(printf '%s\n' "$PR_LABELS" | "$REAL_JQ" -c --arg pr "$pr" '.[$pr] // []')
    printf '{"state":"%s","labels":%s}\n' "$state" "$labels"
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

run_reconcile() {
  ttl=$1
  reviews=$2
  desired=$3
  states=${4:-'{}'}
  labels=${5:-'{}'}
  fail_delete=${6:-}
  fail_gh=${7:-}
  fail_image=${8:-}
  fail_retire=${9:-}
  : > "$delete_log"
  : > "$attempt_log"
  : > "$retire_log"
  : > "$order_log"
  : > "$output_log"
  PATH="$bin:$PATH" \
    TTL_HOURS="$ttl" \
    GITHUB_REPOSITORY=BuildHound/BuildHound \
    ENVIRONMENT_ID=review \
    DNS_SUFFIX=review.buildhound.dev \
    REVIEWS_JSON="$reviews" \
    DESIRED_PRS="$desired" \
    PR_STATES="$states" \
    PR_LABELS="$labels" \
    FAIL_DELETE_PR="$fail_delete" \
    GH_FAIL_PR="$fail_gh" \
    FAIL_IMAGE_PR="$fail_image" \
    FAIL_RETIRE_PR="$fail_retire" \
    sh deploy/dokploy/reconcile-reviews.sh > "$output_log" 2>&1
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

assert_output_has() {
  if ! grep -F -- "$1" "$output_log" >/dev/null; then
    printf 'expected converge output to contain [%s]\n' "$1" >&2
    cat "$output_log" >&2
    exit 1
  fi
}

for invalid_ttl in '' 0 01 '1+1' 87601 999999; do
  if run_reconcile "$invalid_ttl" '[]' '[]'; then exit 1; fi
  assert_deletes ''
done

# Invalid metadata preserves the review (fail-open per item, run fails).
bad_created=$(review 1 null)
closed=$(review 2 '"fresh"')
if run_reconcile 1 "[$bad_created,$closed]" '[]' '{"2":"closed"}'; then exit 1; fi
assert_deletes 2
assert_retires '2|'
assert_order 'scrub:2,image:2,retire:2'

# Two undesired reviews: a scrub failure on the first never blocks the second.
first=$(review 1 '"fresh"')
second=$(review 2 '"fresh"')
if run_reconcile 1 "[$first,$second]" '[]' '{"1":"closed","2":"closed"}' '{}' 1; then exit 1; fi
assert_deletes 1,2
assert_retires '2|'

# TTL expiry retires even a desired (open + labelled) review, with no
# pre-retire re-check — and the summary immediately reports it missing.
expired=$(review 3 '"expired"')
run_reconcile 1 "[$expired]" '[3]'
assert_deletes 3
assert_retires '3|'
assert_output_has 'Open labelled PR #3 has no live review environment'
assert_output_has 'missing=1'

# Fresh and desired: kept.
fresh=$(review 4 '"fresh"')
run_reconcile 1 "[$fresh]" '[4]'
assert_deletes ''
assert_retires ''
assert_output_has 'kept=1'

# Already-retired anchors are skipped, and counted as such.
retired=$(review 12 '"fresh"' '' true)
run_reconcile 1 "[$retired]" '[]'
assert_deletes ''
assert_retires ''
assert_output_has 'skipped-retired=1'

# Not in the desired snapshot but the re-check says open + labelled (label
# raced the snapshot): kept. A failing re-check preserves the review.
raced=$(review 5 '"fresh"')
run_reconcile 1 "[$raced]" '[]' '{"5":"open"}' '{"5":["deploy-review"]}'
assert_deletes ''
assert_retires ''
failed_lookup=$(review 5 '"fresh"')
later_closed=$(review 6 '"fresh"')
if run_reconcile 1 "[$failed_lookup,$later_closed]" '[]' '{"6":"closed"}' '{}' '' 5; then exit 1; fi
assert_deletes 6
assert_retires '6|'

# Open but unlabelled: retired.
unlabelled=$(review 15 '"fresh"')
run_reconcile 1 "[$unlabelled]" '[]' '{"15":"open"}' '{"15":[]}'
assert_deletes 15
assert_retires '15|'

# Image cleanup failure stops retirement for that review only.
image_cleanup_retry=$(review 7 '"fresh"')
if run_reconcile 1 "[$image_cleanup_retry]" '[]' '{"7":"closed"}' '{}' '' '' 7; then exit 1; fi
assert_deletes 7
assert_retires ''

# Attempt ids ride along into scrub and retire when present.
with_attempt=$(review 8 '"fresh"' 800.1)
run_reconcile 1 "[$with_attempt]" '[]' '{"8":"closed"}'
assert_deletes 8
assert_attempts '8|800.1'
assert_retires '8|800.1'

# Retirement failure surfaces but later reviews still converge.
retire_failure=$(review 13 '"fresh"')
later_retire=$(review 14 '"fresh"')
if run_reconcile 1 "[$retire_failure,$later_retire]" '[]' \
  '{"13":"closed","14":"closed"}' '{}' '' '' '' 13; then exit 1; fi
assert_deletes 13,14
assert_retires '13|,14|'

# Desired PR with no live review: report-only, never a deploy from here.
lonely=$(review 16 '"fresh"' '' true)
run_reconcile 1 "[$lonely]" '[16,17]'
assert_deletes ''
assert_retires ''
assert_output_has 'Open labelled PR #16 has no live review environment'
assert_output_has 'Open labelled PR #17 has no live review environment'
assert_output_has 'missing=2'

# Summary evidence line: counts plus review names, never Dokploy ids.
mixed_keep=$(review 4 '"fresh"')
mixed_drop=$(review 6 '"fresh"')
run_reconcile 1 "[$mixed_keep,$mixed_drop]" '[4]' '{"6":"closed"}'
assert_output_has 'converge summary: kept=1 retired=1 missing=0 skipped-retired=0 kept-names: mr4 retired-names: mr6'
if grep -F 'compose-' "$output_log" >/dev/null; then
  printf 'converge output leaked a Dokploy object id\n' >&2
  exit 1
fi

printf 'review reconciliation validated\n'
