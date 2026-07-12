#!/bin/sh
set -eu
url=${BUILDHOUND_SITE_DASHBOARD_URL-}
noindex=${BUILDHOUND_SITE_NOINDEX-}
host=${BUILDHOUND_SITE_HOST-}
case "$url" in https://*) ;; *) echo "dashboard URL must be an absolute HTTPS origin" >&2; exit 64;; esac
origin=${url#https://}
case "$origin" in ''|*[!A-Za-z0-9.:-]*|*/*|*@*|:*|*:) echo "dashboard URL must contain only a host and optional port" >&2; exit 64;; esac
case "$noindex" in true|false) ;; *) echo "BUILDHOUND_SITE_NOINDEX must be true or false" >&2; exit 64;; esac
case "$host" in ''|*[!A-Za-z0-9.-]*|.*|*.) echo "BUILDHOUND_SITE_HOST is invalid" >&2; exit 64;; esac
tmp=$(mktemp /tmp/index.XXXXXX)
conf=$(mktemp /tmp/nginx.XXXXXX)
trap 'rm -f "$tmp" "$conf"' EXIT
sed "s|__DASHBOARD_URL__|$url|g" /usr/share/buildhound/index.template.html > "$tmp"
mkdir -p /tmp/html
mv "$tmp" /tmp/html/index.html
if [ "$noindex" = true ]; then robots='noindex, nofollow'; printf 'User-agent: *\nDisallow: /\n' > /tmp/html/robots.txt; else robots='index, follow'; printf 'User-agent: *\nAllow: /\n' > /tmp/html/robots.txt; fi
sed -e "s|__SITE_HOST__|$host|g" -e "s|__ROBOTS__|$robots|g" /etc/nginx/nginx.template.conf > "$conf"
exec nginx -c "$conf" -g 'daemon off;'
