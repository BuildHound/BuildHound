#!/bin/sh
set -eu

root=$(mktemp -d)
bin=$(mktemp -d)
trap 'rm -rf "$root" "$bin"' EXIT

cat > "$bin/docker-entrypoint.sh" <<'EOF'
#!/bin/sh
mkdir -p "$PGDATA"
touch "$PGDATA/PG_VERSION"
EOF
chmod +x "$bin/docker-entrypoint.sh"

run_guard() {
  PGDATA="$root/pgdata" \
    BUILDHOUND_DB_INSTANCE="${1:-test}" \
    BUILDHOUND_DB_MAJOR="${2:-16}" \
    BUILDHOUND_DB_ALLOW_INIT="${3:-false}" \
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
run_guard test 16 true
test "$(cat "$root/.buildhound-volume")" = 'instance=test major=16'
test -f "$root/pgdata/PG_VERSION"
run_guard test 16 false

expect_exit 70 other 16 false
expect_exit 70 test 17 false

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
