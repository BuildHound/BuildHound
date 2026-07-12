#!/bin/sh
set -eu
file=${1:-deploy/dokploy/stack.yaml}
rendered=$(mktemp)
trap 'rm -f "$rendered"' EXIT
docker stack config -c "$file" > "$rendered"
grep -Eq 'image: .+@sha256:[0-9a-f]{64}' "$rendered"
if grep -Eq '(^|[[:space:]])(build:|container_name:|ports:|privileged:|/var/run/docker.sock)' "$rendered"; then exit 1; fi
grep -q 'failure_action: pause' "$rendered"
grep -q 'order: stop-first' "$rendered"
grep -q 'internal: true' "$rendered"
grep -q 'read_only: true' "$rendered"
if grep -Eiq '(password|token|secret): [^$<{]' "$rendered"; then exit 1; fi
printf 'stack policy validated\n'
