#!/usr/bin/env bash

# Shared mechanics for the trusted Dokploy shell client.  Keep this module free of
# deployment policy; release and review policy belong in their respective modules.
export LC_ALL=C

die() {
  local message=${1:-"unexpected error"}
  printf '%s\n' "$message" >&2
  return 1
}

require_command() {
  if [[ $# -ne 1 || -z ${1:-} ]]; then
    die "require_command expects one command name"
    return 1
  fi
  if ! command -v "$1" >/dev/null 2>&1; then
    die "required command is unavailable: $1"
    return 1
  fi
}

is_lower_hex() {
  local length=${1:-}
  local value=${2:-}
  local pattern
  [[ $length =~ ^[1-9][0-9]*$ ]] || return 1
  pattern="^[0-9a-f]{$length}$"
  [[ $value =~ $pattern ]]
}

is_source_sha() {
  is_lower_hex 40 "${1:-}"
}

is_sha256() {
  is_lower_hex 64 "${1:-}"
}

is_digest_reference() {
  local value=${1:-}
  [[ $value =~ ^[a-z0-9./_-]+(:[a-z0-9._-]+)?@sha256:[0-9a-f]{64}$ ]]
}

is_migration_id() {
  local value=${1:-}
  [[ $value =~ ^V[0-9]+__[A-Za-z0-9_.-]+$ ]]
}

sha256_stdin() {
  local output digest
  if command -v sha256sum >/dev/null 2>&1; then
    output=$(sha256sum) || return 1
  elif command -v shasum >/dev/null 2>&1; then
    output=$(shasum -a 256) || return 1
  else
    die "required SHA-256 command is unavailable"
    return 1
  fi
  digest=${output%%[[:space:]]*}
  if ! is_sha256 "$digest"; then
    die "SHA-256 command returned invalid output"
    return 1
  fi
  printf '%s\n' "$digest"
}

sha256_file() {
  local file=${1:-}
  if [[ $# -ne 1 || ! -r $file ]]; then
    die "readable file required for SHA-256"
    return 1
  fi
  sha256_stdin < "$file"
}

canonical_json() {
  require_command jq || return 1
  if [[ $# -eq 0 ]]; then
    jq -cS .
  else
    jq -cS . "$@"
  fi
}
