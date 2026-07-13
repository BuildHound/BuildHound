#!/bin/sh
set -eu

root=$(mktemp -d)
bin="$root/bin"
mkdir -p "$bin"
trap 'rm -rf "$root"' EXIT
delete_log="$root/deletes"
real_jq=$(command -v jq)
export DELETE_LOG="$delete_log" REAL_JQ="$real_jq"

cat > "$bin/python3" <<'EOF'
#!/bin/sh
set -eu
command=$2
shift 2
case "$command" in
  list-reviews)
    printf '%s\n' "$REVIEWS_JSON"
    ;;
  delete-review)
    pr=
    while [ "$#" -gt 0 ]; do
      if [ "$1" = --pr ]; then pr=$2; break; fi
      shift
    done
    printf '%s\n' "$pr" >> "$DELETE_LOG"
    if [ "${FAIL_DELETE_PR:-}" = "$pr" ]; then exit 1; fi
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
  "api repos/BuildHound/BuildHound --jq .owner.type") printf 'Organization\n'; exit 0 ;;
  "api repos/BuildHound/BuildHound/actions/workflows/review-images.yml --jq .id") printf '99\n'; exit 0 ;;
  *"packages/container/buildhound-server/versions"*|*"packages/container/buildhound-site/versions"*)
    if [ "${FAIL_IMAGE_PR:-}" = "${REVIEW_PR:-}" ]; then exit 1; fi
    printf '[[]]\n'; exit 0
    ;;
esac
url=$2
case "$url" in
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
chmod +x "$bin/python3" "$bin/gh" "$bin/date"

sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
review() {
  printf '{"pr":%s,"createdAt":%s,"composeId":"compose-%s","sha":"%s"}' "$1" "$2" "$1" "$sha"
}

run_reconcile() {
  ttl=$1
  reviews=$2
  states=$3
  labels=$4
  fail_delete=${5:-}
  fail_gh=${6:-}
  fail_image=${7:-}
  : > "$delete_log"
  PATH="$bin:$PATH" \
    TTL_HOURS="$ttl" \
    GITHUB_REPOSITORY=BuildHound/BuildHound \
    ENVIRONMENT_ID=review \
    REVIEWS_JSON="$reviews" \
    PR_STATES="$states" \
    PR_LABELS="$labels" \
    FAIL_DELETE_PR="$fail_delete" \
    GH_FAIL_PR="$fail_gh" \
    FAIL_IMAGE_PR="$fail_image" \
    sh deploy/dokploy/reconcile-reviews.sh
}

assert_deletes() {
  expected=$1
  actual=$(paste -sd, "$delete_log")
  if [ "$actual" != "$expected" ]; then
    printf 'expected deletes %s, got %s\n' "$expected" "$actual" >&2
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

first=$(review 1 '"fresh"')
second=$(review 2 '"fresh"')
if run_reconcile 1 "[$first,$second]" '{"1":"closed","2":"closed"}' '{}' 1; then exit 1; fi
assert_deletes 1,2

expired=$(review 3 '"expired"')
run_reconcile 1 "[$expired]" '{"3":"open"}' '{"3":["deploy-review"]}'
assert_deletes 3

fresh=$(review 4 '"fresh"')
run_reconcile 1 "[$fresh]" '{"4":"open"}' '{"4":["deploy-review"]}'
assert_deletes ''

failed_lookup=$(review 5 '"fresh"')
later_closed=$(review 6 '"fresh"')
if run_reconcile 1 "[$failed_lookup,$later_closed]" '{"5":"open","6":"closed"}' '{"5":["deploy-review"]}' '' 5; then exit 1; fi
assert_deletes 6

image_cleanup_retry=$(review 7 '"fresh"')
if run_reconcile 1 "[$image_cleanup_retry]" '{"7":"closed"}' '{}' '' '' 7; then exit 1; fi
assert_deletes ''
run_reconcile 1 "[$image_cleanup_retry]" '{"7":"closed"}' '{}'
assert_deletes 7

printf 'review reconciliation validated\n'
