# syntax=docker/dockerfile:1
FROM timescale/timescaledb:latest-pg16@sha256:ba149561ad4ddff5940d6eb0a0df60aefd1355cee1a450928f271267038fc888

COPY --chmod=0555 deploy/dokploy/volume-guard.sh /opt/buildhound/volume-guard.sh
COPY --chmod=0555 deploy/dokploy/db/volume-marker-init.sh /docker-entrypoint-initdb.d/zzzz-buildhound-volume-ready.sh
