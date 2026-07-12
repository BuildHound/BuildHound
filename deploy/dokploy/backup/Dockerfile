FROM alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce
RUN apk add --no-cache age aws-cli bash curl postgresql16-client tzdata \
    && addgroup -S -g 10001 backup && adduser -S -D -H -u 10001 -G backup backup
COPY --chmod=0555 deploy/dokploy/backup/backup-loop.sh /opt/buildhound/backup-loop.sh
COPY --chmod=0555 deploy/dokploy/backup/restore.sh /opt/buildhound/restore.sh
USER 10001:10001
