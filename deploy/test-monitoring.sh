#!/usr/bin/env bash
# Monitoring package check (PRD S62, acceptance A64 except the rules-versus-scrape check, which S63 runs against a
# live backend). Needs Docker (for promtool, through the image compose.yaml pins), shellcheck and jq; no secret,
# no running stack, no network beyond pulling that image once. Steps, failing on the first miss:
#   lint        every deploy script is shellcheck-clean (shellcheck -x)
#   compose     docker compose config renders with a throwaway env file, without and with the monitoring profile:
#               the backend gets the frozen R5 env names, the three R5 secrets and ./status read-only (never
#               created by Docker); prometheus is opt-in, pinned to PROM_IMAGE below, internal network only, no
#               published port, with the metrics_token secret and ./monitoring read-only
#   config      promtool check config (prometheus.yml and the rule files it names): scrapes backend:8081
#               /actuator/prometheus every 30 s with authorization.credentials_file = the metrics_token secret
#   rules       promtool check rules and promtool test rules (monitoring/alert-rules.test.yml); one rule per D150 code plus RestaurantProBackendDown; every rule has a
#               severity (critical | warning) and a summary that templates no store label; every metric a rule
#               uses is one of the names frozen in D150 (and every frozen name is used)
#   status      verify-backups.sh in test mode against a fixture (backup directories, and a fake docker on PATH that
#               answers like a healthy postgres container) exits 0 and writes a status file that jq validates
#               against schema 1 (D151): exact keys and types, ISO-8601 UTC instants (the newest archives), both
#               filesystems, ok = no problems, no "/" in any value, mode 0644 in a 0755 directory, no temp file
#   failure     a failing run (no off-box record, archiving off, many malformed files) exits non-zero and still
#               writes "ok": false with path-free problems capped at 21 entries; a run that dies early (missing
#               backup directory), or that is killed by SIGTERM, still writes "ok": false
#
# Usage: deploy/test-monitoring.sh                    the checks (about a minute)
#        MONITORING_FAULT=<step> deploy/test-monitoring.sh
#                                                     the checks with a real violation injected at <step> (a script
#                                                     with a shellcheck finding, a published Prometheus port, an
#                                                     invalid scrape interval, an invalid rule expression, a path in
#                                                     the status file, a failing run that leaves no status file):
#                                                     it must fail at that step
#        deploy/test-monitoring.sh --self-test        the checks, then each fault in turn: every fault must fail at
#                                                     its own step
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
STEPS="lint compose config rules status failure"
PROM_IMAGE="prom/prometheus:v3.13.5" # must equal the prometheus image in compose.yaml (checked in step compose)
# D150: the metric names the rules may use (and must all use), plus up for RestaurantProBackendDown.
FROZEN_METRICS="backup_status_age_seconds backup_last_success_age_seconds backup_problems filesystem_free_ratio
print_jobs_unresolved print_jobs_oldest_waiting_seconds event_publications event_publications_oldest_incomplete_seconds
payment_attempts_in_doubt_overdue cash_closings_with_difference exports_total up"
D150_CODES="BACKUP_MONITOR_STALE BACKUP_LATE BACKUP_PROBLEM DISK_SPACE_LOW PRINT_JOBS_ATTENTION PRINT_QUEUE_STALLED
EVENTS_FAILED EVENTS_BACKLOG PAYMENTS_IN_DOUBT CASH_DISCREPANCY EXPORT_FAILED"
# The R5 env names the backend must receive (PRD r5-stage5, "Frozen configuration names").
FROZEN_ENV="RESTAURANTPRO_MFA_MODE RESTAURANTPRO_MFA_STEP_UP_MAX_AGE RESTAURANTPRO_MFA_KEY_FILE RESTAURANTPRO_LAN_CIDRS
RESTAURANTPRO_OIDC_ENABLED RESTAURANTPRO_OIDC_ISSUER_URI RESTAURANTPRO_OIDC_CLIENT_ID RESTAURANTPRO_OIDC_CLIENT_SECRET_FILE
RESTAURANTPRO_OIDC_DISPLAY_NAME RESTAURANTPRO_OIDC_SCOPES RESTAURANTPRO_OIDC_TRUST_IDP_MFA RESTAURANTPRO_OIDC_POST_LOGIN_URI
RESTAURANTPRO_ALERTS_BACKUP_STATUS_FILE RESTAURANTPRO_ALERTS_CASH_DIFFERENCE_THRESHOLD RESTAURANTPRO_METRICS_TOKEN_FILE"
fault="${MONITORING_FAULT:-}"

