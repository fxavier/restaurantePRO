#!/usr/bin/env bash
# Two-installation isolation drill (PRD S44, acceptance A55, spec §14 #1): "Instalar empresas A e B com
# processos, base, volumes e segredos separados; credenciais de A não abrem B e restauro de A não altera B."
#
# Brings up the Compose projects rp-a and rp-b from deploy/compose.yaml with their own env files
# (drills/isolation/a.env, b.env), secret directories (drills/isolation/compose.isolation.yaml) and age recipients:
# production mode (D98), throwaway keys and credentials generated per run, never printed. Then, failing on
# the first miss:
#   separation   containers, networks and volumes belong to one project only; no container shares another's
#                network, PID or IPC namespace; no secret, recipients file or writable path is mounted in both
#                (the read-only deploy/ scripts and Caddyfile are the only shared mounts)
#   credentials  A's admin password and A's session cookie get 401 on B, and the reverse; A's database password
#                fails authentication on B's PostgreSQL, and the reverse (each with a positive control); A's
#                Caddy root certificate does not verify B
#   network      B's backend cannot resolve A's database container (getent) nor connect to it (a TCP probe in
#                the backend's network namespace), while it reaches its own
#   restore      back up A with backup.sh, change A, restore A with restore.sh --force (A's operator
#                environment: COMPOSE_PROJECT_NAME and COMPOSE_ENV_FILES, no --container): A matches its backup;
#                B's pg_dump checksum and per-table fingerprint are unchanged
#   tooling      A's operator environment resolves to A's database only; pitr-restore.sh --force refuses B's
#                container and volume by their Compose label; restore.sh (with and without --force) and backup.sh
#                refuse B's container from A's environment, unless --other-installation names exactly rp-b
#                (then with a WARNING; restore.sh without --force still stops at B's non-empty database); A's key
#                cannot decrypt B's backup; backup.sh with A's recipients refuses B's backup directory; B unchanged
#   teardown     both stacks down with their volumes; no container, volume or network of either is left; the
#                secret, key and backup directories are removed
# Writes drills/isolation/drill-isolation-<UTC>.json (result, failed step, one line per check) and prints its
# path on stdout. Exits non-zero on any violation. The EXIT trap removes everything the run created, whatever
# happened; the images restaurantpro-postgres:18.6-r1 and restaurantpro-backend:isolation-drill stay, as a
# build cache.
#
# Usage: deploy/test-two-installations.sh              the drill (needs Docker, curl, openssl; ports 18443,
#                                                      18080, 28443, 28080 free; about 3-5 minutes)
#        ISOLATION_FAULT=<step> deploy/test-two-installations.sh
#                                                      the drill with a real violation injected at <step>
#                                                      (B gets A's secret directory, B gets A's credentials,
#                                                      B's backend joins A's network, A's archive is restored
#                                                      into B with --other-installation, A's operator environment names B, A's volumes
#                                                      are kept): it must fail at that step
#        deploy/test-two-installations.sh --self-test  shellcheck, the drill, then each fault in turn: every
#                                                      fault must fail at its own step and leave nothing behind
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ISO_DIR="$DEPLOY_DIR/drills/isolation"
STEPS="separation credentials network restore tooling teardown"
INSTALLATIONS="a b"
DB=restaurantpro
DB_USER=restaurantpro
PG_IMAGE="restaurantpro-postgres:18.6-r1" # compose.yaml's postgres image (D4 pin + age), also the probe image
CADDY_ROOT=/data/caddy/pki/authorities/local/root.crt
fault="${ISOLATION_FAULT:-}"

usage() { awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0" >&2; }
project_objects() { # $1 installation: every container, volume and network of its Compose project
  local label="label=com.docker.compose.project=rp-$1"
  docker ps -aq --filter "$label"
  docker volume ls -q --filter "$label"
  docker network ls -q --filter "$label"
}

self_test() {
  local s out report status problems=0
  command -v shellcheck >/dev/null || { echo "FAIL: self-test: shellcheck is not installed" >&2; return 1; }
  if (cd "$DEPLOY_DIR" && shellcheck -x test-two-installations.sh); then
    echo "PASS: self-test: shellcheck clean" >&2
  else
    echo "FAIL: self-test: shellcheck" >&2; return 1
  fi
  for s in "" $STEPS; do
    status=0
    out="$(ISOLATION_FAULT="$s" "$0")" || status=$?
    report="$(tail -1 <<<"$out")"
    [[ -f "$report" ]] || { echo "FAIL: self-test: fault '${s:-none}' wrote no report (exit $status)" >&2; problems=$((problems + 1)); continue; }
    if [[ -z "$s" ]]; then
      if ((status == 0)) && grep -q '"result": "PASS"' "$report"; then
        echo "PASS: self-test: the drill passes without a fault" >&2
      else
        echo "FAIL: self-test: the drill failed without a fault (exit $status, $report)" >&2; problems=$((problems + 1))
      fi
    elif ((status != 0)) && grep -q "\"failed_step\": \"$s\"" "$report"; then
      echo "PASS: self-test: ISOLATION_FAULT=$s fails the drill at step $s (exit $status)" >&2
    else
      echo "FAIL: self-test: ISOLATION_FAULT=$s was not caught at step $s (exit $status, $report)" >&2; problems=$((problems + 1))
    fi
    if [[ -n "$(project_objects a)$(project_objects b)" ]]; then
      echo "FAIL: self-test: fault '${s:-none}' left Docker objects of rp-a or rp-b behind" >&2; problems=$((problems + 1))
    fi
  done
  ((problems == 0))
}

case "${1:-}" in
  "") ;;
  --self-test) if self_test; then echo "PASS: two-installation drill self-test" >&2; exit 0; else echo "FAIL: two-installation drill self-test" >&2; exit 1; fi ;;
  -h | --help) usage; exit 0 ;;
  *) usage; exit 2 ;;
