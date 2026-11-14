#!/bin/sh
# Reads the database passwords from Docker secret files so they never sit in compose.yaml or .env: the pool's
# (restaurantpro_app) and, since ADR 0039, Flyway's (restaurantpro_owner).
# JVM flags come from JAVA_TOOL_OPTIONS (read by the JVM itself).
set -eu
if [ -n "${RESTAURANTPRO_DB_PASSWORD_FILE:-}" ]; then
  RESTAURANTPRO_DB_PASSWORD="$(cat "$RESTAURANTPRO_DB_PASSWORD_FILE")"
  export RESTAURANTPRO_DB_PASSWORD
fi
if [ -n "${RESTAURANTPRO_FLYWAY_PASSWORD_FILE:-}" ]; then
  RESTAURANTPRO_FLYWAY_PASSWORD="$(cat "$RESTAURANTPRO_FLYWAY_PASSWORD_FILE")"
  export RESTAURANTPRO_FLYWAY_PASSWORD
fi
exec java -jar /app/app.jar "$@"
