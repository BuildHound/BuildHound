#!/bin/sh
set -eu

root=$(mktemp -d)
bin=$(mktemp -d)
trap 'rm -rf "$root" "$bin"' EXIT

cat > "$bin/docker-entrypoint.sh" <<'EOF'
#!/bin/sh
mkdir -p "$PGDATA"
if [ ! -f "$PGDATA/PG_VERSION" ]; then
  printf '%s\n' "$BUILDHOUND_DB_MAJOR" > "$PGDATA/PG_VERSION"
  if [ "${BUILDHOUND_TEST_FAIL_INIT:-false}" = true ]; then
    exit 90
  fi
  sh "$BUILDHOUND_TEST_INIT_HOOK"
fi
EOF
chmod +x "$bin/docker-entrypoint.sh"

run_guard() {
  PGDATA="$root/pgdata" \
    BUILDHOUND_DB_INSTANCE="${1:-test}" \
    BUILDHOUND_DB_MAJOR="${2:-16}" \
    BUILDHOUND_DB_ALLOW_INIT="${3:-false}" \
    BUILDHOUND_TEST_FAIL_INIT="${4:-false}" \
    BUILDHOUND_TEST_INIT_HOOK="$PWD/deploy/dokploy/db/volume-marker-init.sh" \
    PATH="$bin:$PATH" \
    sh deploy/dokploy/volume-guard.sh
}

expect_exit() {
  expected=$1
  shift
  set +e
  run_guard "$@" >/dev/null 2>&1
  actual=$?
  set -e
  test "$actual" -eq "$expected"
}

expect_exit 71 test 16 false
expect_exit 90 test 16 true true
test ! -e "$root/.buildhound-volume"
test "$(cat "$root/.buildhound-volume.initializing")" = 'instance=test major=16'
test -f "$root/pgdata/PG_VERSION"
expect_exit 76 test 16 false
run_guard test 16 true
test ! -e "$root/.buildhound-volume.initializing"
test "$(cat "$root/.buildhound-volume")" = 'instance=test major=16'
test -f "$root/pgdata/PG_VERSION"
test "$(cat "$root/pgdata/PG_VERSION")" = 16
run_guard test 16 false

expect_exit 70 other 16 false
expect_exit 70 test 17 false
printf '15\n' > "$root/pgdata/PG_VERSION"
expect_exit 75 test 16 false
touch "$root/pgdata/postgresql.conf"
rm "$root/pgdata/PG_VERSION"
expect_exit 75 test 16 false
rm "$root/pgdata/postgresql.conf"
printf '16\n' > "$root/pgdata/PG_VERSION"

rm -rf "$root/pgdata"
mkdir -p "$root/pgdata"
expect_exit 73 test 16 false
run_guard test 16 true
test -f "$root/pgdata/PG_VERSION"

rm "$root/.buildhound-volume"
expect_exit 72 test 16 false

rm -rf "$root/pgdata"
touch "$root/pgdata"
expect_exit 74 test 16 false

printf 'volume guard validated\n'
