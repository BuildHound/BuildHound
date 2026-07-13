#!/bin/sh
set -eu

marker="$(dirname "$PGDATA")/.buildhound-volume"
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

if [ -f "$marker" ]; then
  [ "$(cat "$marker")" = "$expected" ] || { echo "database volume identity mismatch" >&2; exit 70; }
  if [ -z "$pgdata_entry" ] && [ "$BUILDHOUND_DB_ALLOW_INIT" != true ]; then
    echo "marked volume with empty PGDATA requires explicit re-initialization" >&2
    exit 73
  fi
elif [ -z "$pgdata_entry" ]; then
  [ "$BUILDHOUND_DB_ALLOW_INIT" = true ] || { echo "empty database volume requires explicit initialization" >&2; exit 71; }
  printf '%s\n' "$expected" > "$marker"
else
  echo "unmarked non-empty database volume refused" >&2
  exit 72
fi

exec docker-entrypoint.sh postgres
