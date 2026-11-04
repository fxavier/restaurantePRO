#!/bin/sh
# Reads the database password from a Docker secret file so it never sits in compose.yaml or .env.
# JVM flags come from JAVA_TOOL_OPTIONS (read by the JVM itself).
set -eu
if [ -n "${RESTAURANTPRO_DB_PASSWORD_FILE:-}" ]; then
  RESTAURANTPRO_DB_PASSWORD="$(cat "$RESTAURANTPRO_DB_PASSWORD_FILE")"
  export RESTAURANTPRO_DB_PASSWORD
fi
exec java -jar /app/app.jar "$@"