usage() { awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0" >&2; }

self_test() {
  local s err status problems=0
  for s in "" $STEPS; do
    status=0
    err="$(MONITORING_FAULT="$s" "$0" 2>&1 >/dev/null)" || status=$?
    if [[ -z "$s" ]]; then
      if ((status == 0)); then
        echo "PASS: self-test: every check passes without a fault" >&2
      else
        echo "FAIL: self-test: a check failed without a fault (exit $status):" >&2
        grep -E '^FAIL' <<<"$err" >&2 || tail -5 <<<"$err" >&2
        problems=$((problems + 1))
      fi
    elif ((status != 0)) && grep -q "^FAIL: step $s:" <<<"$err"; then
      echo "PASS: self-test: MONITORING_FAULT=$s fails at step $s ($(grep -m1 "^FAIL: step $s:" <<<"$err" | cut -c1-140))" >&2
    else
      echo "FAIL: self-test: MONITORING_FAULT=$s was not caught at step $s (exit $status): $(grep -m1 '^FAIL' <<<"$err" || true)" >&2
      problems=$((problems + 1))
    fi
  done
  ((problems == 0))
}

case "${1:-}" in
  "") ;;
  --self-test) if self_test; then echo "PASS: monitoring self-test" >&2; exit 0; else echo "FAIL: monitoring self-test" >&2; exit 1; fi ;;
  -h | --help) usage; exit 0 ;;
  *) usage; exit 2 ;;
esac
if [[ -n "$fault" && " $STEPS " != *" $fault "* ]]; then
  echo "test-monitoring: MONITORING_FAULT must be one of: $STEPS (got: $fault)" >&2
  exit 2
fi
for tool in docker jq shellcheck; do
  command -v "$tool" >/dev/null || { echo "FAIL: step setup: $tool is not installed" >&2; exit 1; }
done

