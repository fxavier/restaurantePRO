# restaurantPRO PostgreSQL image (D98): the D4 pin plus a pinned age, so the archive_command can encrypt WAL
# before it reaches the pg-wal-archive volume. lib/age.sh also runs age from this image on machines
# without it (restore hosts, the build machine). Built and tagged by deploy/compose.yaml:
#   docker build -t restaurantpro-postgres:18.6-r1 deploy/postgres
# Bump the -rN suffix with any change here (a new age release included).
FROM postgres:18.6-alpine
RUN apk add --no-cache age=1.3.2-r1
