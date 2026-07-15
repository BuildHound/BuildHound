#!/usr/bin/env bash
set -euo pipefail

version="${1:-}"
expected="${2:-}"
if [[ ! "$expected" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Expected publication digest must be 64 lowercase hex characters." >&2
  exit 2
fi

actual="$(bash .github/scripts/gradle-plugin-publication-digest.sh "$version")"
if [[ "$actual" != "$expected" ]]; then
  echo "Publication differs from the credential-free pre-approval build." >&2
  exit 1
fi
