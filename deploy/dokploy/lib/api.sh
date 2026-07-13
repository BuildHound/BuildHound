#!/usr/bin/env bash
# Shared, fail-closed Dokploy HTTP transport. This file is sourced by callers.
{ set +x; } 2>/dev/null

_dokploy_api_diagnostic() {
  local safe_path=${2%%\?*}
  printf 'dokploy_api method=%s path=%s status=%s exit=%s\n' \
    "$1" "$safe_path" "$3" "$4" >&2
}

_dokploy_normalize_origin() {
  local value=${1-} authority host port old_ifs label

  case "$value" in
    https://*) ;;
    *) return 1 ;;
  esac
  case "$value" in
    *[[:space:]]*) return 1 ;;
  esac

  authority=${value#https://}
  case "$authority" in
    */) authority=${authority%/} ;;
  esac
  case "$authority" in
    ''|*/*|*@*|*\?*|*\#*|*\\*) return 1 ;;
  esac

  case "$authority" in
    *:*)
      host=${authority%%:*}
      port=${authority#*:}
      case "$port" in
        ''|*[!0-9]*|*:* ) return 1 ;;
      esac
      if [ "${#port}" -gt 5 ]; then
        return 1
      fi
      if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
        return 1
      fi
      ;;
    *)
      host=$authority
      ;;
  esac

  case "$host" in
    ''|.*|*.|*[!A-Za-z0-9.-]*) return 1 ;;
  esac
  if [ "${#host}" -gt 253 ]; then
    return 1
  fi

  old_ifs=$IFS
  IFS=.
  # Intentional splitting validates every DNS label independently.
  # shellcheck disable=SC2086
  set -- $host
  IFS=$old_ifs
  for label in "$@"; do
    case "$label" in
      ''|-*|*-|*[!A-Za-z0-9-]*) return 1 ;;
    esac
    if [ "${#label}" -gt 63 ]; then
      return 1
    fi
  done

  printf 'https://%s' "$authority"
}

_dokploy_valid_api_path() {
  local value=${1-} endpoint query

  case "$value" in
    ''|/*|*://*|*[[:space:]]*|*\\*|*\#*|*[!A-Za-z0-9._~/?\&=%+,-]*)
      return 1
      ;;
  esac

  endpoint=${value%%\?*}
  case "$endpoint" in
    ''|.|..|./*|../*|*/.|*/..|*/./*|*/../*|*//* ) return 1 ;;
  esac

  case "$value" in
    *\?*)
      query=${value#*\?}
      case "$query" in
        *\?*) return 1 ;;
      esac
      ;;
  esac
  return 0
}

# dokploy_api METHOD PATH [BODY]
#
# METHOD is GET or POST. PATH is relative to /api/ and must already contain any
# required percent-encoding. POST bodies are canonicalized into a private,
# short-lived request file so neither credentials nor rendered manifests appear
# in curl's argv. A successful JSON response body is written to stdout; errors
# expose only the request method/query-free path, HTTP status (or validation
# class), and exit code.
dokploy_api() (
  local method=${1-} path=${2-} body=${3-}
  local origin token header_file='' request_file='' response_file='' status='' rc=0
  BUILDHOUND_DOKPLOY_API_WORKDIR=

  case "$method" in
    GET|POST) ;;
    *)
      _dokploy_api_diagnostic invalid invalid invalid_method 2
      exit 2
      ;;
  esac
  if ! _dokploy_valid_api_path "$path"; then
    _dokploy_api_diagnostic "$method" invalid invalid_path 2
    exit 2
  fi
  if ! origin=$(_dokploy_normalize_origin "${DOKPLOY_URL-}"); then
    _dokploy_api_diagnostic "$method" "$path" invalid_origin 2
    exit 2
  fi

  token=${DOKPLOY_TOKEN-}
  case "$token" in
    ''|*$'\r'*|*$'\n'*)
      _dokploy_api_diagnostic "$method" "$path" invalid_token 2
      exit 2
      ;;
  esac

  umask 077
  # Invoked indirectly by the EXIT trap below.
  # shellcheck disable=SC2317,SC2329
  _dokploy_api_cleanup() {
    if [ -n "${BUILDHOUND_DOKPLOY_API_WORKDIR-}" ]; then
      rm -rf -- "$BUILDHOUND_DOKPLOY_API_WORKDIR" 2>/dev/null
    fi
  }
  trap _dokploy_api_cleanup EXIT
  trap '_dokploy_api_cleanup; exit 129' HUP
  trap '_dokploy_api_cleanup; exit 130' INT
  trap '_dokploy_api_cleanup; exit 143' TERM

  if ! BUILDHOUND_DOKPLOY_API_WORKDIR=$(mktemp -d \
      "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/buildhound-dokploy-api.XXXXXX" 2>/dev/null); then
    _dokploy_api_diagnostic "$method" "$path" local_error 1
    exit 1
  fi
  header_file=$BUILDHOUND_DOKPLOY_API_WORKDIR/headers
  request_file=$BUILDHOUND_DOKPLOY_API_WORKDIR/request.json
  response_file=$BUILDHOUND_DOKPLOY_API_WORKDIR/response.json
  if ! printf 'x-api-key: %s\nAccept: application/json\nContent-Type: application/json\n' \
      "$token" 2>/dev/null > "$header_file"; then
    _dokploy_api_diagnostic "$method" "$path" local_error 1
    exit 1
  fi

  if [ "$method" = POST ]; then
    if ! printf '%s' "$body" | jq -cS -s \
        'if length == 1 then .[0] else error("exactly one JSON request required") end' \
        > "$request_file" 2>/dev/null; then
      _dokploy_api_diagnostic "$method" "$path" invalid_json 65
      exit 65
    fi
    if status=$(command curl \
        --disable \
        --silent \
        --proto '=https' \
        --connect-timeout 10 \
        --max-time 30 \
        --max-redirs 0 \
        --request POST \
        --header "@$header_file" \
        --data-binary "@$request_file" \
        --output "$response_file" \
        --write-out '%{http_code}' \
        "$origin/api/$path" 2>/dev/null); then
      rc=0
    else
      rc=$?
    fi
  else
    if status=$(command curl \
        --disable \
        --silent \
        --proto '=https' \
        --connect-timeout 10 \
        --max-time 30 \
        --max-redirs 0 \
        --request GET \
        --header "@$header_file" \
        --output "$response_file" \
        --write-out '%{http_code}' \
        "$origin/api/$path" 2>/dev/null); then
      rc=0
    else
      rc=$?
    fi
  fi

  case "$status" in
    [0-9][0-9][0-9]) ;;
    *) status=000 ;;
  esac
  if [ "$rc" -ne 0 ]; then
    _dokploy_api_diagnostic "$method" "$path" "$status" "$rc"
    exit "$rc"
  fi
  case "$status" in
    2[0-9][0-9])
      if ! jq -e -s 'length == 1' "$response_file" >/dev/null 2>&1; then
        _dokploy_api_diagnostic "$method" "$path" invalid_json 65
        exit 65
      fi
      cat -- "$response_file"
      ;;
    *)
      _dokploy_api_diagnostic "$method" "$path" "$status" 22
      exit 22
      ;;
  esac
)

api_get() {
  dokploy_api GET "${1-}"
}

api_post() {
  dokploy_api POST "${1-}" "${2-}"
}
