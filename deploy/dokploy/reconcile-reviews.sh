#!/bin/sh
# Converge review environments toward the desired state derived from GitHub
# (plan 089): the desired set is exactly the open PRs carrying deploy-review;
# every owned, non-retired review outside that set (or past its TTL) is
# retired. Deployment stays event-driven — a desired PR with no live review is
# reported, never deployed from here. This script is the single cleanup
# authority; the cron schedule and the PR-event handlers all run it.
set -eu

: "${TTL_HOURS:?BUILDHOUND_REVIEW_TTL_HOURS must be set}"
: "${GITHUB_REPOSITORY:?}" "${ENVIRONMENT_ID:?}" \
  "${DNS_SUFFIX:?BUILDHOUND_REVIEW_DNS_SUFFIX must be set}"
case "$TTL_HOURS" in
  ''|0*|*[!0-9]*)
    echo "BUILDHOUND_REVIEW_TTL_HOURS must be a positive base-10 integer" >&2
    exit 64
    ;;
esac
if [ "${#TTL_HOURS}" -gt 5 ] || [ "$TTL_HOURS" -gt 87600 ]; then
  echo "BUILDHOUND_REVIEW_TTL_HOURS must be between 1 and 87600" >&2
  exit 64
fi

reviews=$(mktemp)
entries=$(mktemp)
desired=$(mktemp)
trap 'rm -f "$reviews" "$entries" "$desired"' EXIT

# Desired state: open PRs labelled deploy-review. The issues listing filters
# the label server-side; only real PRs count.
gh api "repos/${GITHUB_REPOSITORY}/issues?labels=deploy-review&state=open&per_page=100" \
  --paginate --jq '.[] | select(.pull_request != null) | .number' > "$desired"
while IFS= read -r desired_pr; do
  [ -n "$desired_pr" ] || continue
  case "$desired_pr" in
    0*|*[!0-9]*)
      echo "GitHub returned an invalid open PR number" >&2
      exit 1
      ;;
  esac
done < "$desired"

# Live state: owned reviews in the dedicated review environment. list-reviews
# fails closed on any compose whose ownership metadata is not a review record,
# so a converge run can never act on staging or production objects.
bash deploy/dokploy/dokploy.sh list-reviews \
  --base-repo "$GITHUB_REPOSITORY" \
  --environment-id "$ENVIRONMENT_ID" > "$reviews"
jq -e 'type == "array"' "$reviews" >/dev/null
jq -c '.[]' "$reviews" > "$entries"

now_epoch=$(date -u +%s)
ttl_seconds=$((TTL_HOURS * 3600))
had_error=false
kept_names=''
retired_names=''
kept_count=0
retired_count=0
skipped_retired_count=0

warn_review() {
  printf '::warning::Review %s: %s\n' "$1" "$2"
}

desired_contains() {
  grep -Fx "$1" "$desired" >/dev/null 2>&1
}

