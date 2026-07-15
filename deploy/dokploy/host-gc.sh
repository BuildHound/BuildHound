#!/bin/sh
# BuildHound review-residue garbage collector (plan 089, Gate H1).
#
# Runs ON the Dokploy host (the platform trust root), installed once by the
# operator. Dokploy <= 0.29.12 cannot delete a compose and its isolated
# network atomically, so every retired review leaves a zero-replica anchor
# compose and an orphaned overlay network. This script removes both, bounded
# by strict repository/name/lifecycle filters:
#   - overlay networks matching THIS repository's review naming scheme whose
#     review is not active (retired or already deleted) and that have no
#     attachments (docker refuses in-use networks; those retry next run)
#   - anchor composes owned by THIS repository, marked retired:true, older
#     than RETENTION_DAYS (measured from retiredAt; activatedAt/createdAt
#     are the fallback for pre-retiredAt records)
#
# Default is a DRY-RUN REPORT (the Gate H1 evidence). --apply mutates.
# --install copies the script to /usr/local/bin and enables a daily systemd
# timer whose unit reads /etc/buildhound/review-gc.env (root:root, 0600):
#   DOKPLOY_URL=...             # local Dokploy origin, e.g. http://127.0.0.1:3000
#   DOKPLOY_TOKEN=...           # instance API token; never stored anywhere else
#   ENVIRONMENT_ID=...          # the dedicated review environment
#   BUILDHOUND_REPOSITORY=...   # owner/name, e.g. BuildHound/BuildHound
#   RETENTION_DAYS=14           # optional, 1..3650
# The token is read from the environment and passed to curl via a 0600
# header file — never argv, never a unit file, never this script.
#
# Requires GNU coreutils date (-d) and the docker CLI; both are probed early.
set -eu

ENV_FILE=/etc/buildhound/review-gc.env
INSTALL_PATH=/usr/local/bin/buildhound-review-gc
UNIT_DIR=/etc/systemd/system

mode=report
while [ "$#" -gt 0 ]; do
  case "$1" in
    --apply) mode=apply; shift ;;
    --install) mode=install; shift ;;
    --help|-h)
      sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "usage: $0 [--apply|--install]" >&2
      exit 64
      ;;
  esac
done

require_env_file_integrity() {
  # The unit runs as root and trusts this file's contents; ownership and
  # mode are enforced on every run, not only at install time, so drift
  # after installation fails closed.
  owner=$(stat -c '%u' "$ENV_FILE" 2>/dev/null || stat -f '%u' "$ENV_FILE")
  perms=$(stat -c '%a' "$ENV_FILE" 2>/dev/null || stat -f '%Lp' "$ENV_FILE")
  if [ "$owner" != 0 ] || [ "$perms" != 600 ]; then
    echo "$ENV_FILE must be owned by root with mode 0600 (found uid=$owner mode=$perms)" >&2
    exit 64
  fi
}

if [ "$mode" = install ]; then
  if [ "$(id -u)" != 0 ]; then
    echo "--install must run as root" >&2
    exit 64
  fi
  if [ ! -f "$ENV_FILE" ]; then
    echo "create $ENV_FILE (root:root, chmod 0600) with DOKPLOY_URL, DOKPLOY_TOKEN, ENVIRONMENT_ID, BUILDHOUND_REPOSITORY first" >&2
    exit 64
  fi
  require_env_file_integrity
  install -m 0755 "$0" "$INSTALL_PATH"
  cat > "$UNIT_DIR/buildhound-review-gc.service" <<UNIT
[Unit]
Description=BuildHound review-residue garbage collection (plan 089)

[Service]
Type=oneshot
EnvironmentFile=$ENV_FILE
ExecStart=$INSTALL_PATH --apply
UNIT
  cat > "$UNIT_DIR/buildhound-review-gc.timer" <<UNIT
[Unit]
Description=Daily BuildHound review-residue garbage collection

[Timer]
OnCalendar=daily
Persistent=true

[Install]
WantedBy=timers.target
UNIT
  systemctl daemon-reload
  systemctl enable --now buildhound-review-gc.timer
  echo "installed: $INSTALL_PATH + buildhound-review-gc.timer (reads $ENV_FILE)"
  echo "first report: run '$INSTALL_PATH' (dry-run) and return its output as the Gate H1 evidence"
  exit 0
