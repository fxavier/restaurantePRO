#!/usr/bin/env bash
# Load smoke: 10 POS terminals against a running stack (spec §12, PRD S45 and S54, D136, acceptance A56).
#
# Runs deploy/load/LoadSmoke.java (a single-file Java program: JDK HttpClient and virtual threads, no dependency)
# with `java LoadSmoke.java`. A run first SEEDS a fresh store of its own through the BackOffice API, tagged
# L<base36 epoch>: N enrolled terminals with a cashier each, a manager (the approver) and a courier, 4 tables per
# terminal (at least 40), a published catalog of --items items at 16 % and 23 % VAT plus a delivery fee item, three
# printers set to HOLD (jobs stay QUEUED: nothing is printed), a fiscal series, a register, a customer with a credit
# limit and an address per terminal, discount limits (15 % free, 50 % max), a published promotion release (20 % off
# every fifth item, at any time) and a delivery zone. Then the N terminals, each with its own PIN session and cash
# session, loop concurrently for --seconds:
#   reads      GET /pos/tables, /pos/catalog, /pos/promotions/current
#   open       a table (4 cycles in 5) or a DELIVERY order with the zone's automatic fee line (1 in 5, R4 D127/D128)
#   rounds     two rounds: drafts, read the order, confirm with catalogVersion, promotionVersion and the preview
#              expectedGross (R4 pricing at confirmation, D103/D104); read the order; print the subtotal (tables)
#   settle     in turn: equal parts in cash | an ITEMS partition with its document, rest in cash | a credit sale |
#              a 10 % manual discount and an approved offer of one unit, then cash (R4 D105/D106) |
#              delivery: board read, dispatch to the courier, delivered, cash on return (R4 D127/D129)
#   close      POST /pos/orders/{id}/close
# while a BackOffice tab reads 1-day reports in turn (sales by item and hour, taxes, receipts by method, R4 margins
# by item, discounts by origin). The random choices use fixed seeds, so runs are comparable.
#
# THRESHOLDS (spec §12: "p95 < 500 ms para comandos locais simples", printing and external calls excluded):
#   gated      every measured /pos and /backoffice call of the cycle above must have p95 < 500 ms, strictly
#              (nearest-rank p95; exactly 500 ms is a MISS). The seed and the logins are not measured.
#   reported   POST /pos/orders/{id}/close, POST /pos/partitions/{id}/documents and POST
#              /pos/orders/{id}/credit-charges are measured and reported but not gated: each issues a fiscal
#              document synchronously through the fiscal adapter port, which is an external call (D48, D54: a
#              certified adapter talks to the tax authority's system; S54 measured close with the
#              non-certified internal adapter, server, database and clients on one laptop: p95 475-544 ms).
#   excluded   printing (tickets, subtotals and documents are queued and printed asynchronously; the load printers
#              are on HOLD) and payment providers (cash and credit only).
# The run FAILS (exit 1) on any non-2xx answer, and, unless --no-gate, on any gated operation whose p95 is not below
# 500 ms. p50/p95/p99/max per operation, throughput, the variant counts and the client are printed and written to
# <report-dir>/load-<UTC>.json.
#
# SAFETY: only against a test or staging installation, NEVER production. A run creates a store, terminals,
# employees, customers, 300 company-wide catalog items, a promotion and real (non-certified test) sales documents,
# which are facts and cannot be deleted (spec §8). The administrator's password is read from the file named by
# LOAD_ADMIN_PASSWORD_FILE (never from the command line or the environment), the account (LOAD_ADMIN_USERNAME,
# default admin) must have completed its first login, and the employees' PINs are random per run and never printed.
#
# MFA (R5, D142): the seed uses sensitive permissions (terminals, employees, catalog publish, the fiscal series, a
# credit limit, the promotion release), which a stack with RESTAURANTPRO_MFA_MODE other than OFF refuses without a
# fresh second factor (403 MFA_ENROLMENT_REQUIRED or MFA_STEP_UP_REQUIRED). This program does not compute TOTP
# codes, so run it against a test or staging stack started with RESTAURANTPRO_MFA_MODE=OFF. The measured loop needs
# no step-up: the POS never asks for a second factor, and the BackOffice tab only reads reports (REPORT_VIEW is not
# sensitive; REPORT_EXPORT, which is, is not called).
#
# Usage: deploy/load/load-smoke.sh [--base-url URL] [--terminals 10] [--seconds 60] [--items 300]
#                                  [--cacert FILE] [--timezone UTC] [--report-dir DIR] [--no-gate]
#                                  [--printer-host 127.0.0.1] [--printer-port 9] [--seed]
#            the run (seed, then load). --base-url defaults to https://restaurantpro.lan; --cacert trusts the
#            installation's CA (Caddy's internal root: see deploy/README.md); --report-dir defaults to
#            deploy/load/reports (git-ignored). --seed is accepted for the S45 command line: every run seeds.
#        deploy/load/load-smoke.sh --dry-run [--base-url URL [--cacert FILE] | --api-docs FILE]
#            checks the plan: every endpoint the run calls exists in the server's /v3/api-docs (logged in as the
#            administrator) or in a saved OpenAPI document. Writes nothing.
#        deploy/load/load-smoke.sh --self-test
#            no server needed: shellcheck of this script (when installed), then the program's own checks (the
#            percentile, the 500 ms gate at its boundary, the plan check against a synthetic OpenAPI document with an
#            endpoint removed, path templates, JSON, options). Exit 0 only when every check passes.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
program="$here/LoadSmoke.java"