WORK="$(mktemp -d "${TMPDIR:-/tmp}/rp-monitoring.XXXXXX")"
WORK="$(cd "$WORK" && pwd -P)"
trap 'rm -rf "$WORK"' EXIT
CURRENT_STEP=setup
fail() { echo "FAIL: step $CURRENT_STEP: $*" >&2; exit 1; }
ok() { echo "PASS: $CURRENT_STEP: $*" >&2; }
step() { CURRENT_STEP=$1; echo "== $1" >&2; }
faulty() { [[ "$fault" == "$CURRENT_STEP" ]]; }
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
mode_of() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"; }
promtool() { # promtool of the pinned image; extra docker run arguments before --
  local args=()
  while (($#)) && [[ "$1" != -- ]]; do args+=("$1"); shift; done
  shift
  docker run --rm --network none --entrypoint promtool "${args[@]}" "$PROM_IMAGE" "$@"
}

# ---------------------------------------------------------------------------------------------------------------
step lint
scripts=()
for f in "$DEPLOY_DIR"/*.sh "$DEPLOY_DIR"/lib/*.sh "$DEPLOY_DIR"/postgres/*.sh "$DEPLOY_DIR"/load/*.sh; do
  [[ -f "$f" ]] && scripts+=("$f")
done
if faulty; then
  # shellcheck disable=SC2016 # the fault script must hold an unquoted $1 (SC2086)
  printf '#!/usr/bin/env bash\nrm -rf $1/cache\n' >"$WORK/fault.sh"
  scripts+=("$WORK/fault.sh")
fi
out="$(cd "$DEPLOY_DIR" && shellcheck -x "${scripts[@]}" 2>&1)" || fail "shellcheck findings: $(grep -m3 -E "SC[0-9]{4}" <<<"$out" | tr -s " " | tr "\n" " ")"
ok "${#scripts[@]} scripts are shellcheck-clean"

# ---------------------------------------------------------------------------------------------------------------
step compose
# Throwaway env: .env.example as shipped (no secret file needs to exist for config).
cp "$DEPLOY_DIR/.env.example" "$WORK/throwaway.env"
compose_files=(-f "$DEPLOY_DIR/compose.yaml")
if faulty; then
  printf 'services:\n  prometheus:\n    ports: ["9090:9090"]\n' >"$WORK/fault.override.yaml"
  compose_files+=(-f "$WORK/fault.override.yaml")
fi
compose() { docker compose --project-directory "$DEPLOY_DIR" --env-file "$WORK/throwaway.env" "${compose_files[@]}" "$@"; }
plain="$(env -u COMPOSE_PROFILES docker compose --project-directory "$DEPLOY_DIR" --env-file "$WORK/throwaway.env" \
  "${compose_files[@]}" config --services 2>&1)" || fail "docker compose config: $plain"
[[ "$(sort <<<"$plain" | tr '\n' ' ')" == "backend caddy postgres " ]] \
  || fail "without the monitoring profile, expected backend caddy postgres, got: $(tr '\n' ' ' <<<"$plain")"
compose --profile monitoring config --format json >"$WORK/compose.json" 2>"$WORK/compose.err" \
  || fail "docker compose --profile monitoring config: $(cat "$WORK/compose.err")"
[[ "$(jq -r '.services | keys | join(" ")' "$WORK/compose.json")" == "backend caddy postgres prometheus" ]] \
  || fail "with the monitoring profile, expected backend caddy postgres prometheus"
for name in $FROZEN_ENV; do
  jq -e --arg n "$name" '.services.backend.environment | has($n)' "$WORK/compose.json" >/dev/null \
    || fail "the backend does not receive $name"
done
problem="$(jq -r --arg img "$PROM_IMAGE" '
  .services.backend as $b | .services.prometheus as $p |
  [ (if $b.environment.RESTAURANTPRO_MFA_KEY_FILE != "/run/secrets/mfa_key" then "MFA key file is not the mfa_key secret" else empty end),
    (if $b.environment.RESTAURANTPRO_METRICS_TOKEN_FILE != "/run/secrets/metrics_token" then "metrics token file is not the metrics_token secret" else empty end),
    (if $b.environment.RESTAURANTPRO_OIDC_CLIENT_SECRET_FILE != "/run/secrets/oidc_client_secret" then "OIDC client secret file is not the oidc_client_secret secret" else empty end),
    (if $b.environment.RESTAURANTPRO_ALERTS_BACKUP_STATUS_FILE != "/run/restaurantpro/status/backup-status.json" then "the backup status file path is not the D151 one" else empty end),
    (if $b.environment.RESTAURANTPRO_OIDC_ENABLED != "false" then "OIDC is not off by default" else empty end),
    (if $b.environment.RESTAURANTPRO_MFA_MODE != "SENSITIVE" then ".env.example does not set MFA mode SENSITIVE (P52)" else empty end),
    (if ([$b.secrets[].source] | index("mfa_key") == null or index("metrics_token") == null or index("oidc_client_secret") == null)
       then "the backend lacks one of the secrets mfa_key, metrics_token, oidc_client_secret" else empty end),
    (if ([$b.volumes[]? | select(.target == "/run/restaurantpro/status" and .read_only == true and .bind.create_host_path == false
           and (.source | endswith("/status")))] | length) != 1
       then "the backend does not mount ./status read-only at /run/restaurantpro/status (without create_host_path)" else empty end),
    (if ($b.volumes | length) != 1 then "the backend mounts more than the status directory" else empty end),
    (if $p.image != $img then "prometheus is not pinned to \($img): \($p.image)" else empty end),
    (if ($p.profiles // []) != ["monitoring"] then "prometheus is not in the monitoring profile only" else empty end),
    (if ($p.networks | keys) != ["internal"] then "prometheus is on another network than internal: \($p.networks | keys)" else empty end),
    (if ($p.ports // []) != [] then "prometheus publishes a port" else empty end),
    (if ([$p.secrets[].source] != ["metrics_token"]) then "prometheus has other secrets than metrics_token" else empty end),
    (if ([$p.volumes[] | select(.target == "/etc/prometheus" and .read_only == true and (.source | endswith("/monitoring")))] | length) != 1
       then "prometheus does not mount ./monitoring read-only at /etc/prometheus" else empty end),
    (if ([$p.volumes[] | select(.type == "volume" and .source == "prometheus-data" and .target == "/prometheus")] | length) != 1
       then "prometheus does not keep its data in the prometheus-data volume" else empty end),
    (if (.services | to_entries | map(select(.key != "prometheus" and (.value.image // "" | startswith("prom/")))) | length) != 0
       then "another service runs a Prometheus image" else empty end),
    (if (.services | has("alertmanager")) then "an alertmanager service ships (P58: none)" else empty end)
  ] | first // empty' "$WORK/compose.json")"
[[ -z "$problem" ]] || fail "$problem"
# Defaults when .env sets none of the R5 names (an upgraded installation): MFA OFF, OIDC off, the D140 LAN list.
grep -vE '^[[:space:]]*(RESTAURANTPRO_(MFA|LAN|OIDC|ALERTS)_|COMPOSE_PROFILES)' "$DEPLOY_DIR/.env.example" >"$WORK/upgraded.env"
docker compose --project-directory "$DEPLOY_DIR" --env-file "$WORK/upgraded.env" -f "$DEPLOY_DIR/compose.yaml" config --format json \
  >"$WORK/upgraded.json" 2>"$WORK/compose.err" || fail "docker compose config with an R4 .env: $(cat "$WORK/compose.err")"
jq -e '.services.backend.environment | .RESTAURANTPRO_MFA_MODE == "OFF" and .RESTAURANTPRO_OIDC_ENABLED == "false"
  and .RESTAURANTPRO_LAN_CIDRS == "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7,fe80::/10"
  and .RESTAURANTPRO_ALERTS_CASH_DIFFERENCE_THRESHOLD == "0" and .RESTAURANTPRO_MFA_STEP_UP_MAX_AGE == "PT15M"' \
  "$WORK/upgraded.json" >/dev/null || fail "the defaults for an .env without the R5 names are not OFF / false / the D140 list / 0 / PT15M"
ok "renders without and with the monitoring profile; R5 env names, secrets and the read-only status mount wired; prometheus pinned, internal, unpublished"

# ---------------------------------------------------------------------------------------------------------------
step config
cp -R "$DEPLOY_DIR/monitoring" "$WORK/monitoring"
chmod -R a+rX "$WORK/monitoring"
if faulty; then sed -i.bak 's/^  scrape_interval: 30s$/  scrape_interval: thirty/' "$WORK/monitoring/prometheus.yml"; fi
openssl rand -hex 32 >"$WORK/metrics_token" # throwaway; promtool checks that the credentials file exists
chmod 644 "$WORK/metrics_token"
out="$(promtool -v "$WORK/monitoring:/etc/prometheus:ro" -v "$WORK/metrics_token:/run/secrets/metrics_token:ro" \
  -- check config /etc/prometheus/prometheus.yml 2>&1)" || fail "promtool check config: $out"
cfg="$WORK/monitoring/prometheus.yml"
grep -qE '^[[:space:]]+- targets: \["backend:8081"\]$' "$cfg" || fail "prometheus.yml does not scrape backend:8081"
grep -qE '^[[:space:]]+metrics_path: /actuator/prometheus$' "$cfg" || fail "prometheus.yml does not scrape /actuator/prometheus"
grep -qE '^[[:space:]]+credentials_file: /run/secrets/metrics_token$' "$cfg" || fail "prometheus.yml does not read the token from the metrics_token secret"
grep -qE '^[[:space:]]+scrape_interval: 30s$' "$cfg" || fail "prometheus.yml does not scrape every 30 s"
grep -qE '^[[:space:]]+- /etc/prometheus/alert-rules.yml$' "$cfg" || fail "prometheus.yml does not load alert-rules.yml"
ok "$(grep -m1 SUCCESS <<<"$out" | sed 's/^ *//'); scrape target, path, interval and token file as D153"

# ---------------------------------------------------------------------------------------------------------------
step rules
rules="$WORK/monitoring/alert-rules.yml"
if faulty; then sed -i.bak 's/^        expr: backup_problems > 0$/        expr: backup_problems >/' "$rules"; fi
out="$(promtool -v "$WORK/monitoring:/etc/prometheus:ro" -- check rules /etc/prometheus/alert-rules.yml 2>&1)" \
  || fail "promtool check rules: $out"
out="$(promtool -v "$WORK/monitoring:/etc/prometheus:ro" -- test rules /etc/prometheus/alert-rules.test.yml 2>&1)" \
  || fail "promtool test rules: $(grep -vE '^\s*$' <<<"$out" | head -12 | tr '\n' ' ')"
# One record per rule: alert<TAB>severity<TAB>code<TAB>summary<TAB>expr (multi-line expressions joined).
awk '
  function flush() { if (alert != "") printf "%s\t%s\t%s\t%s\t%s\n", alert, sev, code, summary, expr; alert = sev = code = summary = expr = ""; inexpr = 0 }
  /^      - alert: / { flush(); alert = $3; next }
  /^        expr: >-$/ { inexpr = 1; next }
  /^        expr: / { sub(/^        expr: /, ""); expr = $0; next }
  inexpr && /^          / { sub(/^ +/, ""); expr = expr " " $0; next }
  { inexpr = 0 }
  /^          severity: / { sev = $2 }
  /^          code: / { code = $2 }
  /^          summary: / { sub(/^          summary: /, ""); summary = $0 }
  END { flush() }' "$rules" >"$WORK/rules.tsv"
[[ "$(wc -l <"$WORK/rules.tsv" | tr -d ' ')" == "$(grep -c '^      - alert: ' "$rules")" ]] || fail "could not read every rule"
while IFS=$'\t' read -r alert sev code summary expr; do
  [[ "$sev" == critical || "$sev" == warning ]] || fail "$alert has no severity critical or warning: '$sev'"
  [[ -n "$summary" ]] || fail "$alert has no summary"
  # shellcheck disable=SC2016 # literal template text
  [[ "$summary" != *'$labels.store'* && "$summary" != *'$labels.id'* ]] || fail "$alert names an id in its summary"
  [[ -n "$code" ]] || fail "$alert has no code label"
  [[ -n "$expr" ]] || fail "$alert has no expression"
done <"$WORK/rules.tsv"
for code in $D150_CODES; do
  awk -F'\t' -v c="$code" '$3 == c { f = 1 } END { exit !f }' "$WORK/rules.tsv" || fail "no rule for the D150 code $code"
done
awk -F'\t' '$1 == "RestaurantProBackendDown" && $5 ~ /^up\{job="restaurantpro-backend"\} == 0$/ { f = 1 } END { exit !f }' "$WORK/rules.tsv" \
  || fail "no RestaurantProBackendDown rule on up == 0"
grep -A3 'alert: RestaurantProBackendDown' "$rules" | grep -q 'for: 2m' || fail "RestaurantProBackendDown does not wait 2 minutes"
# Metric names: strip strings, label matchers, ranges, grouping clauses, function calls, keywords and numbers.
cut -f5 "$WORK/rules.tsv" | sed -E 's/"[^"]*"//g; s/\{[^}]*\}//g; s/\[[^]]*\]//g; s/(by|without|on|ignoring|group_left|group_right)[[:space:]]*\([^)]*\)//g' \
  | sed -E 's/[a-z_]+[[:space:]]*\(/ /g' | tr -c 'a-zA-Z0-9_:' '\n' | grep -E '^[a-zA-Z_:][a-zA-Z0-9_:]*$' \
  | grep -vxE 'and|or|unless|bool|offset' | sort -u >"$WORK/used.txt"
tr ' ' '\n' <<<"$FROZEN_METRICS" | grep -v '^$' | sort -u >"$WORK/frozen.txt"
unknown="$(comm -23 "$WORK/used.txt" "$WORK/frozen.txt" | tr '\n' ' ')"
[[ -z "$unknown" ]] || fail "the rules use metrics outside the D150 table: $unknown"
unused="$(comm -13 "$WORK/used.txt" "$WORK/frozen.txt" | tr '\n' ' ')"
[[ -z "$unused" ]] || fail "D150 metrics no rule uses: $unused"
ok "check rules and test rules pass; every D150 code, severities, summaries, and only the $(wc -l <"$WORK/frozen.txt" | tr -d ' ') frozen metric names"

# ---------------------------------------------------------------------------------------------------------------
# The fixture: backup.sh and basebackup.sh directories with fake ciphertext and sidecars (key-free checks only
# look at the age header and the SHA-256), and a docker on PATH that answers like a healthy postgres container.
fake_age() { # $1 path of x.age: a file with the age header, and its x.sha256 sidecar
  local f=$1 base name
  base="${f%.age}"
  name="$(basename "$base")"
  printf 'plaintext of %s\n' "$name" >"$WORK/plain"
  printf 'age-encryption.org/v1\n-> X25519 fixture\n%s\n' "$name" >"$f"
  printf '%s  %s\n%s  %s\n' "$(sha "$WORK/plain")" "$name" "$(sha "$f")" "$name.age" >"$base.sha256"
}
make_fixture() { # $1 root; fills $1/backups and $1/docker-root
  local b="$1/backups"
  mkdir -p "$b/pitr/base" "$b/pitr/wal" "$1/docker-root"
  fake_age "$b/restaurantpro-20261110T030000Z.tar.age"
  fake_age "$b/restaurantpro-20261111T030000Z.tar.age"
  fake_age "$b/pitr/base/restaurantpro-base-20261110T040000Z-7000000000000000001-000000010000000000000001.tar.age"
  fake_age "$b/pitr/base/restaurantpro-base-20261111T040000Z-7000000000000000001-000000010000000000000003.tar.age"
  fake_age "$b/pitr/wal/000000010000000000000001.age"
  fake_age "$b/pitr/wal/000000010000000000000002.age"
  fake_age "$b/pitr/wal/000000010000000000000003.age"
  printf '%s %s\n' "$(date -u +%s)" "2026-11-12T10:00:00Z" >"$b/LAST_OFFBOX_SYNC"
}
mkdir -p "$WORK/bin"
cat >"$WORK/bin/docker" <<'SH'
#!/usr/bin/env bash
# Fake docker for the status-file fixture: a healthy postgres container (FAKE_ARCHIVE_MODE may say off).
case "${1:-}" in
  info) printf '%s\n' "$FAKE_DOCKER_ROOT" ;;
  exec)
    shift 2
    case "$1" in
      psql)
        sql="${!#}"
        case "$sql" in
          "SHOW archive_mode")
            # FAKE_HANG_PIDFILE: hang here (the pid is this process, the sleep after exec) until the test kills it.
            if [[ -n "${FAKE_HANG_PIDFILE:-}" ]]; then echo "$$" >"$FAKE_HANG_PIDFILE"; exec sleep 300; fi
            echo "${FAKE_ARCHIVE_MODE:-on}" ;;
          *archive_timeout*) echo t ;;
          *last_failed_wal*) ;;
          *failed_count*) echo 0 ;;
          *pg_ls_archive_statusdir*) echo 0 ;;
          *) echo "fake docker: unexpected SQL: $sql" >&2; exit 1 ;;
        esac ;;
      stat) echo "700 postgres" ;;
      bash | find | sh) ;; # the WAL volume is empty
      *) echo "fake docker: unexpected exec: $*" >&2; exit 1 ;;
    esac ;;
  *) echo "fake docker: unexpected: $*" >&2; exit 1 ;;