esac
if [[ -n "$fault" && " $STEPS " != *" $fault "* ]]; then
  echo "test-two-installations: ISOLATION_FAULT must be one of: $STEPS (got: $fault)" >&2
  exit 2
fi

RUN_ID="rp-isolation-$$-$(date +%s)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rp-isolation.XXXXXX")"
WORK="$(cd "$WORK" && pwd -P)"
STARTED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
CURRENT_STEP=setup
RECORDS=() # "step<TAB>PASS|FAIL<TAB>detail", one per check, for the report
PITR_NAMES=()
OWNED=false # true once the projects are ours to remove (never someone else's rp-a / rp-b)

json_str() { printf '"%s"' "$(sed 's/\\/\\\\/g; s/"/\\"/g' <<<"$1" | tr -d '\n')"; }
write_report() { # $1 PASS|FAIL
  local report r sep="" step status detail
  report="$ISO_DIR/drill-isolation-$(date -u +%Y%m%dT%H%M%SZ).json"
  {
    echo "{"
    echo "  \"drill\": \"two-installation isolation (spec §14 #1, A55)\","
    echo "  \"result\": $(json_str "$1"),"
    echo "  \"mode\": \"production\","
    echo "  \"fault\": $(json_str "$fault"),"
    if [[ "$1" == FAIL ]]; then echo "  \"failed_step\": $(json_str "$CURRENT_STEP"),"; else echo "  \"failed_step\": null,"; fi
    echo "  \"started\": $(json_str "$STARTED"),"
    echo "  \"finished\": $(json_str "$(date -u +%Y-%m-%dT%H:%M:%SZ)"),"
    echo "  \"projects\": [\"rp-a\", \"rp-b\"],"
    printf '  "checks": ['
    for r in ${RECORDS[@]+"${RECORDS[@]}"}; do
      IFS=$'\t' read -r step status detail <<<"$r"
      printf '%s\n    {"step": %s, "status": %s, "detail": %s}' "$sep" "$(json_str "$step")" "$(json_str "$status")" "$(json_str "$detail")"
      sep=","
    done
    printf '\n  ]\n}\n'
  } >"$report"
  echo "$report"
}

remove_labelled() { # $1 label=value: every container, then volume, then network carrying it
  local id
  for id in $(docker ps -aq --filter "label=$1"); do docker rm -f -v "$id" >/dev/null 2>&1 || true; done
  for id in $(docker volume ls -q --filter "label=$1"); do docker volume rm "$id" >/dev/null 2>&1 || true; done
  for id in $(docker network ls -q --filter "label=$1"); do docker network rm "$id" >/dev/null 2>&1 || true; done
}
remove_everything() {
  local i n
  for i in b a; do
    compose "$i" down -v --remove-orphans --timeout 10 >/dev/null 2>&1 || true
    # What a failed "down" left (a fault, or a container still attached to a network).
    remove_labelled "com.docker.compose.project=rp-$i"
  done
  remove_labelled "restaurantpro.drill=$RUN_ID"
  for n in ${PITR_NAMES[@]+"${PITR_NAMES[@]}"}; do remove_labelled "restaurantpro.pitr=$n"; done
}

