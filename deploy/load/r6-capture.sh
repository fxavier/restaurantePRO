#!/usr/bin/env bash
# The capture side of the A92 measurement (S77 design §4, deploy/load/r6-load-plan.md "Measuring A92"). It does not
# generate load: the load client runs on its own host; this records what the design asks to capture around each
# phase, from the database and the backend's metrics.
#
#   r6-capture.sh rtt    --db URI                         app-to-database round trip: median of 1000 `SELECT 1` on
#                                                          one session (run it ON THE APPLICATION HOST)
#   r6-capture.sh start  --db URI --metrics URL --out DIR  before a phase: resets pg_stat_statements, snapshots
#                                                          pg_stat_database and db_transactions_total{kind}
#   r6-capture.sh sample --db URI --metrics URL --out DIR --seconds N
#                                                          during a phase, every 2 s: the application role's backends
#                                                          by state and wait event, Hikari active/idle/pending, this
#                                                          host's load average per CPU (run it on the LOAD HOST too:
#                                                          a valid run keeps that below 0.5)
#   r6-capture.sh finish --db URI --metrics URL --out DIR  after a phase: top 20 statements by total_exec_time,
#                                                          transactions committed and rolled back since start, and
#                                                          db_transactions_total by origin since start
#
# --db is a libpq URI of a role allowed to read pg_stat_activity of every role and to reset pg_stat_statements (the
# superuser or pg_monitor plus EXECUTE on pg_stat_statements_reset); its password comes from PGPASSFILE, never the
# command line. --metrics is the management port's /actuator/prometheus; its bearer token is read from the file named
# by METRICS_TOKEN_FILE (secrets/metrics_token on a stack), never printed. pg_stat_statements must be in
# shared_preload_libraries (otherwise `start` and `finish` say so and skip it). Requires psql, curl, awk.
#
# SAFETY: `start` resets the statement statistics of the whole database server. Only against a test or staging
# installation, never production.
set -euo pipefail

usage() { sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

command=${1:-}
[ -n "$command" ] || usage
shift
db='' metrics='' out='' seconds=60
while [ $# -gt 0 ]; do
    case "$1" in
        --db) db=$2; shift 2 ;;
        --metrics) metrics=$2; shift 2 ;;
        --out) out=$2; shift 2 ;;
        --seconds) seconds=$2; shift 2 ;;
        *) usage ;;
    esac
done
[ -n "$db" ] || usage

sql() { psql "$db" -X -q -A -t -v ON_ERROR_STOP=1 -c "$1"; }

has_statements() {
    [ "$(sql "SELECT count(*) FROM pg_extension WHERE extname = 'pg_stat_statements'")" = 1 ]
}

# The metrics page. The token goes to curl in a header file only this user can read (-H @file), never on its argv,
# where any local user could read it in the process list; the file is removed after the call.
scrape() {
    if [ -z "${METRICS_TOKEN_FILE:-}" ]; then
        curl -fsS "$metrics"
        return
    fi
    local header status=0
    header=$(umask 077 && mktemp)
    { printf 'Authorization: Bearer '; tr -d '\r\n' < "$METRICS_TOKEN_FILE"; printf '\n'; } > "$header"
    curl -fsS -H @"$header" "$metrics" || status=$?
    rm -f "$header"
    return "$status"
}