require_java() {
    if ! command -v java >/dev/null 2>&1; then
        echo "load-smoke: java (JDK 25) is required" >&2
        exit 2
    fi
    local version
    version="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')"
    if [ -z "$version" ] || [ "${version%%.*}" -lt 25 ]; then
        echo "load-smoke: JDK 25 or later is required (found ${version:-unknown})" >&2
        exit 2
    fi
}

self_test() {
    local failed=0
    if command -v shellcheck >/dev/null 2>&1; then
        if shellcheck "$here/load-smoke.sh"; then
            echo "  ok    shellcheck load-smoke.sh"
        else
            echo "  FAIL  shellcheck load-smoke.sh"
            failed=1
        fi
    else
        echo "  note  shellcheck not installed: the script lint was not run"
    fi
    if ! java "$program" --self-test; then
        failed=1
    fi
    # the wrapper refuses a run without the password file, before any request
    if env -u LOAD_ADMIN_PASSWORD_FILE "$0" --base-url http://127.0.0.1:9 --seconds 1 >/dev/null 2>&1; then
        echo "  FAIL  a run without LOAD_ADMIN_PASSWORD_FILE is refused"
        failed=1
    else
        echo "  ok    a run without LOAD_ADMIN_PASSWORD_FILE is refused"
    fi
    return "$failed"
}

require_java
mode=run
for arg in "$@"; do
    case "$arg" in
        --self-test) mode=self-test ;;
        --dry-run) mode=dry-run ;;
    esac
done

case "$mode" in
    self-test)
        self_test
        ;;
    dry-run)
        exec java "$program" "$@"
        ;;
    run)
        if [ -z "${LOAD_ADMIN_PASSWORD_FILE:-}" ] || [ ! -r "$LOAD_ADMIN_PASSWORD_FILE" ]; then
            echo "load-smoke: set LOAD_ADMIN_PASSWORD_FILE to a readable file holding the administrator's password" >&2
            exit 2
        fi
        report_dir="$here/reports"
        args=("$@")
        for ((i = 0; i < ${#args[@]}; i++)); do
            if [ "${args[$i]}" = "--report-dir" ] && [ $((i + 1)) -lt ${#args[@]} ]; then
                report_dir="${args[$((i + 1))]}"
            fi
        done
        exec java "$program" --report-dir "$report_dir" "$@"
        ;;
esac
