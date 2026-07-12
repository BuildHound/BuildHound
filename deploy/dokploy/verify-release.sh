#!/bin/bash
set -euo pipefail
: "${BUILDHOUND_SITE_URL:?}" "${BUILDHOUND_DASHBOARD_URL:?}" "${BUILDHOUND_READ_TOKEN:?}" "${BUILDHOUND_INGEST_TOKEN:?}"
payload=buildhound-commons/src/jvmTest/resources/golden/build-payload-v1-ci-env.json
build_id=$(jq -r .buildId "$payload")
curl -fsS "$BUILDHOUND_SITE_URL/" | grep -q 'Track every Gradle build'
curl -fsS "$BUILDHOUND_DASHBOARD_URL/health" | grep -qx ok
curl -fsS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $BUILDHOUND_INGEST_TOKEN" -H 'Content-Type: application/json' --data-binary "@$payload" "$BUILDHOUND_DASHBOARD_URL/v1/builds" | grep -qx 202
curl -fsS -H "Authorization: Bearer $BUILDHOUND_READ_TOKEN" "$BUILDHOUND_DASHBOARD_URL/v1/builds/$build_id" | jq -e --arg id "$build_id" '.buildId == $id' >/dev/null
