#!/usr/bin/env bash
# PgBouncer transaction-mode drill (A83, D178, D188). Needs Docker and Java 25; leaves nothing behind (Testcontainers).
# Usage: deploy/drills/pgbouncer/run.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../../../backend"
./mvnw -q test -Dtest=PgBouncerTransactionModeTest -Dsurefire.failIfNoSpecifiedTests=false
echo "PASS: PgBouncer transaction-mode drill" >&2