esac
SH
chmod 755 "$WORK/bin/docker"
verify() { # $1 fixture root, then verify-backups.sh arguments; test mode, no recipients, the fake docker
  local root=$1
  shift
  env PATH="$WORK/bin:$PATH" FAKE_DOCKER_ROOT="$root/docker-root" RESTAURANTPRO_ENVIRONMENT=test \
    BACKUP_AGE_RECIPIENTS_FILE=/dev/null "$DEPLOY_DIR/verify-backups.sh" --container fake-postgres "$@"
}
# Prints what is wrong with status file $1 against schema 1 (D151); nothing when it is valid.
status_problem() {
  local f=$1
  [[ -f "$f" ]] || { echo "no status file was written"; return; }
  jq -e . "$f" >/dev/null 2>&1 || { echo "the status file is not JSON"; return; }
  jq -r '
    def iso: type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?Z$");
    def count: type == "number" and . >= 0 and . == floor;
    [ (if (keys != (["checkedAt", "filesystems", "lastBaseBackupAt", "lastLogicalBackupAt", "lastOffboxSyncAt", "ok", "problems", "schemaVersion"]))
         then "keys are \(keys)" else empty end),
      (if .schemaVersion != 1 then "schemaVersion is not 1" else empty end),
      (if (.checkedAt | iso | not) then "checkedAt is not an ISO-8601 UTC instant" else empty end),
      (if (.ok | type) != "boolean" then "ok is not a boolean" else empty end),
      (.lastLogicalBackupAt, .lastBaseBackupAt, .lastOffboxSyncAt | select(. != null and (iso | not)) | "an instant is not ISO-8601 UTC: \(.)"),
      (if (.problems | type) != "array" or (.problems | map(type == "string") | all | not) then "problems is not an array of strings" else empty end),
      (if .ok != (.problems | length == 0) then "ok does not match the problems" else empty end),
      (if (.filesystems | type) != "array" then "filesystems is not an array" else empty end),
      (.filesystems[]? | select((keys != ["freeBytes", "name", "totalBytes"]) or (.name | IN("backups", "docker") | not)
         or (.totalBytes | count | not) or (.freeBytes | count | not) or .freeBytes > .totalBytes) | "a filesystem entry is malformed: \(.)"),
      (if ([.filesystems[]?.name] | length) != ([.filesystems[]?.name] | unique | length) then "a filesystem is listed twice" else empty end),
      ([.. | strings | select(contains("/"))] | .[] | "a value holds a path: \(.)")
    ] | first // empty' "$f"
  [[ "$(mode_of "$f")" == 644 ]] || echo "the status file is mode $(mode_of "$f"), not 0644"
  [[ "$(mode_of "$(dirname "$f")")" == 755 ]] || echo "the status directory is mode $(mode_of "$(dirname "$f")"), not 0755"
  [[ -z "$(find "$(dirname "$f")" -name '*.tmp.*')" ]] || echo "a temporary file is left in the status directory"
}