fi

: "${DOKPLOY_URL:?}" "${DOKPLOY_TOKEN:?}" "${ENVIRONMENT_ID:?}" "${BUILDHOUND_REPOSITORY:?}"
if [ -f "$ENV_FILE" ]; then
  require_env_file_integrity
fi
retention_days=${RETENTION_DAYS:-14}
case "$retention_days" in
  ''|0*|*[!0-9]*)
    echo "RETENTION_DAYS must be a positive base-10 integer" >&2
    exit 64
    ;;
esac
if [ "${#retention_days}" -gt 4 ] || [ "$retention_days" -gt 3650 ]; then
  echo "RETENTION_DAYS must be between 1 and 3650" >&2
  exit 64
fi
case "$ENVIRONMENT_ID" in
  ''|*[!A-Za-z0-9_-]*)
    echo "ENVIRONMENT_ID must be a Dokploy object id" >&2
    exit 64
    ;;
esac
case "$DOKPLOY_URL" in
  http://*|https://*) ;;
  *)
    echo "DOKPLOY_URL must be an http(s) origin" >&2
    exit 64
    ;;
esac
case "$DOKPLOY_TOKEN" in
  *[![:graph:]]*)
    echo "DOKPLOY_TOKEN must not contain whitespace or control characters" >&2
    exit 64
    ;;
