#!/bin/sh
set -eu

: "${TTL_HOURS:?BUILDHOUND_REVIEW_TTL_HOURS must be set}"
: "${GITHUB_REPOSITORY:?}" "${ENVIRONMENT_ID:?}" "${DNS_SUFFIX:?BUILDHOUND_REVIEW_DNS_SUFFIX must be set}"
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

python3 deploy/dokploy/dokploy.py list-reviews \
  --base-repo "$GITHUB_REPOSITORY" \
  --environment-id "$ENVIRONMENT_ID" > "$reviews"
jq -e 'type == "array"' "$reviews" >/dev/null
jq -c '.[]' "$reviews" > "$entries"

now_epoch=$(date -u +%s)
ttl_seconds=$((TTL_HOURS * 3600))
had_error=false

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
  if ! created=$(printf '%s\n' "$review" | jq -er '.createdAt | select(type == "string" and length > 0)'); then
    warn_review "$pr" "missing createdAt; preserving review"
    had_error=true
    continue
  fi
  if ! created_epoch=$(date -u -d "$created" +%s 2>/dev/null); then
    warn_review "$pr" "invalid createdAt; preserving review"
    had_error=true
    continue
  fi
  if [ "$created_epoch" -gt $((now_epoch + 300)) ]; then
    warn_review "$pr" "createdAt is in the future; preserving review"
    had_error=true
    continue
  fi
  if ! compose_id=$(printf '%s\n' "$review" | jq -er '.composeId | select(type == "string" and length > 0)'); then
    warn_review "$pr" "missing compose ownership metadata; preserving review"
    had_error=true
    continue
  fi
  if ! sha=$(printf '%s\n' "$review" | jq -er '.sha | select(type == "string" and test("^[0-9a-f]{40}$"))'); then
    warn_review "$pr" "invalid SHA ownership metadata; preserving review"
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

  delete=false
  ttl_delete=false
  if [ "$state" != open ]; then
    delete=true
  else
    if ! labels=$(gh api "repos/${GITHUB_REPOSITORY}/issues/$pr/labels" --jq '.[].name' 2>/dev/null); then
      warn_review "$pr" "GitHub label lookup failed; preserving review"
      had_error=true
      continue
    fi
    if ! printf '%s\n' "$labels" | grep -Fx deploy-review >/dev/null; then
      delete=true
    fi
  fi

  age=$((now_epoch - created_epoch))
  if [ "$age" -gt "$ttl_seconds" ]; then
    delete=true
    ttl_delete=true
  fi
  if [ "$delete" != true ]; then
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
  if [ "$ttl_delete" = false ] && [ "$state" = open ]; then
    if ! labels=$(gh api "repos/${GITHUB_REPOSITORY}/issues/$pr/labels" --jq '.[].name' 2>/dev/null); then
      warn_review "$pr" "final GitHub label check failed; preserving review"
      had_error=true
      continue
    fi
    if printf '%s\n' "$labels" | grep -Fx deploy-review >/dev/null; then
      continue
    fi
  fi

  # Revoke public execution but retain ownership until package garbage collection succeeds.
  if ! python3 deploy/dokploy/dokploy.py revoke-review \
    --base-repo "$GITHUB_REPOSITORY" \
    --pr "$pr" \
    --environment-id "$ENVIRONMENT_ID" \
    --dns-suffix "$DNS_SUFFIX" \
    --expected-compose-id "$compose_id" \
    --expected-sha "$sha"; then
    warn_review "$pr" "Dokploy revocation failed; later reviews will still be reconciled"
    had_error=true
  elif ! REVIEW_PR="$pr" deploy/dokploy/delete-review-images.sh; then
    warn_review "$pr" "Stack revoked but exact-owned GHCR cleanup failed"
    had_error=true
  elif ! python3 deploy/dokploy/dokploy.py delete-review \
    --base-repo "$GITHUB_REPOSITORY" \
    --pr "$pr" \
    --environment-id "$ENVIRONMENT_ID" \
    --dns-suffix "$DNS_SUFFIX" \
    --expected-compose-id "$compose_id" \
    --expected-sha "$sha"; then
    warn_review "$pr" "GHCR cleanup succeeded but Dokploy record deletion failed"
    had_error=true
  fi
done < "$entries"

if [ "$had_error" = true ]; then
  exit 1
fi