# ---------------------------------------------------------------------------------------------------------------
step status
make_fixture "$WORK/ok"
status_file="$WORK/ok/status/backup-status.json"
verify "$WORK/ok" --dir "$WORK/ok/backups" --status-file "$status_file" 2>"$WORK/verify.err" \
  || fail "verify-backups.sh failed on a healthy fixture: $(grep PROBLEM "$WORK/verify.err" | head -3)"
if faulty; then
  jq '.ok = false | .problems = ["the newest base backup in /srv/restaurantpro/backups is late"]' "$status_file" >"$WORK/x.json"
  cat "$WORK/x.json" >"$status_file"
fi
problem="$(status_problem "$status_file" | head -1)"
[[ -z "$problem" ]] || fail "$problem"
jq -e '.ok == true and .problems == [] and .lastLogicalBackupAt == "2026-11-11T03:00:00Z"
  and .lastBaseBackupAt == "2026-11-11T04:00:00Z" and .lastOffboxSyncAt == "2026-11-12T10:00:00Z"
  and ([.filesystems[].name] | sort) == ["backups", "docker"]' "$status_file" >/dev/null \
  || fail "unexpected content for a healthy fixture: $(jq -c . "$status_file")"
# An existing status directory keeps its mode and owner (the installer's chown); only a new one is made 0755.
mkdir -m 750 "$WORK/ok/kept"
verify "$WORK/ok" --dir "$WORK/ok/backups" --status-file "$WORK/ok/kept/backup-status.json" 2>"$WORK/verify.err" \
  || fail "verify-backups.sh failed on a healthy fixture with an existing status directory"
