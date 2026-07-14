#!/bin/sh
set -eu
grep -q '<html lang="en"' site/index.template.html
[ "$(grep -o '<h1' site/index.template.html | wc -l | tr -d ' ')" = 1 ]
grep -q 'https://github.com/BuildHound/BuildHound' site/index.template.html
if grep -Eiq 'https?://[^_].*\.(js|css|woff|png)' site/index.template.html; then exit 1; fi
grep -q "Content-Security-Policy" site/nginx.conf
grep -q 'focus-visible' site/assets/site.css
grep -q -- '--control-border:#96897b' site/assets/site.css
grep -q 'border:1px solid var(--control-border)' site/assets/site.css
grep -q 'footer a{display:inline-flex;align-items:center' site/assets/site.css
grep -q 'class="brand-mark"' site/index.template.html
grep -q 'stroke="currentColor"' site/index.template.html
grep -q 'font-weight:600 750' site/assets/site.css
grep -q 'font-weight:400 700' site/assets/site.css
grep -q 'font-weight:400 600' site/assets/site.css
grep -q 'etag on' site/nginx.conf
grep -q 'Cache-Control "public, max-age=0, must-revalidate"' site/nginx.conf
grep -q "location = /health { access_log off; default_type text/plain;" site/nginx.conf
grep -q '^HEALTHCHECK ' site/Dockerfile
test "$(find site/assets/fonts -maxdepth 1 -type f -name '*.woff2' | wc -l | tr -d ' ')" = 3
for font in Fraunces-Variable.woff2 Inter-Variable.woff2 JetBrainsMono-Variable.woff2; do
  test -f "site/assets/fonts/$font"
  grep -q "$font" site/assets/site.css
  expected=$(sha256sum "site/assets/fonts/$font" | cut -d' ' -f1)
  grep -q "$font.*$expected" site/assets/fonts/PROVENANCE.md
done
for value in 'http://bad.example' 'https://user@bad.example' 'https://bad.example/path' 'https://bad.example/?x=1' 'https://bad.example/#x'; do
  if BUILDHOUND_SITE_DASHBOARD_URL="$value" BUILDHOUND_SITE_NOINDEX=true BUILDHOUND_SITE_HOST=site.example.test sh site/render.sh 2>/dev/null; then exit 1; fi
done

if [ -n "${BUILDHOUND_SITE_TEST_IMAGE:-}" ]; then
  tmp=$(mktemp -d)
  container=
  cleanup() {
    if [ -n "$container" ]; then docker rm -f "$container" >/dev/null 2>&1 || true; fi
    rm -rf "$tmp"
  }
  trap cleanup EXIT INT TERM

  container=$(docker run -d --read-only --user 101:101 \
    --mount type=tmpfs,destination=/tmp,tmpfs-size=67108864 \
    -p 127.0.0.1::8080 \
    -e BUILDHOUND_SITE_DASHBOARD_URL=https://dashboard.example.test \
    -e BUILDHOUND_SITE_NOINDEX=true \
    -e BUILDHOUND_SITE_HOST=site.example.test \
    "$BUILDHOUND_SITE_TEST_IMAGE")
  port=$(docker port "$container" 8080/tcp | awk -F: 'END { print $NF }')
  base="http://127.0.0.1:$port"

  attempts=0
  until [ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$container")" = healthy ]; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 30 ]; then docker logs "$container"; exit 1; fi
    sleep 1
  done

  curl -fsS -H 'Host: site.example.test' -H 'X-Forwarded-Proto: https' -D "$tmp/index.headers" "$base/" > "$tmp/index.html"
  grep -q 'href="https://dashboard.example.test"' "$tmp/index.html"
  if grep -q '__DASHBOARD_URL__' "$tmp/index.html"; then exit 1; fi

  curl -fsS -H 'Host: site.example.test' "$base/robots.txt" > "$tmp/robots.txt"
  grep -qFx 'User-agent: *' "$tmp/robots.txt"
  grep -qFx 'Disallow: /' "$tmp/robots.txt"

  curl -fsS -H 'Host: site.example.test' -H 'X-Forwarded-Proto: https' -D "$tmp/health.headers" "$base/health" > "$tmp/health.txt"
  test "$(tr -d '\r\n' < "$tmp/health.txt")" = ok
  tr -d '\r' < "$tmp/health.headers" | grep -qi '^Content-Type: text/plain$'
  for headers in "$tmp/index.headers" "$tmp/health.headers"; do
    tr -d '\r' < "$headers" > "$headers.clean"
    grep -qi '^Content-Security-Policy:' "$headers.clean"
    grep -qi '^Referrer-Policy: no-referrer$' "$headers.clean"
    grep -qi '^X-Content-Type-Options: nosniff$' "$headers.clean"
    grep -qi '^X-Frame-Options: DENY$' "$headers.clean"
    grep -qi '^Permissions-Policy:' "$headers.clean"
    grep -qi '^Strict-Transport-Security: max-age=31536000; includeSubDomains$' "$headers.clean"
    grep -qi '^X-Robots-Tag: noindex, nofollow$' "$headers.clean"
  done

  curl -fsS -H 'Host: site.example.test' -D "$tmp/asset.headers" "$base/assets/site.css" > "$tmp/site.css"
  tr -d '\r' < "$tmp/asset.headers" > "$tmp/asset.headers.clean"
  grep -qi '^Cache-Control: public, max-age=0, must-revalidate$' "$tmp/asset.headers.clean"
  etag=$(sed -n 's/^[Ee][Tt][Aa][Gg]:[[:space:]]*//p' "$tmp/asset.headers.clean" | head -1)
  test -n "$etag"
  status=$(curl -sS -o /dev/null -D "$tmp/revalidate.headers" -w '%{http_code}' -H 'Host: site.example.test' -H "If-None-Match: $etag" "$base/assets/site.css")
  test "$status" = 304
  tr -d '\r' < "$tmp/revalidate.headers" > "$tmp/revalidate.headers.clean"
  grep -qi '^Cache-Control: public, max-age=0, must-revalidate$' "$tmp/revalidate.headers.clean"
  grep -qi '^ETag:' "$tmp/revalidate.headers.clean"

  test "$(docker exec "$container" id -u)" = 101
  test "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$container")" = true
  if docker exec "$container" touch /usr/share/nginx/html/read-only-check 2>/dev/null; then exit 1; fi
  docker exec "$container" sh -c 'touch /tmp/write-check && rm /tmp/write-check'
fi
printf 'site static policy validated\n'
