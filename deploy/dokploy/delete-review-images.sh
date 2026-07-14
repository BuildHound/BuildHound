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

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT HUP INT TERM
force_push_history=$tmpdir/force-push-history
force_push_history_state=unloaded
# GraphQL variables are intentionally literal inside this single-quoted query.
# shellcheck disable=SC2016
force_push_query='query($owner:String!,$name:String!,$pr:Int!,$endCursor:String){
  repository(owner:$owner,name:$name){
    nameWithOwner
    pullRequest(number:$pr){
      number
      baseRepository{nameWithOwner}
      headRepository{nameWithOwner}
      timelineItems(first:100,after:$endCursor,itemTypes:[HEAD_REF_FORCE_PUSHED_EVENT]){
        pageCount
        nodes{... on HeadRefForcePushedEvent{id beforeCommit{oid}}}
        pageInfo{hasNextPage endCursor}
      }
    }
  }
}'

load_force_push_history() {
  case "$force_push_history_state" in
    loaded) return 0 ;;
    failed) return 1 ;;
  esac

  if history=$(gh api graphql --paginate --slurp \
      -f owner="$owner" -f name="$repo_name" -F pr="$REVIEW_PR" \
      -f query="$force_push_query"); then
    :
  else
    force_push_history_state=failed
    return 1
  fi
  if ! printf '%s\n' "$history" | jq -er --arg repo "$GITHUB_REPOSITORY" \
      --argjson pr "$REVIEW_PR" '
    type == "array" and length > 0 and
    all(.[];
      (has("errors") | not) and
      (.data.repository | type) == "object" and
      .data.repository.nameWithOwner == $repo and
      (.data.repository.pullRequest | type) == "object" and
      .data.repository.pullRequest.number == $pr and
      .data.repository.pullRequest.baseRepository.nameWithOwner == $repo and
      .data.repository.pullRequest.headRepository.nameWithOwner == $repo and
      (.data.repository.pullRequest.timelineItems | type) == "object" and
      (.data.repository.pullRequest.timelineItems.nodes | type) == "array" and
      (.data.repository.pullRequest.timelineItems.pageCount | type) == "number" and
      .data.repository.pullRequest.timelineItems.pageCount >= 0 and
      .data.repository.pullRequest.timelineItems.pageCount ==
        (.data.repository.pullRequest.timelineItems.nodes | length) and
      all(.data.repository.pullRequest.timelineItems.nodes[];
        (type == "object") and
        (.id | type) == "string" and (.id | length) > 0 and
        ((.beforeCommit == null) or
         ((.beforeCommit | type) == "object" and
          (.beforeCommit.oid | type) == "string" and
          (.beforeCommit.oid | test("^[0-9a-f]{40}\\z"))))
      ) and
      (.data.repository.pullRequest.timelineItems.pageInfo | type) == "object" and
      (.data.repository.pullRequest.timelineItems.pageInfo.hasNextPage | type) == "boolean" and
      ((.data.repository.pullRequest.timelineItems.pageInfo.endCursor == null) or
       ((.data.repository.pullRequest.timelineItems.pageInfo.endCursor | type) == "string" and
        (.data.repository.pullRequest.timelineItems.pageInfo.endCursor | length) > 0))) and
    all(.[0:-1][];
      .data.repository.pullRequest.timelineItems.pageInfo.hasNextPage == true and
      (.data.repository.pullRequest.timelineItems.pageInfo.endCursor | type) == "string" and
      (.data.repository.pullRequest.timelineItems.pageInfo.endCursor | length) > 0) and
    .[-1].data.repository.pullRequest.timelineItems.pageInfo.hasNextPage == false and
    ([.[].data.repository.pullRequest.timelineItems.nodes[].id] | length) ==
      ([.[].data.repository.pullRequest.timelineItems.nodes[].id] | unique | length)
  ' >/dev/null; then
    force_push_history_state=failed
    return 1
  fi
  if ! printf '%s\n' "$history" | jq -r '
      .[].data.repository.pullRequest.timelineItems.nodes[] |
      .beforeCommit.oid? // empty
    ' > "$force_push_history"; then
    force_push_history_state=failed
    return 1
  fi
  force_push_history_state=loaded
}

force_push_history_contains() {
  load_force_push_history || return 1
  grep -Fx "$1" "$force_push_history" >/dev/null
}

had_error=false
for package in buildhound-server buildhound-site; do
  if versions=$(gh api --paginate --slurp "$package_scope/packages/container/$package/versions?per_page=100"); then
    :
  else
    echo "unable to list $package versions; preserving images" >&2
    had_error=true
    continue
  fi
  candidates=$tmpdir/$package-candidates
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
    verified=false
    if pulls=$(gh api "repos/$GITHUB_REPOSITORY/commits/$sha/pulls") &&
       printf '%s\n' "$pulls" | jq -e --arg repo "$GITHUB_REPOSITORY" --argjson pr "$REVIEW_PR" '
         .[] |
         select(.number == $pr and .head.repo.full_name == $repo and .base.repo.full_name == $repo)
       ' >/dev/null; then
      verified=true
    elif force_push_history_contains "$sha"; then
      verified=true
    fi
    if [ "$verified" != true ]; then
      echo "unverified $package repository/PR/SHA provenance; preserving image" >&2
      had_error=true
      continue
    fi
    if live=$(gh api "$package_scope/packages/container/$package/versions/$id") &&
       printf '%s\n' "$live" | jq -e --argjson id "$id" --arg tag "$tag" '
         type == "object" and .id == $id and
         (.metadata.container.tags | type) == "array" and
         .metadata.container.tags == [$tag]
       ' >/dev/null; then
      :
    else
      echo "verified $package review image changed before deletion; preserving image" >&2
      had_error=true
      continue
    fi
    if ! gh api --method DELETE "$package_scope/packages/container/$package/versions/$id"; then
      echo "failed to delete verified $package review image" >&2
      had_error=true
    fi
  done < "$candidates"
done

if [ "$had_error" = true ]; then
  exit 1
fi
