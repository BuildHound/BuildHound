#!/bin/sh
set -eu

marker="$(dirname "$PGDATA")/.buildhound-volume"
expected="instance=$BUILDHOUND_DB_INSTANCE major=$BUILDHOUND_DB_MAJOR"

if [ -f "$marker" ]; then
  [ "$(cat "$marker")" = "$expected" ] || { echo "database volume identity mismatch" >&2; exit 70; }
elif [ -z "$(find "$PGDATA" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]; then
  [ "$BUILDHOUND_DB_ALLOW_INIT" = true ] || { echo "empty database volume requires explicit initialization" >&2; exit 71; }
  printf '%s\n' "$expected" > "$marker"
else
  echo "unmarked non-empty database volume refused" >&2
  exit 72
fi

exec docker-entrypoint.sh postgres
