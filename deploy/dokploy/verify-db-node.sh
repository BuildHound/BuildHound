#!/bin/sh
set -eu
: "${BUILDHOUND_DB_NODE_ID:?must be the Swarm ID of the role=db node}"

nodes=$(docker node ls \
  --filter 'node.label=role=db' \
  --format '{{.ID}}|{{.Status}}|{{.Availability}}')
labelled=$(printf '%s\n' "$nodes" | awk 'NF { count++ } END { print count + 0 }')
eligible=$(printf '%s\n' "$nodes" | awk -F '|' '
  $2 == "Ready" && $3 == "Active" { count++ }
  END { print count + 0 }
')

if [ "$labelled" -ne 1 ] || [ "$eligible" -ne 1 ]; then
  printf 'expected exactly one Ready/Active Swarm node labelled role=db; found %s eligible among %s labelled nodes\n' \
    "$eligible" "$labelled" >&2
  exit 1
fi

node_id=$(printf '%s\n' "$nodes" | cut -d '|' -f 1)
if [ "$node_id" != "$BUILDHOUND_DB_NODE_ID" ]; then
  printf 'role=db resolves to node %s, not configured BUILDHOUND_DB_NODE_ID %s\n' \
    "$node_id" "$BUILDHOUND_DB_NODE_ID" >&2
  exit 1
fi
printf 'database placement validated: role=db on %s\n' "$node_id"