# db_transactions_total{kind} as "kind value" lines (the backend's S77 counter)
transactions_by_origin() {
    scrape | awk '
        /^db_transactions_total\{/ {
            match($0, /kind="[^"]*"/); origin = substr($0, RSTART + 6, RLENGTH - 7)
            total[origin] += $NF
        }
        END { for (o in total) printf "%s %d\n", o, total[o] }' | sort
}

hikari() {
    scrape | awk '
        /^hikaricp_connections_(active|idle|pending)\{/ {
            name = $1; sub(/\{.*/, "", name); sub(/hikaricp_connections_/, "", name); value[name] += $NF
        }
        END { printf "%d,%d,%d", value["active"], value["idle"], value["pending"] }'
}

load_per_cpu() {
    local cpus load
    cpus=$(getconf _NPROCESSORS_ONLN)
    load=$(uptime | awk -F'load averages?: ' '{ split($2, l, /[, ]+/); print l[1] }')
    awk -v l="$load" -v c="$cpus" 'BEGIN { printf "%.2f", l / c }'
}

need_out() { [ -n "$out" ] || usage; mkdir -p "$out"; }

case "$command" in
    rtt)
        script=$(mktemp)
        trap 'rm -f "$script"' EXIT
        { printf '%s\n' '\timing on'; for _ in $(seq 1000); do printf 'SELECT 1;\n'; done; } > "$script"
        psql "$db" -X -q -f "$script" | awk '/^Time:/ { print $2 }' | sort -n \
            | awk '{ t[NR] = $1 } END { printf "app-db RTT median %.3f ms over %d round trips\n", t[int((NR + 1) / 2)], NR }'
        ;;
    start)
        need_out
        [ -n "$metrics" ] || usage
        if has_statements; then sql "SELECT pg_stat_statements_reset()" > /dev/null; else
            echo "pg_stat_statements is not installed: no statement statistics this phase" >&2; fi
        sql "SELECT xact_commit, xact_rollback FROM pg_stat_database WHERE datname = current_database()" \
            > "$out/database-start.txt"
        transactions_by_origin > "$out/transactions-start.txt"
        date -u +%FT%TZ > "$out/started-at.txt"
        echo "phase started; snapshots in $out"
        ;;
    sample)
        need_out
        [ -n "$metrics" ] || usage
        file="$out/samples.csv"
        [ -f "$file" ] || echo "utc,load_per_cpu,hikari_active,hikari_idle,hikari_pending,app_backends_by_state_and_wait" \
            > "$file"
        end=$(( $(date +%s) + seconds ))
        while [ "$(date +%s)" -lt "$end" ]; do
            states=$(sql "SELECT coalesce(string_agg(s || '=' || n, ' '), '') FROM (
                            SELECT state || '/' || coalesce(wait_event_type || ':' || wait_event, '-') AS s, count(*) AS n
                            FROM pg_stat_activity WHERE usename = 'restaurantpro_app' AND state <> 'idle'
                            GROUP BY 1 ORDER BY 2 DESC) t")
            echo "$(date -u +%FT%TZ),$(load_per_cpu),$(hikari),$states" >> "$file"
            sleep 2
        done
        awk -F, 'NR > 1 && $2 > max { max = $2 } END { printf "max load per CPU on this host: %.2f%s\n", max,
            (max >= 0.5 ? " (a load host this busy makes the run invalid)" : "") }' "$file"
        ;;
    finish)
        need_out
        [ -n "$metrics" ] || usage
        if has_statements; then
            sql "SELECT round(total_exec_time::numeric, 1) || ' ms | ' || calls || ' calls | '
                        || regexp_replace(left(query, 160), '\s+', ' ', 'g')
                 FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 20" > "$out/top-statements.txt"
        fi
        read -r commit0 rollback0 < <(tr '|' ' ' < "$out/database-start.txt")
        read -r commit1 rollback1 < <(sql "SELECT xact_commit, xact_rollback FROM pg_stat_database
                                           WHERE datname = current_database()" | tr '|' ' ')
        transactions_by_origin > "$out/transactions-finish.txt"
        {
            echo "since $(cat "$out/started-at.txt"), until $(date -u +%FT%TZ)"
            echo "pg_stat_database: $((commit1 - commit0)) committed, $((rollback1 - rollback0)) rolled back"
            echo "db_transactions_total by origin:"
            join -a 2 -e 0 -o 0,1.2,2.2 "$out/transactions-start.txt" "$out/transactions-finish.txt" \
                | awk '{ printf "  %-28s %d\n", $1, $3 - $2 }'
        } | tee "$out/summary.txt"
        ;;
    *) usage ;;
esac