[[ "$(mode_of "$WORK/ok/kept")" == 750 ]] || fail "verify-backups.sh changed an existing status directory to mode $(mode_of "$WORK/ok/kept")"
[[ "$(mode_of "$WORK/ok/kept/backup-status.json")" == 644 ]] || fail "the status file in an existing directory is not 0644"
ok "a healthy run writes a schema-1 status file: $(jq -c '{ok, lastLogicalBackupAt, lastBaseBackupAt, lastOffboxSyncAt, filesystems: [.filesystems[].name]}' "$status_file")"

# ---------------------------------------------------------------------------------------------------------------
step failure
make_fixture "$WORK/bad"
status_file="$WORK/bad/status/backup-status.json"
rm "$WORK/bad/backups/LAST_OFFBOX_SYNC"
status=0
FAKE_ARCHIVE_MODE=off verify "$WORK/bad" --dir "$WORK/bad/backups" --status-file "$status_file" 2>"$WORK/verify.err" || status=$?
((status != 0)) || fail "verify-backups.sh exited 0 on a failing fixture"
if faulty; then rm -f "$status_file"; fi
problem="$(status_problem "$status_file" | head -1)"
[[ -z "$problem" ]] || fail "after a failing run: $problem"
jq -e '.ok == false and .lastOffboxSyncAt == null and .lastLogicalBackupAt == "2026-11-11T03:00:00Z"
  and .problems == ["archive_mode is not on", "no off-box copy recorded: LAST_OFFBOX_SYNC is missing (offbox-sync.sh)"]' \
  "$status_file" >/dev/null || fail "unexpected content after a failing run: $(jq -c . "$status_file")"
