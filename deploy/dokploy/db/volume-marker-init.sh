#!/bin/sh
set -eu

case "$PGDATA" in
  /*/) PGDATA=${PGDATA%/} ;;
  /*) ;;
  *) echo "PGDATA must be an absolute non-root path" >&2; exit 64 ;;
esac
[ "$PGDATA" != / ] || { echo "PGDATA must be an absolute non-root path" >&2; exit 64; }

marker="$(dirname "$PGDATA")/.buildhound-volume"
initializing="$marker.initializing"
expected="instance=$BUILDHOUND_DB_INSTANCE major=$BUILDHOUND_DB_MAJOR"

[ ! -e "$marker" ] || { echo "database volume identity already exists during initialization" >&2; exit 76; }
[ -f "$initializing" ] || { echo "database initialization marker is missing" >&2; exit 76; }
[ "$(cat "$initializing")" = "$expected" ] || { echo "database initialization identity mismatch" >&2; exit 70; }
[ -f "$PGDATA/PG_VERSION" ] || { echo "initialized database volume has no PG_VERSION" >&2; exit 75; }
pg_major=$(cat "$PGDATA/PG_VERSION" 2>/dev/null) || { echo "unable to read PG_VERSION" >&2; exit 74; }
[ "$pg_major" = "$BUILDHOUND_DB_MAJOR" ] || { echo "initialized database major mismatch" >&2; exit 75; }

mv "$initializing" "$marker"
