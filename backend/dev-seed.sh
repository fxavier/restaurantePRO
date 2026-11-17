#!/usr/bin/env bash
# Local development seed (DEV ONLY): fills the dev database of ./run-local.sh with a ready-to-use store through the
# HTTP API (no SQL, so every business rule applies), with fixed logins and PINs printed at the end.
#
# Usage, from backend/, with the app running:
#   ./dev-seed.sh [--base-url http://localhost:8080]
# R6: not usable yet. deploy/load/DevSeed.java still signs in with the R5 local login (an admin username and password,
# POST /api/v1/auth/login), which R6 removed: users sign in through Keycloak and the API takes only its bearer tokens.
# It works again once DevSeed signs in with a Keycloak token (follow-up). It never takes a password from the
# command line.
#
# Creates store DEV "Restaurante Dev" (Africa/Maputo): terminals POS-1 and POS-2 enrolled (device tokens written to
# .dev-seed/terminals.txt, git-ignored), 8 tables, kitchen/bar/counter printers on HOLD (nothing is printed), series FR,
# register CX1; BackOffice accounts gerente and stock; POS employees GERENTE, CAIXA, EMPREGADO, ESTAFETA; a published
# catalog of 20 items (Bebidas, Pratos, Sobremesas, Cafés), a menu of the day and a delivery fee; a happy hour on
# Bebidas 17:00-19:00; discount limits 10 % free / 50 % max; a loyalty program with a free-coffee reward; 3 customers
# and a delivery zone; kitchen and bar warehouses live through approved opening counts, 13 stock items, recipes, a
# supplier with a purchase order and a confirmed receipt; then today's sales (9 closed orders, 1 table left open).
#
# Re-running is safe: when store DEV exists it prints "already seeded" and the logins, and exits 0.
# The program is deploy/load/DevSeed.java; it shares the HTTP client of deploy/load/LoadSmoke.java.
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
    echo "dev-seed: java (JDK 25) is required" >&2
    exit 2
fi
version="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')"
if [ -z "$version" ] || [ "${version%%.*}" -lt 25 ]; then
    echo "dev-seed: JDK 25 or later is required (found ${version:-unknown})" >&2
    exit 2
fi

exec java ../deploy/load/DevSeed.java --out-dir .dev-seed "$@"
