#!/bin/sh
# BuildHound review-residue garbage collector (plan 089, Gate H1).
#
# Runs ON the Dokploy host (the platform trust root), installed once by the
# operator. Dokploy <= 0.29.12 cannot delete a compose and its isolated
# network atomically, so every retired review leaves a zero-replica anchor
# compose and an orphaned overlay network. This script removes both, bounded
# by strict name/metadata filters:
#   - overlay networks matching the review naming scheme with no attachments
#   - anchor composes marked retired:true older than RETENTION_DAYS
#
# Default is a DRY-RUN REPORT (the Gate H1 evidence). --apply mutates.
# --install copies the script to /usr/local/bin and enables a daily systemd
# timer whose unit reads /etc/buildhound/review-gc.env (root-owned, 0600):
#   DOKPLOY_URL=...        # local Dokploy origin, e.g. http://127.0.0.1:3000
#   DOKPLOY_TOKEN=...      # instance API token; never stored anywhere else
#   ENVIRONMENT_ID=...     # the dedicated review environment
#   RETENTION_DAYS=14      # optional
# The token is read from the environment and passed to curl via a 0600
# header file — never argv, never a unit file, never this script.
set -eu

ENV_FILE=/etc/buildhound/review-gc.env
INSTALL_PATH=/usr/local/bin/buildhound-review-gc
UNIT_DIR=/etc/systemd/system
NETWORK_PATTERN='^bh-[0-9a-f]{24}-mr[0-9]+-[A-Za-z0-9]{6}$'
NAME_PATTERN='^mr[0-9]+$'

mode=report
while [ "$#" -gt 0 ]; do
  case "$1" in
    --apply) mode=apply; shift ;;
    --install) mode=install; shift ;;
    --help|-h)
      sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "usage: $0 [--apply|--install]" >&2
      exit 64
      ;;
  esac
done

if [ "$mode" = install ]; then
  if [ "$(id -u)" != 0 ]; then
    echo "--install must run as root" >&2
    exit 64
  fi
  if [ ! -f "$ENV_FILE" ]; then
    echo "create $ENV_FILE (root:root, chmod 0600) with DOKPLOY_URL, DOKPLOY_TOKEN, ENVIRONMENT_ID first" >&2
    exit 64
  fi
  perms=$(stat -c '%a' "$ENV_FILE" 2>/dev/null || stat -f '%Lp' "$ENV_FILE")
  if [ "$perms" != 600 ]; then
    echo "$ENV_FILE must be chmod 0600 (found $perms)" >&2
    exit 64
  fi
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

: "${DOKPLOY_URL:?}" "${DOKPLOY_TOKEN:?}" "${ENVIRONMENT_ID:?}"
retention_days=${RETENTION_DAYS:-14}
case "$retention_days" in
  ''|0*|*[!0-9]*)
    echo "RETENTION_DAYS must be a positive base-10 integer" >&2
    exit 64
    ;;
esac
case "$ENVIRONMENT_ID" in
  *[!A-Za-z0-9_-]*)
    echo "ENVIRONMENT_ID must be a Dokploy object id" >&2
    exit 64
    ;;
esac

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT
umask 077
headers=$workdir/headers
printf 'x-api-key: %s\nAccept: application/json\nContent-Type: application/json\n' \
  "$DOKPLOY_TOKEN" > "$headers"

api_get() {
  curl --disable --silent --show-error --fail --max-time 30 \
    --header "@$headers" "$DOKPLOY_URL/api/$1"
}

api_post() {
  curl --disable --silent --show-error --fail --max-time 60 \
    --header "@$headers" --request POST --data "$2" "$DOKPLOY_URL/api/$1"
}

had_error=false
now_epoch=$(date -u +%s)
retention_seconds=$((retention_days * 86400))

# --- Orphaned review overlay networks -------------------------------------
docker network ls --filter driver=overlay --format '{{.Name}}' \
  | { grep -E "$NETWORK_PATTERN" || true; } > "$workdir/networks"

removed_networks=''
remaining_networks=''
while IFS= read -r network; do
  [ -n "$network" ] || continue
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

deleted_anchors=''
eligible_anchors=''
kept_anchors=''
while IFS= read -r item; do
  [ -n "$item" ] || continue
  name=$(printf '%s\n' "$item" | jq -r '.name // ""')
  case "$name" in
    mr*) ;;
    *) continue ;;
  esac
  printf '%s' "$name" | grep -Eq "$NAME_PATTERN" || continue
  compose_id=$(printf '%s\n' "$item" | jq -r '.composeId // ""')
  case "$compose_id" in
    ''|*[!A-Za-z0-9_-]*) had_error=true; continue ;;
  esac
  metadata=$(printf '%s\n' "$item" | jq -c '
    (.description // "{}") | (try fromjson catch {})
  ')
  retired=$(printf '%s\n' "$metadata" | jq -r '.retired // false')
  if [ "$retired" != true ]; then
    continue
  fi
  stamp=$(printf '%s\n' "$metadata" | jq -r '.activatedAt // ""')
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
printf 'review GC report (mode=%s retention=%sd)\n' "$mode" "$retention_days"
printf '  networks removed:%s\n' "${removed_networks:- none}"
printf '  networks remaining:%s\n' "${remaining_networks:- none}"
printf '  anchors deleted:%s\n' "${deleted_anchors:- none}"
printf '  anchors eligible:%s\n' "${eligible_anchors:- none}"
printf '  anchors kept (within retention):%s\n' "${kept_anchors:- none}"

if [ "$had_error" = true ]; then
  exit 1
fi
