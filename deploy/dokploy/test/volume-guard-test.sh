#!/bin/sh
set -eu
root=$(mktemp -d); bin=$(mktemp -d); trap 'rm -rf "$root" "$bin"' EXIT
printf '#!/bin/sh\nexit 0\n' > "$bin/docker-entrypoint.sh"; chmod +x "$bin/docker-entrypoint.sh"
PGDATA="$root/pgdata" BUILDHOUND_DB_INSTANCE=test BUILDHOUND_DB_MAJOR=16 BUILDHOUND_DB_ALLOW_INIT=true PATH="$bin:$PATH" sh deploy/dokploy/volume-guard.sh
test "$(cat "$root/.buildhound-volume")" = 'instance=test major=16'
PGDATA="$root/pgdata" BUILDHOUND_DB_INSTANCE=test BUILDHOUND_DB_MAJOR=16 BUILDHOUND_DB_ALLOW_INIT=false PATH="$bin:$PATH" sh deploy/dokploy/volume-guard.sh
if PGDATA="$root/pgdata" BUILDHOUND_DB_INSTANCE=other BUILDHOUND_DB_MAJOR=16 BUILDHOUND_DB_ALLOW_INIT=false PATH="$bin:$PATH" sh deploy/dokploy/volume-guard.sh 2>/dev/null; then exit 1; fi
printf 'volume guard validated\n'
