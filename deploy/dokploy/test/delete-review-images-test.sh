#!/usr/bin/env bash
set -euo pipefail

root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
bin="$root/bin"
delete_log="$root/delete.log"
gh_log="$root/gh.log"
mkdir -p "$bin"

current=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb

cat > "$bin/gh" <<'EOF'
#!/usr/bin/env sh
set -eu
printf '%s\n' "$*" >> "$GH_LOG"
case "$*" in
  "api repos/BuildHound/BuildHound --jq .owner.type") printf 'Organization\n' ;;
  "api --method DELETE "*) printf '%s\n' "$*" >> "$DELETE_LOG" ;;
  *"packages/container/buildhound-server/versions/11")
    if [ "${PACKAGE_TEST_MODE:-normal}" = revalidation_drift ]; then
      printf '{"id":11,"metadata":{"container":{"tags":["pr-42-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","release"]}}}\n'
    else
      printf '{"id":11,"metadata":{"container":{"tags":["pr-42-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]}}}\n'
    fi
    ;;
  *"packages/container/buildhound-site/versions/21")
    if [ "${PACKAGE_TEST_MODE:-normal}" = revalidation_drift ]; then
      printf '{"id":21,"metadata":{"container":{"tags":["pr-42-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","release"]}}}\n'
    else
      printf '{"id":21,"metadata":{"container":{"tags":["pr-42-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]}}}\n'
    fi
    ;;
  *"packages/container/buildhound-server/versions"*)
    if [ "${PACKAGE_TEST_MODE:-normal}" = shared ]; then
      printf '[[{"id":13,"metadata":{"container":{"tags":["pr-42-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","latest"]}}}]]\n'
    else
      printf '[[{"id":11,"metadata":{"container":{"tags":["pr-42-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]}}},{"id":12,"metadata":{"container":{"tags":["pr-42-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]}}}]]\n'
    fi
    ;;
  *"packages/container/buildhound-site/versions"*)
    if [ "${PACKAGE_TEST_MODE:-normal}" = shared ]; then
      printf '[[]]\n'
    else
      printf '[[{"id":21,"metadata":{"container":{"tags":["pr-42-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]}}},{"id":22,"metadata":{"container":{"tags":["pr-42-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]}}}]]\n'
    fi
    ;;
  "api repos/BuildHound/BuildHound/commits/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/pulls")
    if [ "${PACKAGE_TEST_MODE:-normal}" = normal ] ||
       [ "${PACKAGE_TEST_MODE:-normal}" = revalidation_drift ]; then
      printf '[{"number":42,"head":{"repo":{"full_name":"BuildHound/BuildHound"}},"base":{"repo":{"full_name":"BuildHound/BuildHound"}}}]\n'
    elif [ "${PACKAGE_TEST_MODE:-normal}" = unowned ]; then
      printf '[{"number":7,"head":{"repo":{"full_name":"BuildHound/BuildHound"}},"base":{"repo":{"full_name":"BuildHound/BuildHound"}}}]\n'
    else
      printf '[]\n'
    fi
    ;;
  "api repos/BuildHound/BuildHound/pulls/42")
    # commits/{sha}/pulls omits closed/rebase-merged PRs off the default
    # branch; the PR record is the fallback provenance for the exact head.
    case "${PACKAGE_TEST_MODE:-normal}" in
      closedpr)
        printf '{"head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"BuildHound/BuildHound"}},"base":{"repo":{"full_name":"BuildHound/BuildHound"}}}\n'
        ;;
      closedpr_fork)
        printf '{"head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"someone/fork"}},"base":{"repo":{"full_name":"BuildHound/BuildHound"}}}\n'
        ;;
      *)
        exit 91
        ;;
    esac
    ;;
  "api graphql "*)
    case "${PACKAGE_TEST_MODE:-normal}" in
      forcepush)
        printf '[{"data":{"repository":{"nameWithOwner":"BuildHound/BuildHound","pullRequest":{"number":42,"baseRepository":{"nameWithOwner":"BuildHound/BuildHound"},"headRepository":{"nameWithOwner":"BuildHound/BuildHound"},"timelineItems":{"pageCount":1,"nodes":[{"id":"event-1","beforeCommit":{"oid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}],"pageInfo":{"hasNextPage":false,"endCursor":"cursor-1"}}}}}}]\n'
        ;;
      forcepush_page2)
        printf '[{"data":{"repository":{"nameWithOwner":"BuildHound/BuildHound","pullRequest":{"number":42,"baseRepository":{"nameWithOwner":"BuildHound/BuildHound"},"headRepository":{"nameWithOwner":"BuildHound/BuildHound"},"timelineItems":{"pageCount":1,"nodes":[{"id":"event-1","beforeCommit":{"oid":"cccccccccccccccccccccccccccccccccccccccc"}}],"pageInfo":{"hasNextPage":true,"endCursor":"cursor-1"}}}}}},{"data":{"repository":{"nameWithOwner":"BuildHound/BuildHound","pullRequest":{"number":42,"baseRepository":{"nameWithOwner":"BuildHound/BuildHound"},"headRepository":{"nameWithOwner":"BuildHound/BuildHound"},"timelineItems":{"pageCount":1,"nodes":[{"id":"event-2","beforeCommit":{"oid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}],"pageInfo":{"hasNextPage":false,"endCursor":"cursor-2"}}}}}}]\n'
        ;;
      graphfail) exit 91 ;;
      malformed)
        printf '[{"data":{"repository":{"nameWithOwner":"BuildHound/BuildHound","pullRequest":{"number":42,"baseRepository":{"nameWithOwner":"BuildHound/BuildHound"},"headRepository":{"nameWithOwner":"BuildHound/BuildHound"},"timelineItems":{"pageCount":2,"nodes":[{"id":"event-1","beforeCommit":{"oid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}]\n'
        ;;
      wrongidentity)
        printf '[{"data":{"repository":{"nameWithOwner":"BuildHound/BuildHound","pullRequest":{"number":42,"baseRepository":{"nameWithOwner":"BuildHound/BuildHound"},"headRepository":{"nameWithOwner":"someone/fork"},"timelineItems":{"pageCount":1,"nodes":[{"id":"event-1","beforeCommit":{"oid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}]\n'
        ;;
      *)
        printf '[{"data":{"repository":{"nameWithOwner":"BuildHound/BuildHound","pullRequest":{"number":42,"baseRepository":{"nameWithOwner":"BuildHound/BuildHound"},"headRepository":{"nameWithOwner":"BuildHound/BuildHound"},"timelineItems":{"pageCount":1,"nodes":[{"id":"event-1","beforeCommit":{"oid":"cccccccccccccccccccccccccccccccccccccccc"}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}]\n'
        ;;
    esac
    ;;
  *) printf 'unexpected gh call: %s\n' "$*" >&2; exit 90 ;;