while IFS= read -r review; do
  display_pr=$(printf '%s\n' "$review" | jq -r '.pr // "unknown"')
  if ! pr=$(printf '%s\n' "$review" | jq -er '.pr | select(type == "number" and . > 0 and floor == .)'); then
    warn_review "$display_pr" "invalid PR ownership metadata; preserving review"
    had_error=true
    continue
  fi
  if ! retired=$(printf '%s\n' "$review" | jq -er '
    .retired | select(type == "boolean") | if . then "true" else "false" end
  '); then
    warn_review "$pr" "invalid review lifecycle metadata; preserving review"
    had_error=true
    continue
  fi
  if [ "$retired" = true ]; then
    skipped_retired_count=$((skipped_retired_count + 1))
    continue
  fi
  if ! activated=$(printf '%s\n' "$review" | jq -er '
    (.activatedAt // .createdAt) | select(type == "string" and length > 0)
  '); then
    warn_review "$pr" "missing activation time; preserving review"
    had_error=true
    continue
  fi
  if ! activated_epoch=$(date -u -d "$activated" +%s 2>/dev/null); then
    warn_review "$pr" "invalid activation time; preserving review"
    had_error=true
    continue
  fi
  if [ "$activated_epoch" -gt $((now_epoch + 300)) ]; then
    warn_review "$pr" "activation time is in the future; preserving review"
    had_error=true
    continue
  fi
  if ! compose_id=$(printf '%s\n' "$review" | jq -er '.composeId | select(type == "string" and length > 0)'); then
    warn_review "$pr" "missing compose ownership metadata; preserving review"
    had_error=true
    continue
  fi
  if ! sha=$(printf '%s\n' "$review" | jq -er '.sha | select(type == "string" and test("^[0-9a-f]{40}\\z"))'); then
    warn_review "$pr" "invalid SHA ownership metadata; preserving review"
    had_error=true
    continue
  fi
  if ! attempt_id=$(printf '%s\n' "$review" | jq -er '.attemptId // "" | select(type == "string")'); then
    warn_review "$pr" "invalid attempt ownership metadata; preserving review"
    had_error=true
    continue
  fi

  ttl_expired=false
  age=$((now_epoch - activated_epoch))
  if [ "$age" -gt "$ttl_seconds" ]; then
    ttl_expired=true
  fi
  if [ "$ttl_expired" = false ] && desired_contains "$pr"; then
    kept_count=$((kept_count + 1))
    kept_names="$kept_names mr$pr"
    continue
  fi
  # Not desired: re-check the PR once before retiring so a label or reopen
  # that raced this run's snapshot is preserved. The next tick converges
  # anyway; this only avoids churning a just-relabelled environment.
  if [ "$ttl_expired" = false ]; then
    if ! pr_state=$(gh api "repos/${GITHUB_REPOSITORY}/issues/$pr" \
      --jq '{state: .state, labels: [.labels[].name]}' 2>/dev/null); then
      warn_review "$pr" "GitHub PR lookup failed; preserving review"
      had_error=true
      continue
    fi
    if printf '%s\n' "$pr_state" | jq -e '
      .state == "open" and any(.labels[]; . == "deploy-review")
    ' >/dev/null; then
      kept_count=$((kept_count + 1))
      kept_names="$kept_names mr$pr"
      continue
    fi
  fi

  # Retire: scrub the credential-bearing manager-side file, delete the
  # exact-owned GHCR images, then mark the anchor retired.
  if [ -n "$attempt_id" ]; then
    scrub_ok=false
    if bash deploy/dokploy/dokploy.sh scrub-review \
      --base-repo "$GITHUB_REPOSITORY" --pr "$pr" --environment-id "$ENVIRONMENT_ID" \
      --dns-suffix "$DNS_SUFFIX" --expected-compose-id "$compose_id" --expected-sha "$sha" \
      --expected-attempt-id "$attempt_id"; then scrub_ok=true; fi
  else
    scrub_ok=false
    if bash deploy/dokploy/dokploy.sh scrub-review \
      --base-repo "$GITHUB_REPOSITORY" --pr "$pr" --environment-id "$ENVIRONMENT_ID" \
      --dns-suffix "$DNS_SUFFIX" --expected-compose-id "$compose_id" --expected-sha "$sha"; then
      scrub_ok=true
    fi
  fi
  if [ "$scrub_ok" != true ]; then
    warn_review "$pr" "Dokploy scrub failed; later reviews will still be reconciled"
    had_error=true
  elif ! REVIEW_PR="$pr" deploy/dokploy/delete-review-images.sh; then
    warn_review "$pr" "Stack was scrubbed but exact-owned GHCR cleanup failed"
    had_error=true
  elif [ -n "$attempt_id" ] && ! bash deploy/dokploy/dokploy.sh retire-review \
    --base-repo "$GITHUB_REPOSITORY" --pr "$pr" --environment-id "$ENVIRONMENT_ID" \
    --dns-suffix "$DNS_SUFFIX" --expected-compose-id "$compose_id" --expected-sha "$sha" \
    --expected-attempt-id "$attempt_id"; then
    warn_review "$pr" "GHCR cleanup succeeded but Dokploy anchor retirement failed"
    had_error=true
  elif [ -z "$attempt_id" ] && ! bash deploy/dokploy/dokploy.sh retire-review \
    --base-repo "$GITHUB_REPOSITORY" --pr "$pr" --environment-id "$ENVIRONMENT_ID" \
    --dns-suffix "$DNS_SUFFIX" --expected-compose-id "$compose_id" --expected-sha "$sha"; then
    warn_review "$pr" "GHCR cleanup succeeded but Dokploy anchor retirement failed"
    had_error=true
  else
    retired_count=$((retired_count + 1))
    retired_names="$retired_names mr$pr"
  fi
done < "$entries"

# Report-only: desired PRs with no live, non-retired review. Deployment is
# event-driven; the notice makes the gap visible in the run log.
missing_count=0
missing_names=''
while IFS= read -r desired_pr; do
  [ -n "$desired_pr" ] || continue
  if ! jq -e --argjson pr "$desired_pr" '
    any(.[]; .pr == $pr and .retired == false)
  ' "$reviews" >/dev/null; then
    missing_count=$((missing_count + 1))
    missing_names="$missing_names mr$desired_pr"
    printf '::notice::Open labelled PR #%s has no live review environment (deploys are event-driven)\n' "$desired_pr"
  fi
done < "$desired"

# Evidence summary (plan 089): counts and review names only — raw Dokploy
# object ids are secrets and never logged.
printf '::notice::converge summary: kept=%s retired=%s missing=%s skipped-retired=%s kept-names:%s retired-names:%s missing-names:%s\n' \
  "$kept_count" "$retired_count" "$missing_count" "$skipped_retired_count" \
  "${kept_names:- none}" "${retired_names:- none}" "${missing_names:- none}"

if [ "$had_error" = true ]; then
  exit 1
fi
