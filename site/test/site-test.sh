#!/bin/sh
set -eu
grep -q '<html lang="en"' site/index.template.html
[ "$(grep -o '<h1' site/index.template.html | wc -l | tr -d ' ')" = 1 ]
grep -q 'https://github.com/BuildHound/BuildHound' site/index.template.html
if grep -Eiq 'https?://[^_].*\.(js|css|woff|png)' site/index.template.html; then exit 1; fi
grep -q "Content-Security-Policy" site/nginx.conf
grep -q 'focus-visible' site/assets/site.css
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
printf 'site static policy validated\n'
