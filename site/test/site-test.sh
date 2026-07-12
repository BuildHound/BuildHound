#!/bin/sh
set -eu
grep -q '<html lang="en"' site/index.template.html
[ "$(grep -o '<h1' site/index.template.html | wc -l | tr -d ' ')" = 1 ]
grep -q 'https://github.com/BuildHound/BuildHound' site/index.template.html
if grep -Eiq 'https?://[^_].*\.(js|css|woff|png)' site/index.template.html; then exit 1; fi
grep -q "Content-Security-Policy" site/nginx.conf
grep -q 'focus-visible' site/assets/site.css
for value in 'http://bad.example' 'https://user@bad.example' 'https://bad.example/path' 'https://bad.example/?x=1' 'https://bad.example/#x'; do
  if BUILDHOUND_SITE_DASHBOARD_URL="$value" BUILDHOUND_SITE_NOINDEX=true BUILDHOUND_SITE_HOST=site.example.test sh site/render.sh 2>/dev/null; then exit 1; fi
done
printf 'site static policy validated\n'
