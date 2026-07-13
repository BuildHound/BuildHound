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
if [ -e "$PGDATA" ] && [ ! -d "$PGDATA" ]; then
  echo "PGDATA is not a directory" >&2
  exit 74
elif [ -d "$PGDATA" ]; then
  pgdata_entry=$(find "$PGDATA" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null) || {
    echo "unable to inspect PGDATA" >&2
    exit 74
  }
else
  pgdata_entry=
fi

for state_file in "$marker" "$initializing"; do
  if [ -e "$state_file" ] && [ ! -f "$state_file" ]; then
    echo "database volume state is not a regular file" >&2
    exit 74
  fi
done
[ ! -f "$marker" ] || [ ! -f "$initializing" ] || {
  echo "database volume identity state is ambiguous" >&2
  exit 76
}

if [ -f "$marker" ]; then
  [ "$(cat "$marker")" = "$expected" ] || { echo "database volume identity mismatch" >&2; exit 70; }
  if [ -z "$pgdata_entry" ] && [ "$BUILDHOUND_DB_ALLOW_INIT" != true ]; then
    echo "marked volume with empty PGDATA requires explicit re-initialization" >&2
    exit 73
  elif [ -z "$pgdata_entry" ]; then
    mv "$marker" "$initializing"
  elif [ -n "$pgdata_entry" ]; then
    [ -f "$PGDATA/PG_VERSION" ] || { echo "marked database volume has no PG_VERSION" >&2; exit 75; }
    pg_major=$(cat "$PGDATA/PG_VERSION" 2>/dev/null) || { echo "unable to read PG_VERSION" >&2; exit 74; }
    [ "$pg_major" = "$BUILDHOUND_DB_MAJOR" ] || { echo "database data major mismatch" >&2; exit 75; }
  fi
elif [ -f "$initializing" ]; then
  [ "$(cat "$initializing")" = "$expected" ] || { echo "database initialization identity mismatch" >&2; exit 70; }
  [ "$BUILDHOUND_DB_ALLOW_INIT" = true ] || {
    echo "interrupted database initialization requires explicit re-initialization" >&2
    exit 76
  }
  if [ -n "$pgdata_entry" ]; then
    find "$PGDATA" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} \;
  fi
elif [ -z "$pgdata_entry" ]; then
  [ "$BUILDHOUND_DB_ALLOW_INIT" = true ] || { echo "empty database volume requires explicit initialization" >&2; exit 71; }
  pending="$initializing.tmp.$$"
  printf '%s\n' "$expected" > "$pending"
  mv "$pending" "$initializing"
else
  echo "unmarked non-empty database volume refused" >&2
  exit 72
fi

exec docker-entrypoint.sh postgres
