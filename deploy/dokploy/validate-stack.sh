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
test "$(grep -c 'node.labels.role == db' "$rendered")" -eq 2
test "$(grep -c "node.id == $BUILDHOUND_DB_NODE_ID" "$rendered")" -eq 2
for service in backup db; do
  awk -v service="$service" -v node_id="$BUILDHOUND_DB_NODE_ID" '
    /^  [[:alnum:]_-]+:$/ { in_service = ($1 == service ":") }
    in_service && $1 == "-" && $2 == "node.labels.role" && $3 == "==" && $4 == "db" {
      role_found = 1
    }
    in_service && $1 == "-" && $2 == "node.id" && $3 == "==" && $4 == node_id {
      id_found = 1
    }
    END { exit(role_found && id_found ? 0 : 1) }
  ' "$rendered"
done
if grep -Eiq '(password|token|secret): [^$<{]' "$rendered"; then exit 1; fi
printf 'stack policy validated\n'