grep -q "PROBLEM: no off-box copy recorded: $WORK/bad/backups/LAST_OFFBOX_SYNC is missing" "$WORK/verify.err" \
  || fail "the operator output lost the full path of a problem"
ok "a failing run (exit $status) writes ok false with path-free problems: $(jq -c .problems "$status_file")"
for i in $(seq 1 25); do printf 'bad\n' >"$WORK/bad/backups/pitr/malformed-$i.sha256"; done
status=0
verify "$WORK/bad" --dir "$WORK/bad/backups" --status-file "$status_file" 2>"$WORK/verify.err" || status=$?
((status != 0)) || fail "verify-backups.sh exited 0 with 25 malformed files"
problem="$(status_problem "$status_file" | head -1)"
[[ -z "$problem" ]] || fail "after a run with many problems: $problem"
jq -e '.ok == false and (.problems | length) == 21 and .problems[0] == "backups: malformed sidecar: malformed-1.sha256"
  and .problems[20] == "and 6 more problem(s): see the output of verify-backups.sh"' "$status_file" >/dev/null \
  || fail "unexpected content after a run with 26 problems: $(jq -c . "$status_file" | cut -c1-400)"
ok "26 problems are capped at 20 plus a count of the rest"
status=0
verify "$WORK/bad" --dir "$WORK/bad/missing" --status-file "$status_file" 2>"$WORK/verify.err" || status=$?
((status != 0)) || fail "verify-backups.sh exited 0 without a backup directory"
problem="$(status_problem "$status_file" | head -1)"
[[ -z "$problem" ]] || fail "after an early exit: $problem"
jq -e '.ok == false and .problems == ["backup directory not found: missing"] and .lastLogicalBackupAt == null' "$status_file" >/dev/null \
  || fail "unexpected content after an early exit: $(jq -c . "$status_file")"