esac
EOF
chmod +x "$bin/gh"

run_cleanup() {
  PATH="$bin:$PATH" \
    DELETE_LOG="$delete_log" \
    GH_LOG="$gh_log" \
    GITHUB_REPOSITORY=BuildHound/BuildHound \
    REVIEW_PR=42 \
    KEEP_SHA="$current" \
    PACKAGE_TEST_MODE="${1:-normal}" \
    deploy/dokploy/delete-review-images.sh
}

: > "$delete_log"
: > "$gh_log"
run_cleanup normal
grep -F 'buildhound-server/versions/11' "$delete_log" >/dev/null
grep -F 'buildhound-site/versions/21' "$delete_log" >/dev/null
if grep -Eq 'versions/(12|22)' "$delete_log"; then exit 1; fi
if grep -F 'api graphql ' "$gh_log" >/dev/null; then
  echo 'GraphQL fallback ran despite direct provenance' >&2
  exit 1
fi

# Closed / rebase-merged PR: commits/{sha}/pulls is empty, the PR record
# verifies the exact head, and no GraphQL fallback is needed.
: > "$delete_log"
: > "$gh_log"
run_cleanup closedpr
grep -F 'buildhound-server/versions/11' "$delete_log" >/dev/null
grep -F 'buildhound-site/versions/21' "$delete_log" >/dev/null
if grep -Eq 'versions/(12|22)' "$delete_log"; then exit 1; fi
if grep -F 'api graphql ' "$gh_log" >/dev/null; then
  echo 'GraphQL fallback ran despite PR-record provenance' >&2
  exit 1
fi

for mode in forcepush forcepush_page2; do
  : > "$delete_log"
  : > "$gh_log"
  run_cleanup "$mode"
  grep -F 'buildhound-server/versions/11' "$delete_log" >/dev/null
  grep -F 'buildhound-site/versions/21' "$delete_log" >/dev/null
  test "$(grep -Fc 'api graphql ' "$gh_log")" = 1
done

for mode in shared unowned graphfail malformed wrongidentity revalidation_drift closedpr_fork; do
  : > "$delete_log"
  : > "$gh_log"
  if run_cleanup "$mode" >/dev/null 2>&1; then
    echo "$mode package provenance was not rejected" >&2
    exit 1
  fi
  test ! -s "$delete_log"
done

printf 'review image cleanup validated\n'
