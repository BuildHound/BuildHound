#!/usr/bin/env bash
set -euo pipefail

version="${1:-}"
if [[ ! "$version" =~ ^[0-9A-Za-z][0-9A-Za-z.+-]*$ ]]; then
  echo "Usage: $0 <release-version>" >&2
  exit 2
fi

files=()
while IFS= read -r file; do
  files+=("$file")
done < <(
  {
    find buildhound-gradle-plugin/build/libs \
      -maxdepth 1 -type f \
      -name "buildhound-gradle-plugin-$version*.jar" \
      -print
    find buildhound-gradle-plugin/build/publications \
      -mindepth 2 -maxdepth 2 -type f \
      \( -name pom-default.xml -o -name module.json \) \
      -print
  } | LC_ALL=C sort
)

if (( ${#files[@]} != 7 )); then
  echo "Expected 7 Portal publication files, found ${#files[@]}:" >&2
  printf '  %s\n' "${files[@]}" >&2
  exit 1
fi

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print $1 }'
  else
    shasum -a 256 "$1" | awk '{ print $1 }'
  fi
}

hash_stream() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{ print $1 }'
  else
    shasum -a 256 | awk '{ print $1 }'
  fi
}

for file in "${files[@]}"; do
  if [[ ! -f "$file" ]]; then
    echo "Missing publication file: $file" >&2
    exit 1
  fi
done

for file in "${files[@]}"; do
  printf '%s  %s\n' "$(hash_file "$file")" "$file"
done | LC_ALL=C sort | hash_stream