cleanup() {
  local status=$? result=PASS last
  set +e
  if $OWNED; then remove_everything; fi
  rm -rf "$WORK"
  if ((status != 0)); then
    result=FAIL
    # set -e inside a function ends the run without fail() or the ERR trap: the report still says so.
    last=""
    if ((${#RECORDS[@]})); then last="${RECORDS[${#RECORDS[@]}-1]}"; fi
    [[ "$last" == "$CURRENT_STEP"$'\t'FAIL$'\t'* ]] || record FAIL "unexpected failure (exit $status)"
  fi
  write_report "$result"
  if ((status == 0)); then echo "PASS: two-installation isolation drill" >&2; else echo "FAIL: two-installation isolation drill (step $CURRENT_STEP, exit $status)" >&2; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM
# An unexpected failure (set -e) of a command of this script is reported with its line.
trap 'record FAIL "unexpected failure at line $LINENO (exit $?)"; echo "FAIL: $CURRENT_STEP: unexpected failure at line $LINENO" >&2' ERR

record() { RECORDS+=("$CURRENT_STEP"$'\t'"$1"$'\t'"$2"); }
pass() { echo "PASS: $CURRENT_STEP: $*" >&2; record PASS "$*"; }
fail() { echo "FAIL: $CURRENT_STEP: $*" >&2; record FAIL "$*"; exit 1; }
die() { fail "$@"; }
step() { CURRENT_STEP=$1; echo "--- $1: $2" >&2; }
faulted() { [[ "$fault" == "$CURRENT_STEP" ]]; }
note() { echo "    $*" >&2; }
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"
# shellcheck source=lib/age.sh
. "$DEPLOY_DIR/lib/age.sh"
# shellcheck source=test/keys.sh
. "$DEPLOY_DIR/test/keys.sh"

env_of() { grep -E "^$2=" "$ISO_DIR/$1.env" | tail -1 | cut -d= -f2-; } # $1 installation, $2 name
# ISOLATION_FAULT=separation: B is configured with A's secret directory.
secrets_dir() { if [[ "$1" == b && "$fault" == separation ]]; then echo "$WORK/a/secrets"; else echo "$WORK/$1/secrets"; fi; }
compose() { # $1 installation, then docker compose arguments
  local i=$1
  shift
  RP_ISOLATION_SECRETS="$(secrets_dir "$i")" BACKUP_AGE_RECIPIENTS_FILE="$WORK/$i/keys/recipients.txt" \
    docker compose -p "rp-$i" --env-file "$ISO_DIR/$i.env" -f "$DEPLOY_DIR/compose.yaml" -f "$ISO_DIR/compose.isolation.yaml" "$@"
}
# The environment an operator of installation $1 runs the deploy/ scripts with: its project, its env file, its
# recipients and its backup directory. No --container: the scripts find the database themselves.
as_operator() {
  local i=$1
  shift
  COMPOSE_PROJECT_NAME="rp-$i" COMPOSE_ENV_FILES="$ISO_DIR/$i.env" BACKUP_AGE_RECIPIENTS_FILE="$WORK/$i/keys/recipients.txt" \
    BACKUP_DIR="$WORK/$i/backups" "$@"
}
service() { compose "$1" ps -q "$2"; }   # container id of service $2 of installation $1
name_of() { docker inspect -f '{{.Name}}' "$1" | sed 's|^/||'; }
psql_in() { docker exec -i "$(service "$1" postgres)" psql -X -q -At -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB" "${@:2}"; }

# One line per user table ("schema.table|rows|md5 of all rows in a stable order") and per sequence, as in
# test-backup-restore.sh.
fingerprint() {
  psql_in "$1" <<'SQL'
SELECT format('SELECT %L, count(*), md5(coalesce(string_agg(t::text, %L ORDER BY t::text), %L)) FROM %I.%I t',
              n.nspname || '.' || c.relname, E'\n', '', n.nspname, c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%'
ORDER BY n.nspname, c.relname
\gexec
SELECT 'sequence ' || schemaname || '.' || sequencename, coalesce(last_value::text, 'unset') FROM pg_sequences ORDER BY 1;
SQL
}
# SHA-256 of the full plain pg_dump (schema and data), without the random \restrict token pg_dump 18 emits per run.
dump_sum() {
  docker exec "$(service "$1" postgres)" pg_dump -U "$DB_USER" -d "$DB" --no-owner \
    | grep -vE '^\\(un)?restrict ' | sha256_hex /dev/stdin
}

# HTTPS through the installation's own Caddy, verified against the root certificate given in $2 (its own, unless
# a check says otherwise). Prints the status; the body goes to $WORK/body.
JAR=""
api() { # $1 installation, $2 root certificate, $3 method, $4 path, [$5 json body], [$6... extra headers]
  local i=$1 port host token header args
  port="$(env_of "$i" RESTAURANTPRO_HTTPS_PORT)"
  host="rp-$i.lan"
  args=(-sS -o "$WORK/body" -w '%{http_code}' --cacert "$2" --resolve "$host:$port:127.0.0.1" -b "$JAR" -c "$JAR" -X "$3" "https://$host:$port$4")
  token="$(awk '$6 == "XSRF-TOKEN" {print $7}' "$JAR" 2>/dev/null | tail -1 || true)"
  if [[ "$3" != GET && -n "$token" ]]; then args+=(-H "X-XSRF-TOKEN: $token"); fi
  if (($# > 4)) && [[ -n "$5" ]]; then args+=(-H 'Content-Type: application/json' --data "$5"); fi
  for header in "${@:6}"; do args+=(-H "$header"); done
  curl "${args[@]}"
}
login_body() { printf '{"username":"admin","password":"%s"}' "$(cat "$1")"; }
cookie_header() { awk -F'\t' 'NF == 7 { printf "%s%s=%s", sep, $6, $7; sep = "; " }' "$1"; } # every cookie of jar $1
# psql from a throwaway container on installation $1's internal network to its "postgres", with the password in
# file $2 (passed by variable name, never on a command line). Prints psql's output and error.
db_login() {
  PGPASSWORD="$(cat "$2")" docker run --rm -e PGPASSWORD --network "rp-$1_internal" --label restaurantpro.drill="$RUN_ID" \
    --entrypoint psql "$PG_IMAGE" -X -At -h postgres -U "$DB_USER" -d "$DB" -c 'SELECT 1' 2>&1
}
# pg_isready from inside the network namespace of container $1 to host $2 port 5432: 0 accepting, 1 rejecting,
# 2 no response (unreachable).
tcp_probe() {
  local status=0
  docker run --rm --network "container:$1" --label restaurantpro.drill="$RUN_ID" --entrypoint pg_isready "$PG_IMAGE" \
    -q -h "$2" -p 5432 -t 3 >/dev/null 2>&1 || status=$?
  echo "$status"
}
# Docker Desktop reports some bind sources under /host_mnt (its view of the host's file system).
real() { set -- "${1#/host_mnt}"; if [[ -e "$1" ]]; then if [[ -d "$1" ]]; then abs_dir "$1"; else abs_path "$1"; fi; else echo "$1"; fi; }
# One line per mount of every container of installation $1: "<container>|<type>|<source>|<rw>|<destination>".
mounts_of() {
  local c name type src rw dst
  for c in $(compose "$1" ps -aq); do
    docker inspect -f '{{$n := .Name}}{{range .Mounts}}{{$n}}|{{.Type}}|{{if eq .Type "volume"}}{{.Name}}{{else}}{{.Source}}{{end}}|{{.RW}}|{{.Destination}}{{"\n"}}{{end}}' "$c"
  done | while IFS='|' read -r name type src rw dst; do
    [[ -n "$name" ]] || continue
    [[ "$type" != bind ]] || src="$(real "$src")"
    echo "${name#/}|$type|$src|$rw|$dst"
  done
}

# --- setup -----------------------------------------------------------------------------------------------------
step setup "throwaway keys, secrets and the two stacks (production mode, D98)"
for i in $INSTALLATIONS; do
  [[ -z "$(project_objects "$i")" ]] || fail "Docker objects of project rp-$i already exist: remove them first (docker compose -p rp-$i down -v)"
done
OWNED=true
for i in $INSTALLATIONS; do
  mkdir -m 700 "$WORK/$i" "$WORK/$i/keys" "$WORK/$i/secrets" "$WORK/$i/wal-empty"
  make_throwaway_keys "$WORK/$i/keys"
  openssl rand -hex 24 | tr -d '\n' >"$WORK/$i/secrets/db_password"
  printf 'initial-%s' "$(openssl rand -hex 12)" >"$WORK/$i/secrets/admin_initial_password"
  chmod 644 "$WORK/$i/secrets/"* # read by non-root users inside the containers; the 0700 directory protects them
done
unset BACKUP_AGE_RECIPIENTS_FILE # per installation from here on (compose, as_operator)
if [[ "$fault" == credentials ]]; then cp -p "$WORK/a/secrets/"* "$WORK/b/secrets/"; fi # B gets A's passwords
note "building the images (cached after the first run) and starting rp-a"
# The postgres image as lib/age.sh builds it: a Compose build would stamp rp-a's project label on the image that
# every installation and pitr-restore.sh container on this host runs.
age_image_ready
compose a build backend >"$WORK/up-a.log" 2>&1 || { tail -30 "$WORK/up-a.log" >&2; fail "the backend image did not build"; }
compose a up -d >>"$WORK/up-a.log" 2>&1 || { tail -30 "$WORK/up-a.log" >&2; fail "rp-a did not start"; }
note "starting rp-b"
compose b up -d >"$WORK/up-b.log" 2>&1 || { tail -30 "$WORK/up-b.log" >&2; fail "rp-b did not start"; }
for i in $INSTALLATIONS; do
  if ! compose "$i" up -d --wait --wait-timeout 360 >>"$WORK/up-$i.log" 2>&1; then
    tail -20 "$WORK/up-$i.log" >&2
    compose "$i" logs --tail 40 backend >&2 || true
    fail "rp-$i did not become healthy"
  fi
  for _ in $(seq 1 30); do
    docker exec "$(service "$i" caddy)" cat "$CADDY_ROOT" >"$WORK/$i/root.crt" 2>/dev/null && break
    sleep 1
  done
  grep -q 'BEGIN CERTIFICATE' "$WORK/$i/root.crt" || fail "rp-$i's Caddy did not create its local root certificate"
done
pass "rp-a and rp-b are up and healthy (production mode, two throwaway recipients each)"

# --- 1. separation ---------------------------------------------------------------------------------------------
step separation "containers, networks, volumes and secrets of each project are its own"
for i in $INSTALLATIONS; do
  compose "$i" ps -aq | sort >"$WORK/$i.containers"
  docker volume ls -q --filter "label=com.docker.compose.project=rp-$i" | sort >"$WORK/$i.volumes"
  docker network ls --format '{{.Name}}' --filter "label=com.docker.compose.project=rp-$i" | sort >"$WORK/$i.networks"
  mounts_of "$i" | sort >"$WORK/$i.mounts"
  note "rp-$i containers: $(while read -r c; do name_of "$c"; done <"$WORK/$i.containers" | tr '\n' ' ')"
  note "rp-$i volumes: $(tr '\n' ' ' <"$WORK/$i.volumes")"
  note "rp-$i networks: $(tr '\n' ' ' <"$WORK/$i.networks")"
done
for i in $INSTALLATIONS; do
  [[ "$(wc -l <"$WORK/$i.containers" | tr -d ' ')" == 3 ]] || fail "rp-$i should run 3 containers (postgres, backend, caddy)"
  [[ "$(wc -l <"$WORK/$i.volumes" | tr -d ' ')" == 4 ]] || fail "rp-$i should own 4 volumes"
  [[ "$(wc -l <"$WORK/$i.networks" | tr -d ' ')" == 2 ]] || fail "rp-$i should own 2 networks"
done
for kind in containers volumes networks; do
  shared="$(comm -12 "$WORK/a.$kind" "$WORK/b.$kind")"
  [[ -z "$shared" ]] || fail "$kind shared by rp-a and rp-b: $shared"
done
pass "no container, volume or network belongs to both projects"
for i in $INSTALLATIONS; do
  while read -r c; do
    modes="$(docker inspect -f '{{.HostConfig.NetworkMode}} {{.HostConfig.PidMode}} {{.HostConfig.IpcMode}}' "$c")"
    for m in $modes; do
      [[ "$m" != container:* && "$m" != host ]] || fail "$(name_of "$c") shares a namespace ($m)"
    done
    for n in $(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$c"); do
      grep -qx "$n" "$WORK/$i.networks" || fail "$(name_of "$c") of rp-$i is attached to network $n, which is not rp-$i's"
    done
  done <"$WORK/$i.containers"
  while IFS='|' read -r c type src _ _; do
    [[ "$type" != volume ]] || grep -qx "$src" "$WORK/$i.volumes" || fail "$c of rp-$i mounts volume $src, which is not rp-$i's"
  done <"$WORK/$i.mounts"
done
pass "every container is attached only to its own project's networks and volumes, with its own PID, IPC and network namespaces"
for i in $INSTALLATIONS; do
  private=0
  while IFS='|' read -r c type src _ dst; do
    case "$dst" in
      /run/secrets/* | /etc/restaurantpro/backup-recipients.txt)
        [[ "$src" == "$WORK/$i/"* ]] || fail "$c of rp-$i mounts $dst from $src, outside rp-$i's own directory $WORK/$i"
        private=$((private + 1)) ;;
    esac
  done <"$WORK/$i.mounts"
  # postgres: db_password and the recipients; backend: db_password and admin_initial_password.
  ((private == 4)) || fail "rp-$i mounts $private secret or recipients files, expected 4"
done
cut -d'|' -f2-3 "$WORK/a.mounts" | sort -u >"$WORK/a.sources"
cut -d'|' -f2-3 "$WORK/b.mounts" | sort -u >"$WORK/b.sources"
shared_code=0
while IFS='|' read -r type src; do
  [[ "$type" == bind && "$src" == "$DEPLOY_DIR/"* && "$src" != "$DEPLOY_DIR/secrets"* ]] \
    || fail "mounted in both installations: $type $src"
  if grep -q "|$type|$src|true|" "$WORK/a.mounts" "$WORK/b.mounts"; then fail "mounted writable in both installations: $src"; fi
  shared_code=$((shared_code + 1))
done < <(comm -12 "$WORK/a.sources" "$WORK/b.sources")
pass "secrets and recipients come from each installation's own directory; the only shared mounts are $shared_code read-only deploy/ files (scripts, Caddyfile)"

# --- 2. credentials ---------------------------------------------------------------------------------------------
step credentials "A's credentials do not open B, and B's do not open A"
for i in $INSTALLATIONS; do
  JAR="$WORK/$i/cookies"
  [[ "$(api "$i" "$WORK/$i/root.crt" GET /api/v1/auth/session)" == 401 ]] || fail "rp-$i: GET /auth/session without a session should be 401"
  status=""
  for attempt in $(seq 1 30); do # the admin is created by a listener just after the bootstrap commits
    status="$(api "$i" "$WORK/$i/root.crt" POST /api/v1/auth/login "$(login_body "$WORK/$i/secrets/admin_initial_password")")"
    [[ "$status" == 200 ]] && break
    sleep 1
  done
  [[ "$status" == 200 ]] || fail "rp-$i: its own admin login gave $status after $attempt attempts"
  [[ "$(api "$i" "$WORK/$i/root.crt" GET /api/v1/auth/session)" == 200 ]] || fail "rp-$i: its own session is not accepted"
done
pass "each admin logs in on its own installation and its session cookie is accepted there (positive control)"
for pair in "a b" "b a"; do
  read -r own other <<<"$pair"
  OWN="$(tr '[:lower:]' '[:upper:]' <<<"$own")"
  JAR="$WORK/$own/probe-cookies"
  rm -f "$JAR"
  api "$other" "$WORK/$other/root.crt" GET /api/v1/auth/session >/dev/null # fresh XSRF-TOKEN, as a browser gets one
  got="$(api "$other" "$WORK/$other/root.crt" POST /api/v1/auth/login "$(login_body "$WORK/$own/secrets/admin_initial_password")")"
  [[ "$got" == 401 ]] || fail "$OWN's admin password on rp-$other: expected 401, got $got"
  JAR="$WORK/$own/no-jar"
  got="$(api "$other" "$WORK/$other/root.crt" GET /api/v1/auth/session '' "Cookie: $(cookie_header "$WORK/$own/cookies")")"
  [[ "$got" == 401 ]] || fail "$OWN's session cookie on rp-$other: expected 401, got $got"
  got="$(api "$own" "$WORK/$own/root.crt" GET /api/v1/auth/session '' "Cookie: $(cookie_header "$WORK/$own/cookies")")"
  [[ "$got" == 200 ]] || fail "the same cookie header on rp-$own should be 200 (control), got $got"
  pass "$OWN's admin password and session cookie get 401 on rp-$other (same username, admin)"
done
for pair in "a b" "b a"; do
  read -r own other <<<"$pair"
  OWN="$(tr '[:lower:]' '[:upper:]' <<<"$own")"
  out="$(db_login "$other" "$WORK/$other/secrets/db_password")" || fail "rp-$other's own database password is refused (control): $out"
  [[ "$out" == 1 ]] || fail "rp-$other's own database login printed: $out"
  if out="$(db_login "$other" "$WORK/$own/secrets/db_password")"; then fail "$OWN's database password opens rp-$other's PostgreSQL"; fi
  grep -q 'password authentication failed' <<<"$out" || fail "$OWN's database password on rp-$other failed for another reason: $out"
  pass "$OWN's database password: psql on rp-$other's network fails with \"password authentication failed\""
done
[[ "$(sha256_hex "$WORK/a/root.crt")" != "$(sha256_hex "$WORK/b/root.crt")" ]] || fail "rp-a and rp-b share Caddy's root certificate"
JAR="$WORK/a/no-jar"
if api b "$WORK/a/root.crt" GET /api/v1/auth/session >/dev/null 2>"$WORK/tls.err"; then fail "A's Caddy root certificate verifies rp-b"; fi
pass "each Caddy has its own local CA: A's root certificate does not verify rp-b ($(head -1 "$WORK/tls.err" | cut -c1-80))"

# --- 3. network -------------------------------------------------------------------------------------------------
step network "B's backend cannot resolve or reach A's database"
b_backend="$(service b backend)"
a_pg="$(service a postgres)"
b_pg="$(service b postgres)"
a_pg_name="$(name_of "$a_pg")"
ips_of() { docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' "$1" | sed 's/ $//'; }
a_pg_ips="$(ips_of "$a_pg")"
b_pg_ips="$(ips_of "$b_pg")"
if faulted; then docker network connect rp-a_internal "$b_backend"; note "fault: rp-b's backend joined rp-a_internal"; fi
if out="$(docker exec "$b_backend" getent hosts "$a_pg_name" 2>&1)"; then fail "rp-b's backend resolves $a_pg_name: $out"; fi
own_ip="$(docker exec "$b_backend" getent hosts postgres | awk '{print $1}')" || fail "rp-b's backend cannot resolve its own postgres (control)"
[[ " $b_pg_ips " == *" $own_ip "* ]] || fail "\"postgres\" resolves to $own_ip in rp-b's backend, not to rp-b's database ($b_pg_ips)"
pass "getent in rp-b's backend: $a_pg_name does not resolve; \"postgres\" is rp-b's own database ($own_ip)"
[[ "$(tcp_probe "$b_backend" postgres)" == 0 ]] || fail "the probe in rp-b's backend namespace cannot reach rp-b's own database (control)"
for ip in $a_pg_ips; do
  got="$(tcp_probe "$b_backend" "$ip")"
  [[ "$got" == 2 ]] || fail "rp-b's backend reaches rp-a's database at $ip:5432 (pg_isready exit $got)"
done
pass "TCP from rp-b's backend namespace: rp-a's database ($a_pg_ips) port 5432 unreachable; own database reachable"

# --- 4. restore -------------------------------------------------------------------------------------------------
step restore "back up A, change A, restore A: A matches its backup, B is byte-identical"
# Quiet databases: no background writes from either backend while fingerprints are compared.
compose a stop backend >/dev/null 2>&1 || fail "could not stop rp-a's backend"
compose b stop backend >/dev/null 2>&1 || fail "could not stop rp-b's backend"
b_sum_before="$(dump_sum b)"
b_fp_before="$(fingerprint b)"
a_before="$(fingerprint a)"
# Spring Session rows are not backed up (backup.sh): after a restore they are empty.
a_expected="$(sed -E 's/^(shared\.spring_session(_attributes)?)\|[0-9]+\|[0-9a-f]+$/\1|0|d41d8cd98f00b204e9800998ecf8427e/' <<<"$a_before")"
note "rp-b before: pg_dump sha256 $b_sum_before, $(grep -vc '^sequence ' <<<"$b_fp_before") tables"
archive="$(as_operator a "$DEPLOY_DIR/backup.sh" 2>"$WORK/a-backup.err")" || { cat "$WORK/a-backup.err" >&2; fail "backup.sh of rp-a failed"; }
grep -q "from container $a_pg" "$WORK/a-backup.err" || fail "backup.sh in A's environment did not dump rp-a's database: $(cat "$WORK/a-backup.err")"
if [[ "$archive" != "$WORK/a/backups/"*.tar.age ]] || ! is_age_file "$archive"; then
  fail "backup.sh did not write an encrypted archive in A's backup directory (got $archive)"
fi
pass "backup.sh in A's environment dumped rp-a's database ($(name_of "$a_pg")) into $(basename "$archive"), age-encrypted"
psql_in a <<'SQL'
UPDATE organization.company_profile SET name = name || ' (changed after the backup)';
CREATE TABLE public.isolation_drill_after_backup (id int PRIMARY KEY);
INSERT INTO public.isolation_drill_after_backup VALUES (1);
SQL
[[ "$(fingerprint a)" != "$a_before" ]] || fail "the change to rp-a is not visible"
if faulted; then
  note "fault: A's archive restored into rp-b's database (the installation guard bypassed with --other-installation rp-b)"
  as_operator a "$DEPLOY_DIR/restore.sh" --force --other-installation rp-b --container "$b_pg" --identity "$WORK/a/keys/operational.key" "$archive" 2>>"$WORK/fault.err" || true
fi
as_operator a "$DEPLOY_DIR/restore.sh" --force --identity "$WORK/a/keys/operational.key" "$archive" 2>"$WORK/a-restore.err" \
  || { cat "$WORK/a-restore.err" >&2; fail "restore.sh --force of rp-a failed"; }
a_after="$(fingerprint a)"
if [[ "$a_after" != "$a_expected" ]]; then diff <(echo "$a_expected") <(echo "$a_after") >&2 || true; fail "rp-a does not match its backup"; fi
pass "restore.sh --force in A's environment: rp-a matches its backup ($(grep -vc '^sequence ' <<<"$a_after") tables; the changed row and the table created after the backup are gone)"
b_sum_after="$(dump_sum b)"
b_fp_after="$(fingerprint b)"
if [[ "$b_fp_after" != "$b_fp_before" ]]; then diff <(echo "$b_fp_before") <(echo "$b_fp_after") >&2 || true; fi
[[ "$b_sum_after" == "$b_sum_before" ]] || fail "rp-b changed during A's backup and restore: pg_dump sha256 $b_sum_before -> $b_sum_after"
[[ "$b_fp_after" == "$b_fp_before" ]] || fail "rp-b's table fingerprint changed during A's backup and restore"
pass "rp-b byte-identical: pg_dump sha256 $b_sum_after before and after, every table fingerprint unchanged"

# --- 5. tooling -------------------------------------------------------------------------------------------------
step tooling "A's restore tooling refuses B's objects"
if faulted; then
  note "fault: A's operator environment names project rp-b"
  resolved="$(as_operator a env COMPOSE_PROJECT_NAME=rp-b docker compose -f "$DEPLOY_DIR/compose.yaml" ps -q postgres)"
else
  resolved="$(as_operator a docker compose -f "$DEPLOY_DIR/compose.yaml" ps -q postgres)"
fi
[[ -n "$resolved" && "$resolved" == "$a_pg" ]] || fail "A's operator environment resolves the database to '$resolved', not rp-a's $a_pg"
pass "A's operator environment (COMPOSE_PROJECT_NAME, COMPOSE_ENV_FILES) resolves backup.sh/restore.sh to rp-a's database only"
b_backup="$(as_operator b "$DEPLOY_DIR/backup.sh" 2>"$WORK/b-backup.err")" || { cat "$WORK/b-backup.err" >&2; fail "backup.sh of rp-b failed"; }
b_sum_before="$(dump_sum b)"
a_sum_before="$(dump_sum a)"
b_volume="$(grep -x 'rp-b_db-data' "$WORK/b.volumes")" || fail "rp-b has no rp-b_db-data volume"
b_pg_name="$(name_of "$b_pg")"
for target in "$b_pg_name" "${b_volume%-data}"; do # the container itself, then the name whose <name>-data is B's volume
  PITR_NAMES+=("$target")
  if as_operator a "$DEPLOY_DIR/pitr-restore.sh" --force --name "$target" --target-time latest --wal-dir "$WORK/a/wal-empty" \
    --identity "$WORK/a/keys/operational.key" "$archive" >/dev/null 2>"$WORK/pitr.err"; then
    fail "pitr-restore.sh --force --name $target succeeded against rp-b"
  fi
  grep -q 'belongs to a Docker Compose project' "$WORK/pitr.err" || fail "pitr-restore.sh --name $target was not refused by the Compose label guard: $(tail -3 "$WORK/pitr.err")"
done
if [[ "$(docker inspect -f '{{.State.Running}}' "$b_pg" 2>/dev/null)" != true ]] || ! docker volume inspect "$b_volume" >/dev/null 2>&1; then
  fail "rp-b's database container or volume is gone after pitr-restore.sh was refused"
fi
pass "pitr-restore.sh --force refuses container $b_pg_name and volume $b_volume (created by Compose for rp-b: config-hash label on the container, volume label on the volume)"
# An earlier pitr-restore.sh --name container, started from a Compose-built image so it inherits the image's
# project and service labels (but not config-hash), and its volume: --force must replace both.
earlier="$RUN_ID-pitr-earlier"
PITR_NAMES+=("$earlier")
docker volume create --label restaurantpro.pitr="$earlier" "$earlier-data" >/dev/null
docker run -d --name "$earlier" --network none --label restaurantpro.pitr="$earlier" --label com.docker.compose.project=rp-a \
  --label com.docker.compose.service=postgres --entrypoint sleep "$PG_IMAGE" 600 >/dev/null
as_operator a "$DEPLOY_DIR/pitr-restore.sh" --force --name "$earlier" --target-time latest --wal-dir "$WORK/a/wal-empty" \
  --identity "$WORK/a/keys/operational.key" "$archive" >/dev/null 2>"$WORK/pitr.err" || true # A's archive is no base backup
if ! grep -q -- "--force: removing container $earlier" "$WORK/pitr.err" || ! grep -q -- "--force: removing volume $earlier-data" "$WORK/pitr.err"; then
  fail "pitr-restore.sh --force did not replace its own earlier container and volume (inherited Compose labels): $(grep pitr-restore: "$WORK/pitr.err" | head -2)"
fi
! docker container inspect "$earlier" >/dev/null 2>&1 || fail "the earlier pitr-restore.sh container $earlier is still there"
pass "pitr-restore.sh --force replaces its own earlier container (Compose project/service labels inherited from the image) and volume"
restore_a() { as_operator a "$DEPLOY_DIR/restore.sh" --identity "$WORK/a/keys/operational.key" "$@" 2>"$WORK/r.err"; }
backup_a() { as_operator a "$DEPLOY_DIR/backup.sh" "$@" 2>"$WORK/r.err"; }
refused_as_other() { grep -q "belongs to installation rp-b" "$WORK/r.err"; }
for force in --force ""; do
  if restore_a ${force:+"$force"} --container "$b_pg" "$archive"; then fail "restore.sh ${force:-without --force} wrote A's archive into rp-b"; fi
  refused_as_other || fail "restore.sh ${force:-without --force} into rp-b was not refused by the installation guard: $(tail -2 "$WORK/r.err")"
done
if restore_a --force --other-installation rp-c --container "$b_pg" "$archive"; then fail "restore.sh --other-installation rp-c wrote into rp-b"; fi
refused_as_other || fail "restore.sh --other-installation rp-c into rp-b was not refused: $(tail -2 "$WORK/r.err")"
[[ "$(dump_sum b)" == "$b_sum_before" ]] || fail "a refused restore.sh changed rp-b"
pass "restore.sh, with and without --force, refuses $b_pg_name from A's environment (Compose project rp-b, not rp-a), also with --other-installation rp-c"
# The override names exactly rp-b: past the installation guard, loudly, then stopped by the non-empty guard.
if restore_a --other-installation rp-b --container "$b_pg" "$archive"; then fail "restore.sh wrote A's archive into rp-b without --force"; fi
grep -q 'WARNING: --other-installation rp-b' "$WORK/r.err" || fail "restore.sh --other-installation rp-b did not log the override: $(tail -2 "$WORK/r.err")"
grep -q 'is not empty' "$WORK/r.err" || fail "restore.sh --other-installation rp-b was not stopped by the non-empty guard: $(tail -2 "$WORK/r.err")"
[[ "$(dump_sum b)" == "$b_sum_before" ]] || fail "restore.sh --other-installation rp-b without --force changed rp-b"
pass "restore.sh --other-installation rp-b passes the installation guard with a WARNING, and without --force still refuses rp-b's non-empty database"
if backup_a --container "$b_pg" --dir "$WORK/a/other" >/dev/null; then fail "backup.sh in A's environment dumped rp-b's database"; fi
refused_as_other || fail "backup.sh of rp-b from A's environment was not refused by the installation guard: $(tail -2 "$WORK/r.err")"
if backup_a --other-installation rp-c --container "$b_pg" --dir "$WORK/a/other" >/dev/null; then fail "backup.sh --other-installation rp-c dumped rp-b"; fi
refused_as_other || fail "backup.sh --other-installation rp-c was not refused: $(tail -2 "$WORK/r.err")"
[[ ! -e "$WORK/a/other" ]] || fail "a refused backup.sh created $WORK/a/other"
other_archive="$(backup_a --other-installation rp-b --container "$b_pg" --dir "$WORK/a/other")" \
  || fail "backup.sh --other-installation rp-b failed: $(tail -2 "$WORK/r.err")"
grep -q 'WARNING: --other-installation rp-b' "$WORK/r.err" || fail "backup.sh --other-installation rp-b did not log the override"
is_age_file "$other_archive" || fail "backup.sh --other-installation rp-b did not write an encrypted archive"
pass "backup.sh refuses $b_pg_name from A's environment (also with --other-installation rp-c); --other-installation rp-b dumps it, with a WARNING"
# A container Compose did not create but that carries rp-b's project label, as one started from a Compose-built
# image inherits it (a pitr-restore.sh container): not another installation's database, so backup.sh may dump it.
inherited="$RUN_ID-inherited"
POSTGRES_PASSWORD="$(openssl rand -hex 16)" docker run -d --name "$inherited" -e POSTGRES_PASSWORD -e POSTGRES_USER="$DB_USER" \
  -e POSTGRES_DB="$DB" --label restaurantpro.drill="$RUN_ID" --label com.docker.compose.project=rp-b --entrypoint docker-entrypoint.sh \
  "$PG_IMAGE" postgres >/dev/null
for _ in $(seq 1 60); do docker exec "$inherited" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$DB" 2>/dev/null && break; sleep 1; done
backup_a --container "$inherited" --dir "$WORK/a/inherited" >/dev/null \
  || fail "backup.sh refused a container Compose did not create (only an inherited rp-b label): $(tail -2 "$WORK/r.err")"
docker rm -f -v "$inherited" >/dev/null
pass "a container Compose did not create, with an inherited com.docker.compose.project=rp-b label (as from a Compose-built image), is not taken for rp-b"
if as_operator a "$DEPLOY_DIR/restore.sh" --force --identity "$WORK/a/keys/operational.key" "$b_backup" 2>"$WORK/r.err"; then
  fail "A's key decrypted and restored B's backup"
fi
grep -q 'cannot decrypt' "$WORK/r.err" || fail "B's backup with A's key failed for another reason: $(tail -2 "$WORK/r.err")"
[[ "$(dump_sum a)" == "$a_sum_before" ]] || fail "the refused restore of B's backup changed rp-a"
pass "A's key cannot decrypt B's backup $(basename "$b_backup"); rp-a unchanged"
listing() { (cd "$1" && find . -type f | sort | while IFS= read -r f; do printf '%s %s\n' "$(sha256_hex "$f")" "$f"; done); }
b_dir_before="$(listing "$WORK/b/backups")"
if as_operator a "$DEPLOY_DIR/backup.sh" --dir "$WORK/b/backups" >/dev/null 2>"$WORK/r.err"; then fail "backup.sh with A's recipients wrote into B's backup directory"; fi
grep -q 'the recipients changed' "$WORK/r.err" || fail "backup.sh into B's directory failed for another reason: $(tail -2 "$WORK/r.err")"
[[ "$(listing "$WORK/b/backups")" == "$b_dir_before" ]] || fail "B's backup directory changed"
pass "backup.sh with A's recipients refuses B's backup directory (recorded recipients differ); nothing written"
[[ "$(dump_sum b)" == "$b_sum_before" ]] || fail "rp-b changed during the tooling checks"
pass "rp-b byte-identical after the tooling checks (pg_dump sha256 $b_sum_before)"

# --- 6. teardown ------------------------------------------------------------------------------------------------
step teardown "both stacks down with their volumes; nothing left"
compose b down -v --remove-orphans --timeout 10 >/dev/null 2>&1 || fail "docker compose down of rp-b failed"
if faulted; then
  note "fault: rp-a is taken down without its volumes"
  compose a down --remove-orphans --timeout 10 >/dev/null 2>&1 || fail "docker compose down of rp-a failed"
else
  compose a down -v --remove-orphans --timeout 10 >/dev/null 2>&1 || fail "docker compose down of rp-a failed"
fi
for i in $INSTALLATIONS; do
  left="$(project_objects "$i")"
  [[ -z "$left" ]] || fail "rp-$i left Docker objects behind: $(tr '\n' ' ' <<<"$left")"
  rm -rf "${WORK:?}/$i"
  [[ ! -e "$WORK/$i" ]] || fail "could not remove rp-$i's secret, key and backup directory"
done
left="$(docker ps -aq --filter "label=restaurantpro.drill=$RUN_ID")"
[[ -z "$left" ]] || fail "probe containers left behind: $left"
pass "no container, volume or network of rp-a or rp-b is left; secrets, keys and backups removed"
CURRENT_STEP=finished
