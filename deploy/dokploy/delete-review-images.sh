#!/bin/sh
set -eu

: "${GITHUB_REPOSITORY:?}" "${REVIEW_PR:?}"
case "$REVIEW_PR" in ''|0*|*[!0-9]*) echo "invalid review PR" >&2; exit 64;; esac
if [ -n "${KEEP_SHA:-}" ] && ! printf '%s\n' "$KEEP_SHA" | grep -Eq '^[0-9a-f]{40}$'; then
  echo "invalid keep SHA" >&2
  exit 64
fi

owner=${GITHUB_REPOSITORY%%/*}
repo_name=${GITHUB_REPOSITORY#*/}
if [ -z "$owner" ] || [ -z "$repo_name" ] || [ "$owner/$repo_name" != "$GITHUB_REPOSITORY" ]; then
  echo "invalid repository identity" >&2
  exit 64
fi

owner_type=$(gh api "repos/$GITHUB_REPOSITORY" --jq .owner.type)
case "$owner_type" in
  Organization) package_scope="orgs/$owner" ;;
  User) package_scope="users/$owner" ;;
  *) echo "unsupported package owner type" >&2; exit 65 ;;
esac
had_error=false
for package in buildhound-server buildhound-site; do
  if versions=$(gh api --paginate --slurp "$package_scope/packages/container/$package/versions?per_page=100"); then
    :
  else
    echo "unable to list $package versions; preserving images" >&2
    had_error=true
    continue
  fi
  candidates=$(mktemp)
  printf '%s\n' "$versions" | jq -c --arg pattern "^pr-${REVIEW_PR}-[0-9a-f]{40}\\z" '
    .[][] |
    select(.metadata.container.tags | type == "array") |
    . as $version |
    [$version.metadata.container.tags[] | select(test($pattern))] as $owned |
    select($owned | length > 0) |
    {id: $version.id, tags: $version.metadata.container.tags, owned: $owned}
  ' > "$candidates"

  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    if ! id=$(printf '%s\n' "$candidate" | jq -er '.id | select(type == "number" and floor == . and . > 0)'); then
      echo "invalid $package version ID; preserving image" >&2
      had_error=true
      continue
    fi
    if ! tag=$(printf '%s\n' "$candidate" | jq -er '.owned | select(length == 1) | .[0]'); then
      echo "ambiguous $package review tag; preserving image" >&2
      had_error=true
      continue
    fi
    if ! printf '%s\n' "$candidate" | jq -e --arg tag "$tag" '.tags == [$tag]' >/dev/null; then
      echo "shared $package version has additional tags; preserving image" >&2
      had_error=true
      continue
    fi
    sha=${tag##*-}
    if [ -n "${KEEP_SHA:-}" ] && [ "$sha" = "$KEEP_SHA" ]; then
      continue
    fi
    if pulls=$(gh api "repos/$GITHUB_REPOSITORY/commits/$sha/pulls"); then
      :
    else
      echo "unable to verify $package PR provenance; preserving image" >&2
      had_error=true
      continue
    fi
    if ! printf '%s\n' "$pulls" | jq -e --arg repo "$GITHUB_REPOSITORY" --argjson pr "$REVIEW_PR" '
      .[] |
      select(.number == $pr and .head.repo.full_name == $repo and .base.repo.full_name == $repo)
    ' >/dev/null; then
      echo "unverified $package repository/PR/SHA provenance; preserving image" >&2
      had_error=true
      continue
    fi
    if ! gh api --method DELETE "$package_scope/packages/container/$package/versions/$id"; then
      echo "failed to delete verified $package review image" >&2
      had_error=true
    fi
  done < "$candidates"
  rm -f "$candidates"
done

if [ "$had_error" = true ]; then
  exit 1
fi
