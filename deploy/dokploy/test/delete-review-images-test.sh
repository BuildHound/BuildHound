#!/usr/bin/env bash
set -euo pipefail

root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
bin="$root/bin"
log="$root/delete.log"
mkdir -p "$bin"

current=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb

cat > "$bin/gh" <<'EOF'
#!/usr/bin/env sh
set -eu
case "$*" in
  "api repos/BuildHound/BuildHound --jq .owner.type") printf 'Organization\n' ;;
  "api --method DELETE "*) printf '%s\n' "$*" >> "$DELETE_LOG" ;;
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
    if [ "${PACKAGE_TEST_MODE:-normal}" = unowned ]; then
      printf '[{"number":7,"head":{"sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","repo":{"full_name":"BuildHound/BuildHound"}},"base":{"repo":{"full_name":"BuildHound/BuildHound"}}}]\n'
    else
      printf '[{"number":42,"head":{"sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","repo":{"full_name":"BuildHound/BuildHound"}},"base":{"repo":{"full_name":"BuildHound/BuildHound"}}}]\n'
    fi
    ;;
  *) printf 'unexpected gh call: %s\n' "$*" >&2; exit 90 ;;
esac
EOF
chmod +x "$bin/gh"

run_cleanup() {
  PATH="$bin:$PATH" \
    DELETE_LOG="$log" \
    GITHUB_REPOSITORY=BuildHound/BuildHound \
    REVIEW_PR=42 \
    KEEP_SHA="$current" \
    PACKAGE_TEST_MODE="${1:-normal}" \
    deploy/dokploy/delete-review-images.sh
}

: > "$log"
run_cleanup normal
grep -F 'buildhound-server/versions/11' "$log" >/dev/null
grep -F 'buildhound-site/versions/21' "$log" >/dev/null
if grep -Eq 'versions/(12|22)' "$log"; then exit 1; fi

: > "$log"
if run_cleanup shared >/dev/null 2>&1; then
  echo 'shared package version was not rejected' >&2
  exit 1
fi
test ! -s "$log"

: > "$log"
if run_cleanup unowned >/dev/null 2>&1; then
  echo 'unowned package version was not rejected' >&2
  exit 1
fi
test ! -s "$log"

printf 'review image cleanup validated\n'