ok "a run that dies early (exit $status) still writes ok false: $(jq -c .problems "$status_file")"
# A run killed by a signal (the cron's timeout, a reboot) on an otherwise healthy fixture: never "ok": true.
status_file="$WORK/ok/status-term/backup-status.json"
hang="$WORK/hang.pid"
env PATH="$WORK/bin:$PATH" FAKE_DOCKER_ROOT="$WORK/ok/docker-root" FAKE_HANG_PIDFILE="$hang" RESTAURANTPRO_ENVIRONMENT=test \
  BACKUP_AGE_RECIPIENTS_FILE=/dev/null "$DEPLOY_DIR/verify-backups.sh" --container fake-postgres --dir "$WORK/ok/backups" \
  --status-file "$status_file" 2>"$WORK/verify.err" &
vpid=$!
for _ in $(seq 1 300); do [[ -s "$hang" ]] && break; sleep 0.1; done # until the fake docker hangs
[[ -s "$hang" ]] || { kill -KILL "$vpid" 2>/dev/null || true; fail "the run never reached the hanging fake docker"; }
kill -TERM "$vpid"
kill -TERM "$(cat "$hang")" 2>/dev/null || true # the hanging child, so the shell can act on the signal
status=0
wait "$vpid" || status=$?
((status != 0)) || fail "verify-backups.sh exited 0 after SIGTERM"
problem="$(status_problem "$status_file" | head -1)"
[[ -z "$problem" ]] || fail "after SIGTERM: $problem"
jq -e '.ok == false and (.problems | length) > 0' "$status_file" >/dev/null \
  || fail "a run killed by SIGTERM wrote: $(jq -c '{ok, problems}' "$status_file")"
ok "a run killed by SIGTERM (exit $status) writes ok false: $(jq -c .problems "$status_file")"

echo "PASS: monitoring checks${fault:+ (MONITORING_FAULT=$fault was not caught)}" >&2