esac
case "$BUILDHOUND_REPOSITORY" in
  */*) ;;
  *)
    echo "BUILDHOUND_REPOSITORY must be owner/name" >&2
    exit 64
    ;;
esac
# GNU date is required for -d parsing; fail with a clear message elsewhere.
if ! date -u -d '1970-01-01T00:00:00Z' +%s >/dev/null 2>&1; then
  echo "GNU coreutils date is required (date -d support)" >&2
  exit 64
fi

# Repository-scoped network prefix: bh-<first 24 hex of sha256(lowercase repo)>
# (must match review_provider_id in lib/review.sh).
sha256_text() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | cut -d' ' -f1
  else
    shasum -a 256 | cut -d' ' -f1
  fi
}
repo_lower=$(printf '%s' "$BUILDHOUND_REPOSITORY" | tr '[:upper:]' '[:lower:]')
repo_digest=$(printf '%s' "$repo_lower" | sha256_text | cut -c1-24)
network_pattern="^bh-${repo_digest}-mr[0-9]+-[A-Za-z0-9]{6}\$"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT
umask 077
headers=$workdir/headers
printf 'x-api-key: %s\nAccept: application/json\nContent-Type: application/json\n' \
  "$DOKPLOY_TOKEN" > "$headers"

api_get() {
  curl --disable --silent --show-error --fail --max-redirs 0 --max-time 30 \
    --header "@$headers" "$DOKPLOY_URL/api/$1"
}

api_post() {
  curl --disable --silent --show-error --fail --max-redirs 0 --max-time 60 \
    --header "@$headers" --request POST --data "$2" "$DOKPLOY_URL/api/$1"
}

had_error=false
now_epoch=$(date -u +%s)
retention_seconds=$((retention_days * 86400))

# --- Review environment state (fetched first: it gates network removal) ----
api_get "environment.one?environmentId=$ENVIRONMENT_ID" > "$workdir/environment" || {
  echo "Dokploy environment lookup failed" >&2
  exit 1
}
jq -c '
  if type != "object" or (.compose | type) != "array" then
    error("invalid environment response")
  else
    .compose[]
    | select(type == "object")
    | {name, composeId, createdAt, description}
  end
' "$workdir/environment" > "$workdir/composes"

# Active (non-retired) review names: their networks must never be touched,
# even while transiently unattached (e.g. mid-deploy).
jq -rs '
  [.[]
   | select((.name // "") | test("^mr[0-9]+$"))
   | select((((.description // "{}") | (try fromjson catch {})).retired // false) != true)
   | .name] | .[]
' "$workdir/composes" > "$workdir/active-names"

# --- Orphaned review overlay networks -------------------------------------
# docker's own exit status is checked directly; a listing failure must never
# read as "no orphaned networks" in the Gate H1 report.
if ! docker network ls --filter driver=overlay --format '{{.Name}}' > "$workdir/networks-raw"; then
  echo "docker network listing failed" >&2
  exit 1
fi
grep -E "$network_pattern" "$workdir/networks-raw" > "$workdir/networks" || true

removed_networks=''
remaining_networks=''
skipped_active_networks=''
while IFS= read -r network; do
  [ -n "$network" ] || continue
  review_name=$(printf '%s' "$network" | sed -E "s/^bh-${repo_digest}-(mr[0-9]+)-[A-Za-z0-9]{6}\$/\\1/")
  if grep -Fxq "$review_name" "$workdir/active-names"; then
    skipped_active_networks="$skipped_active_networks $network"
    continue
  fi
  if [ "$mode" = apply ]; then
    # docker refuses to remove a network with attachments; an in-use network
    # is simply reported and retried on the next timer run.
    if docker network rm "$network" >/dev/null 2>&1; then
      removed_networks="$removed_networks $network"
    else
      remaining_networks="$remaining_networks $network"
    fi
  else
    remaining_networks="$remaining_networks $network"
  fi
done < "$workdir/networks"

# --- Retired anchor composes past retention --------------------------------
deleted_anchors=''
eligible_anchors=''
kept_anchors=''
while IFS= read -r item; do
  [ -n "$item" ] || continue
  name=$(printf '%s\n' "$item" | jq -r '.name // ""')
  printf '%s' "$name" | grep -Eq '^mr[0-9]+$' || continue
  compose_id=$(printf '%s\n' "$item" | jq -r '.composeId // ""')
  case "$compose_id" in
    ''|*[!A-Za-z0-9_-]*) had_error=true; continue ;;
  esac
  metadata=$(printf '%s\n' "$item" | jq -c '
    (.description // "{}") | (try fromjson catch {})
  ')
  repository=$(printf '%s\n' "$metadata" | jq -r '.repository // ""')
  if [ "$repository" != "$BUILDHOUND_REPOSITORY" ]; then
    # Not ours: a shared or misconfigured environment must never lose
    # another repository's objects to this collector.
    continue
  fi
  retired=$(printf '%s\n' "$metadata" | jq -r '.retired // false')
  if [ "$retired" != true ]; then
    continue
  fi
  stamp=$(printf '%s\n' "$metadata" | jq -r '.retiredAt // .activatedAt // ""')
  if [ -z "$stamp" ]; then
    stamp=$(printf '%s\n' "$item" | jq -r '.createdAt // ""')
  fi
  if [ -z "$stamp" ] || ! stamp_epoch=$(date -u -d "$stamp" +%s 2>/dev/null); then
    kept_anchors="$kept_anchors $name(no-timestamp)"
    had_error=true
    continue
  fi
  age=$((now_epoch - stamp_epoch))
  if [ "$age" -le "$retention_seconds" ]; then
    kept_anchors="$kept_anchors $name"
    continue
  fi
  if [ "$mode" = apply ]; then
    if api_post compose.delete "$(jq -cn --arg id "$compose_id" \
        '{composeId:$id, deleteVolumes:false}')" >/dev/null; then
      deleted_anchors="$deleted_anchors $name"
    else
      echo "compose.delete failed for $name" >&2
      had_error=true
      eligible_anchors="$eligible_anchors $name"
    fi
  else
    eligible_anchors="$eligible_anchors $name"
  fi
done < "$workdir/composes"

# --- Gate H1 report ---------------------------------------------------------
printf 'review GC report (mode=%s retention=%sd repository=%s)\n' "$mode" "$retention_days" "$BUILDHOUND_REPOSITORY"
printf '  networks removed:%s\n' "${removed_networks:- none}"
printf '  networks remaining:%s\n' "${remaining_networks:- none}"
printf '  networks skipped (active review):%s\n' "${skipped_active_networks:- none}"
printf '  anchors deleted:%s\n' "${deleted_anchors:- none}"
printf '  anchors eligible:%s\n' "${eligible_anchors:- none}"
printf '  anchors kept (within retention):%s\n' "${kept_anchors:- none}"

if [ "$had_error" = true ]; then
  exit 1
fi
