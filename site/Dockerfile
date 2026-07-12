FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6
COPY --chown=101:101 site/index.template.html /usr/share/buildhound/index.template.html
COPY --chown=101:101 site/assets/ /usr/share/nginx/html/assets/
COPY --chown=101:101 site/nginx.conf /etc/nginx/nginx.template.conf
COPY --chown=101:101 --chmod=0555 site/render.sh /usr/local/bin/buildhound-site
USER 101:101
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/buildhound-site"]
