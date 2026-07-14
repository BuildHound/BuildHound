#!/bin/sh
set -eu

: "${TTL_HOURS:?BUILDHOUND_REVIEW_TTL_HOURS must be set}"
: "${GITHUB_REPOSITORY:?}" "${DEFAULT_BRANCH:?}" "${ENVIRONMENT_ID:?}" \
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
trap 'rm -f "$reviews" "$entries"' EXIT

bash deploy/dokploy/dokploy.sh list-reviews \
  --base-repo "$GITHUB_REPOSITORY" \
  --environment-id "$ENVIRONMENT_ID" > "$reviews"
jq -e 'type == "array"' "$reviews" >/dev/null
jq -c '.[]' "$reviews" > "$entries"

now_epoch=$(date -u +%s)
ttl_seconds=$((TTL_HOURS * 3600))
had_error=false
review_workflow_id=$(gh api \
  "repos/${GITHUB_REPOSITORY}/actions/workflows/review-environment.yml" --jq .id)
case "$review_workflow_id" in ''|0*|*[!0-9]*) echo "invalid review workflow ID" >&2; exit 1;; esac

warn_review() {
  printf '::warning::Review %s: %s\n' "$1" "$2"
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
  if ! data=$(gh api "repos/${GITHUB_REPOSITORY}/pulls/$pr" 2>/dev/null); then
    warn_review "$pr" "GitHub PR lookup failed; preserving review"
    had_error=true
    continue
  fi
  if ! state=$(printf '%s\n' "$data" | jq -er '.state | select(type == "string")'); then
    warn_review "$pr" "GitHub returned invalid PR state; preserving review"
    had_error=true
    continue
  fi

  retire=false
  ttl_retire=false
  attempt_retire=false
  if [ "$state" != open ]; then
    retire=true
  else
    if ! labels=$(gh api "repos/${GITHUB_REPOSITORY}/issues/$pr/labels" --jq '.[].name' 2>/dev/null); then
      warn_review "$pr" "GitHub label lookup failed; preserving review"
      had_error=true
      continue
    fi
    if ! printf '%s\n' "$labels" | grep -Fx deploy-review >/dev/null; then
      retire=true
    fi
  fi

  if [ "$retire" = false ] && [ -n "$attempt_id" ]; then
    case "$attempt_id" in
      *.*) run_id=${attempt_id%%.*}; run_attempt=${attempt_id#*.} ;;
      *) run_id=; run_attempt= ;;
    esac
    case "$run_id" in ''|0*|*[!0-9]*) run_id=;; esac
    case "$run_attempt" in ''|0*|*[!0-9]*|*.*) run_attempt=;; esac
    if [ -z "$run_id" ] || [ -z "$run_attempt" ]; then
      warn_review "$pr" "invalid attempt identity; preserving review"
      had_error=true
      continue
    fi
    if ! run=$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/$run_id/attempts/$run_attempt" 2>/dev/null); then
      warn_review "$pr" "review attempt lookup failed; preserving review"
      had_error=true
      continue
    fi
    if ! printf '%s\n' "$run" | jq -e --arg repository "$GITHUB_REPOSITORY" \
        --arg branch "$DEFAULT_BRANCH" --argjson workflow "$review_workflow_id" \
        --argjson pr "$pr" --arg sha "$sha" --argjson attempt "$run_attempt" '
          type == "object" and .workflow_id == $workflow and .run_attempt == $attempt and
          .event == "pull_request_target" and .repository.full_name == $repository and
          ((.pull_requests | type) == "array") and
          ([.pull_requests[] | select(
            .number == $pr and .head.sha == $sha and .base.ref == $branch
          )] | length == 1) and
          (.status | type) == "string"
        ' >/dev/null; then
      warn_review "$pr" "review attempt evidence was invalid; preserving review"
      had_error=true
      continue
    fi
    run_status=$(printf '%s\n' "$run" | jq -er .status)
    case "$run_status" in
      queued|in_progress|pending|requested|waiting) : ;;
      completed)
        if [ "$(printf '%s\n' "$run" | jq -r .conclusion)" = success ]; then
          :
        else
          retire=true
          attempt_retire=true
        fi
        ;;
      *)
        warn_review "$pr" "review attempt status was invalid; preserving review"
        had_error=true
        continue
        ;;
    esac
  fi

  age=$((now_epoch - activated_epoch))
  if [ "$age" -gt "$ttl_seconds" ]; then
    retire=true
    ttl_retire=true
  fi
  if [ "$retire" != true ]; then
    continue
  fi

  if ! data=$(gh api "repos/${GITHUB_REPOSITORY}/pulls/$pr" 2>/dev/null); then
    warn_review "$pr" "final GitHub PR check failed; preserving review"
    had_error=true
    continue
  fi
  if ! state=$(printf '%s\n' "$data" | jq -er '.state | select(type == "string")'); then
    warn_review "$pr" "final GitHub PR state was invalid; preserving review"
    had_error=true
    continue
  fi
  if [ "$ttl_retire" = false ] && [ "$attempt_retire" = false ] && [ "$state" = open ]; then
    if ! labels=$(gh api "repos/${GITHUB_REPOSITORY}/issues/$pr/labels" --jq '.[].name' 2>/dev/null); then
      warn_review "$pr" "final GitHub label check failed; preserving review"
      had_error=true
      continue
    fi
    if printf '%s\n' "$labels" | grep -Fx deploy-review >/dev/null; then
      continue
    fi
  fi

  # Materialize the credential-free anchor before package garbage collection.
  if [ -n "$attempt_id" ]; then
    attempt_args="--expected-attempt-id $attempt_id"
  else
    attempt_args=
  fi
  # attempt_id is validated above and contains no shell metacharacters. The
  # branch avoids eval and preserves compatibility with legacy records.
  if [ -n "$attempt_args" ]; then
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
  fi
done < "$entries"

if [ "$had_error" = true ]; then
  exit 1
fi
