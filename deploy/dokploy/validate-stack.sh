#!/bin/sh
set -eu
file=${1:-deploy/dokploy/stack.yaml}
: "${BUILDHOUND_APP_ROLE:?must be staging or prod}"
case "$BUILDHOUND_APP_ROLE" in
  staging|prod) ;;
  *) printf 'BUILDHOUND_APP_ROLE must be staging or prod\n' >&2; exit 1 ;;
esac
stack_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
rendered=$(mktemp)
site_role_source=$(mktemp)
trap 'rm -f "$rendered" "$site_role_source"' EXIT

# Traefik object names are swarm-global: every router/middleware/service name
# must stay unique across ALL stack files that can coexist on the swarm, with
# the parameterized site stack expanded for both roles, or the first
# coexistence of two stacks silently 404s both routers (first prod anchor,
# 2026-07-17).
traefik_names() {
  grep -oE 'traefik\.(http|tcp|udp)\.(routers|middlewares|services)\.[^.=]+' "$1" \
    | sed 's/.*\.//' | sort -u
}
all_names=$(
  traefik_names "$stack_dir/stack.yaml"
  traefik_names "$stack_dir/staging-stack.yaml"
  traefik_names "$stack_dir/review-stack.yaml"
  for role in staging prod; do
    sed "s/\${BUILDHOUND_APP_ROLE}/$role/g" "$stack_dir/site-stack.yaml" > "$site_role_source"
    traefik_names "$site_role_source"
  done
)
test "$(printf '%s\n' "$all_names" | wc -l)" -eq \
  "$(printf '%s\n' "$all_names" | sort -u | wc -l)"

docker stack config -c "$file" > "$rendered"
grep -Eq 'image: .+@sha256:[0-9a-f]{64}' "$rendered"
if grep -Eq '(^|[[:space:]])(build:|container_name:|ports:|privileged:|/var/run/docker.sock)' "$rendered"; then exit 1; fi
grep -q 'failure_action: pause' "$rendered"
grep -q 'order: stop-first' "$rendered"
grep -q 'read_only: true' "$rendered"
# Short-form service-level tmpfs is preserved by `docker stack config` but
# dropped from the converted Swarm service mounts; only the long-form volumes
# mount is allowed (README "tmpfs" note).
if grep -q '^    tmpfs:' "$rendered"; then exit 1; fi
if grep -Eiq '(password|token|secret): [^$<{]' "$rendered"; then exit 1; fi

case "$file" in
  *site-stack.yaml)
    : "${BUILDHOUND_SITE_HOST:?required to validate the site stack}"
    : "${DOKPLOY_INGRESS_NETWORK:?required to validate the site stack}"
    test "$(grep -c 'type: tmpfs' "$rendered")" -eq 1
    test "$(grep -c 'target: /tmp' "$rendered")" -eq 1
    test "$(grep -c "node.labels.role == $BUILDHOUND_APP_ROLE" "$rendered")" -eq 1
    grep -qF "traefik.http.routers.buildhound-$BUILDHOUND_APP_ROLE-site.rule: Host(\`$BUILDHOUND_SITE_HOST\`)" "$rendered"
    grep -qF "traefik.http.routers.buildhound-$BUILDHOUND_APP_ROLE-site.entrypoints: websecure" "$rendered"
    grep -qF "traefik.http.routers.buildhound-$BUILDHOUND_APP_ROLE-site.tls.certresolver: letsencrypt-dns-hetzner" "$rendered"
    grep -qF "traefik.http.services.buildhound-$BUILDHOUND_APP_ROLE-site.loadbalancer.server.port: \"8080\"" "$rendered"
    grep -qF "traefik.swarm.network: $DOKPLOY_INGRESS_NETWORK" "$rendered"
    grep -q 'no-new-privileges:true' "$rendered"
    # Hardening parity with the standalone contract: site/compose.yml stays
    # the pinned minimal Docker Compose contract (plan 096); the shipped
    # stack derivative must not silently drift from it (plan 097).
    compose_file=$stack_dir/../../site/compose.yml
    compose_repo=$(sed -n 's/^ *image: \(ghcr\.io[^@]*\)@sha256:.*/\1/p' "$compose_file")
    test -n "$compose_repo"
    grep -qF "image: $compose_repo@sha256:" "$rendered"
    compose_user=$(sed -n 's/^ *user: //p' "$compose_file" | tr -d '"')
    site_user=$(sed -n 's/^ *user: //p' "$rendered" | tr -d '"')
    test -n "$compose_user"
    test "$compose_user" = "$site_user"
    grep -A1 'cap_drop:' "$compose_file" | grep -q 'ALL'
    grep -A1 'cap_drop:' "$rendered" | grep -q 'ALL'
    grep -q 'read_only: true' "$compose_file"
    grep -q '/tmp:rw,' "$compose_file"
    test "$(grep -oE '^ +BUILDHOUND_SITE_[A-Z_]+:' "$compose_file" | tr -d ' ' | sort)" = \
      "$(grep -oE '^ +BUILDHOUND_SITE_[A-Z_]+:' "$rendered" | tr -d ' ' | sort)"
    ;;
  *)
    grep -q 'internal: true' "$rendered"
    test "$(grep -c 'type: tmpfs' "$rendered")" -eq 2
    test "$(grep -c 'target: /tmp' "$rendered")" -eq 2
    test "$(grep -c 'node.labels.role == db' "$rendered")" -eq 2
    test "$(grep -c "node.id == $BUILDHOUND_DB_NODE_ID" "$rendered")" -eq 2
    test "$(grep -c "node.labels.role == $BUILDHOUND_APP_ROLE" "$rendered")" -eq 1
    if grep -q 'node.labels.buildhound.traefik' "$rendered"; then exit 1; fi
    awk -v app_role="$BUILDHOUND_APP_ROLE" '
      /^  [[:alnum:]_-]+:$/ { in_service = ($1 == "server:") }
      in_service && $1 == "-" && $2 == "node.labels.role" && $3 == "==" && $4 == app_role {
        role_found = 1
      }
      END { exit(role_found ? 0 : 1) }
    ' "$rendered"
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
    # Parser-truth check on secret file modes: 256 == 0o400. Source-text tests
    # cannot see what the deploying docker actually resolved (a bare 0400 renders
    # 256 on one docker generation and 400 on another — first prod anchor,
    # 2026-07-17); the rendered output is the ground truth.
    if grep -q 'mode:' "$rendered"; then
      test "$(grep -c 'mode: 256' "$rendered")" -eq "$(grep -c 'mode:' "$rendered")"
    fi
    ;;
esac
printf 'stack policy validated\n'
