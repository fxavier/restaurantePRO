#!/usr/bin/env bash
# Backup encryption drill (PRD S36, acceptance A37; D98). Production mode, throwaway age keys.
#
# Starts the postgres service of deploy/compose.yaml itself (throwaway projects, secrets and volumes) and an
# sshd container with rrsync as the off-box destination (built from the same pinned alpine base). Checks:
#   1. production without a recipients file, or with an empty one: postgres does not start; backup.sh and
#      basebackup.sh exit non-zero and write nothing
#   2. one recipient only: refused by postgres and by both scripts; BACKUP_AGE_RECIPIENT is test mode only
#   3. two recipients: every file in the pg-wal-archive volume, in the backup directory and at the off-box
#      target is age ciphertext with its sidecar, and none is plaintext WAL, a pg_dump archive, gzip or tar
#   4. an identity file under deploy/, or BACKUP_AGE_IDENTITY set: the backup scripts refuse; a 0755 backup
#      directory, a 0644 backup file, a 0644 identity or a group-writable recipients file: refused
#   5. pitr-restore.sh to T with the operational key, then again with the recovery key alone: the same
#      fingerprint at T; restore.sh of the logical archive with the recovery key alone
#   6. a wrong key: pitr-restore.sh fails and leaves no container or volume behind
#   7. a swapped recipients file: verify-backups.sh fails until --accept-recipients-change
#   8. offbox-sync.sh: an unknown host key is refused; the pinned key copies; a plaintext file is refused; a
#      rewritten (ransomware) archive and sidecar never replace the off-box copy; --check-append-only passes
#      behind rrsync -wo -no-del -no-overwrite, and fails without -no-overwrite or without rrsync
#   also: a group-writable deploy/.env, a recipients file postgres cannot read, missing or malformed sidecars,
#      plaintext WAL next to an encrypted base, tampered ciphertext (caught key-free), a recipients swap that
#      blocks the backup scripts, and ciphertext without a recorded recipients fingerprint
#   9. an S27-style plaintext installation (dev mode, which still works): after the switch to production,
#      basebackup.sh refuses until --encrypt-legacy, which leaves no plaintext; restores still work
#  10. verify-restore.sh --key-recovery on the off-box copy, with the recovery key alone: success, JSON report
# Exits non-zero on any mismatch.
#
# Usage: deploy/test-backup-encryption.sh     (needs only Docker; leaves nothing behind; about 4 minutes)
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB=restaurantpro
DB_USER=restaurantpro
RUN_ID="rp-crypt-drill-$$-$(date +%s)"
LEGACY_ID="$RUN_ID-legacy"
PITR_NAME="$RUN_ID-restored"
SSHD="$RUN_ID-sshd"
SSHD_IMAGE="restaurantpro-drill-sshd:$RUN_ID"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rp-crypt-drill.XXXXXX")"
KEYS="$WORK/keys"
BACKUPS="$WORK/backups"

fail() { echo "FAIL: $*" >&2; exit 1; }
die() { fail "$@"; }
ok() { echo "ok: $*" >&2; }
step() { echo "--- $*" >&2; }
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"
# shellcheck source=lib/age.sh
. "$DEPLOY_DIR/lib/age.sh"
# shellcheck source=test/keys.sh
. "$DEPLOY_DIR/test/keys.sh"

compose() { docker compose -p "$RUN_ID" -f "$DEPLOY_DIR/compose.yaml" -f "$WORK/drill.override.yaml" "$@"; }
legacy() { docker compose -p "$LEGACY_ID" -f "$DEPLOY_DIR/compose.yaml" -f "$WORK/drill.override.yaml" "$@"; }

cleanup() {
  local status=$?
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  legacy down -v --remove-orphans >/dev/null 2>&1 || true
  docker rm -f -v "$PITR_NAME" "$SSHD" "$RUN_ID-dev" >/dev/null 2>&1 || true
  docker volume rm "$PITR_NAME-data" >/dev/null 2>&1 || true
  docker image rm "$SSHD_IMAGE" >/dev/null 2>&1 || true
  rm -rf "$WORK"
  if ((status == 0)); then echo "PASS: backup encryption drill" >&2; else echo "FAIL: backup encryption drill (exit $status)" >&2; fi
}
trap cleanup EXIT

# Throwaway secrets and the variables compose.yaml requires; the backend and Caddy are never started.
mkdir -m 700 "$KEYS" "$WORK/secrets" "$WORK/ssh"
openssl rand -hex 16 >"$WORK/secrets/db_password"
openssl rand -hex 16 >"$WORK/secrets/admin_initial_password"
chmod 644 "$WORK/secrets/"*
cat >"$WORK/drill.override.yaml" <<YAML
secrets:
  db_password:
    file: $WORK/secrets/db_password
  admin_initial_password:
    file: $WORK/secrets/admin_initial_password
YAML
export RESTAURANTPRO_HOSTNAME=drill.invalid RESTAURANTPRO_BOOTSTRAP_COMPANY_NAME=drill \
  RESTAURANTPRO_BOOTSTRAP_COMPANY_TAX_ID=0 RESTAURANTPRO_BOOTSTRAP_COMPANY_COUNTRY=XX \
  RESTAURANTPRO_BOOTSTRAP_COMPANY_CURRENCY=XXX RESTAURANTPRO_BOOTSTRAP_ADMIN_USERNAME=drill

psql_in() { docker exec -i "$1" psql -X -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB" "${@:2}"; }
q() { psql_in "$1" -At -c "$2"; }
fingerprint() { # one line per user table, as test-pitr.sh
  psql_in "$1" -At <<'SQL'
SELECT format('SELECT %L, count(*), md5(coalesce(string_agg(t::text, %L ORDER BY t::text), %L)) FROM %I.%I t',
              n.nspname || '.' || c.relname, E'\n', '', n.nspname, c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%'
ORDER BY n.nspname, c.relname
\gexec
SQL
}
write() { # $1 container, $2 id; prints the commit time
  q "$1" "INSERT INTO drill.pitr_event (id, label) VALUES ($2, 'W$2')" >/dev/null
  q "$1" "SELECT to_char(committed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM drill.pitr_event WHERE id = $2"
}
rows() { q "$1" "SELECT coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM drill.pitr_event"; }
now_utc() { q "$1" "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"; }
switch_and_wait() { # $1 container: archives the current segment
  local seg
  seg="$(q "$1" 'SELECT pg_walfile_name(pg_current_wal_insert_lsn())')"
  q "$1" 'SELECT pg_switch_wal()' >/dev/null
  for _ in $(seq 1 60); do
    [[ "$(q "$1" "SELECT coalesce(last_archived_wal >= '$seg', false) FROM pg_stat_archiver")" == t ]] && return 0
    sleep 1
  done
  fail "$seg not archived within 60s ($(q "$1" 'SELECT failed_count, last_failed_wal FROM pg_stat_archiver'))"
}
wait_ready() { # $1 container
  for _ in $(seq 1 60); do
    docker exec "$1" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$DB" 2>/dev/null && return 0
    sleep 1
  done
  fail "postgres $1 did not become ready: $(docker logs --tail 5 "$1" 2>&1)"
}
refused_to_start() { # $1 recipients file, $2 expected reason (regex): postgres must not start
  local cid out
  if ! out="$(BACKUP_AGE_RECIPIENTS_FILE="$1" compose up -d --no-build postgres 2>&1)"; then
    compose down -v >/dev/null 2>&1 || true
    [[ "$out" =~ $2 ]] || fail "compose refused, but not for the expected reason ($2): $out"
    echo "compose: $(tail -1 <<<"$out")"
    return 0
  fi
  cid="$(compose ps -a -q postgres)"
  for _ in $(seq 1 30); do
    out="$(docker logs "$cid" 2>&1 || true)"
    [[ "$out" == *"REFUSING TO START"* ]] && break
    sleep 1
  done
  if docker exec "$cid" pg_isready -q -h 127.0.0.1 >/dev/null 2>&1; then fail "postgres started with $1"; fi
  [[ "$out" =~ $2 ]] || fail "postgres not refused for the expected reason ($2): $(tail -3 <<<"$out")"
  compose down -v >/dev/null 2>&1
  grep -m1 'REFUSING TO START' <<<"$out"
}
writes_nothing() { # $1 label, $2 expected reason (regex), $3... the command; must fail without creating $NOTHING
  local err
  rm -rf "$NOTHING"
  if err="$("${@:3}" 2>&1 >/dev/null)"; then fail "$1: succeeded"; fi
  [[ ! -e "$NOTHING" ]] || fail "$1: wrote $(find "$NOTHING" | head -3 | tr '\n' ' ')"
  [[ "$err" =~ $2 ]] || fail "$1: not refused for the expected reason ($2): $(tail -2 <<<"$err")"
  ok "$1: refused, nothing written ($(grep -m1 -E "$2" <<<"$err" | cut -c1-150))"
}

step "setup: pinned image $RP_AGE_IMAGE, throwaway keys outside deploy/"
age_image_ready
make_throwaway_keys "$KEYS"
grep -m1 "^age1" "$KEYS/recipients.txt" >"$KEYS/one-recipient.txt"
: >"$KEYS/empty.txt"
chmod 644 "$KEYS/one-recipient.txt" "$KEYS/empty.txt"
(umask 077 && age_keygen >"$KEYS/wrong.key")
(umask 077 && grep -E "$RP_AGE_SECRET_KEY" "$KEYS/recovery.key" >"$KEYS/recovery-only.key")
ok "operational $(age_public_key "$KEYS/operational.key" | cut -c1-16)…, recovery $(age_public_key "$KEYS/recovery.key" | cut -c1-16)…"

step "1. production without recipients: postgres does not start, the backup scripts write nothing"
NOTHING="$WORK/nothing"
why="$(refused_to_start "$WORK/no-such-file.txt" 'no recipients file|bind source path does not exist|not found|no such file')"
ok "missing file: $why"
why="$(refused_to_start "$KEYS/empty.txt" 'holds 0 distinct recipient')"
ok "empty file: $why"

step "2. one recipient only: refused"
why="$(refused_to_start "$KEYS/one-recipient.txt" 'holds 1 distinct recipient')"
ok "one recipient: $why"

step "2b. a recipients file the postgres user cannot read: refused"
# Docker Desktop's file sharing ignores modes, so the file is made root-only inside the container.
preflight_with() { # $1 mode for a root-owned copy of the recipients file inside the container
  docker run --rm --network none -e RESTAURANTPRO_ENVIRONMENT=production \
    -v "$DEPLOY_DIR/postgres/preflight.sh:/etc/restaurantpro/preflight.sh:ro" -v "$DEPLOY_DIR/lib/common.sh:/etc/restaurantpro/common.sh:ro" \
    -v "$KEYS/recipients.txt:/tmp/recipients:ro" --entrypoint bash "$RP_AGE_IMAGE" \
    -c "install -m $1 -o root -g root /tmp/recipients /etc/restaurantpro/backup-recipients.txt && bash /etc/restaurantpro/preflight.sh" 2>&1
}
if out="$(preflight_with 600)"; then fail "the preflight accepted a recipients file postgres cannot read"; fi
[[ "$out" == *"postgres user"*"cannot read"* ]] || fail "unexpected: $out"
out="$(preflight_with 644)" || fail "the preflight refused a readable 0644 recipients file: $out"
ok "root-only 0600 recipients file: $(grep -m1 'REFUSING' <<<"$(preflight_with 600 || true)" | cut -c1-120); 0644: accepted"

step "starting the postgres service in production with both recipients"
compose up -d --no-build postgres >/dev/null 2>&1 || fail "compose up failed: $(compose up -d --no-build postgres 2>&1 | tail -3)"
SRC="$(compose ps -q postgres)"
wait_ready "$SRC"
docker logs "$SRC" 2>&1 | grep -m1 'preflight' | sed 's/^/    /' >&2 || true
WAL_MAGIC="$(docker exec "$SRC" sh -c 'f=$(ls "$PGDATA/pg_wal" | grep -E "^[0-9A-F]{24}$" | head -1); head -c 2 "$PGDATA/pg_wal/$f"' | od -An -tx1 | tr -d ' \n')"
export WAL_MAGIC
[[ "$WAL_MAGIC" =~ ^[0-9a-f]{4}$ ]] || fail "cannot read the WAL page magic"
ok "postgres ready; WAL page magic of this server: $WAL_MAGIC"

step "1b/2b. the backup scripts against the running server"
for script in backup.sh basebackup.sh; do
  writes_nothing "$script, no recipients file" 'no recipients file' \
    env BACKUP_AGE_RECIPIENTS_FILE="$WORK/no-such-file.txt" "$DEPLOY_DIR/$script" --container "$SRC" --dir "$NOTHING"
  writes_nothing "$script, one recipient" 'holds 1 distinct recipient' \
    "$DEPLOY_DIR/$script" --recipients-file "$KEYS/one-recipient.txt" --container "$SRC" --dir "$NOTHING"
  writes_nothing "$script, BACKUP_AGE_RECIPIENT in production" 'test mode only' \
    env BACKUP_AGE_RECIPIENT="$(age_public_key "$KEYS/operational.key")" "$DEPLOY_DIR/$script" --container "$SRC" --dir "$NOTHING"
done

step "3. two recipients: everything written is ciphertext"
psql_in "$SRC" <"$DEPLOY_DIR/test/sample.sql"
q "$SRC" "CREATE SCHEMA drill; CREATE TABLE drill.pitr_event (id int PRIMARY KEY, label text NOT NULL,
          committed_at timestamptz NOT NULL DEFAULT clock_timestamp())" >/dev/null
write "$SRC" 1 >/dev/null
base="$("$DEPLOY_DIR/basebackup.sh" --new-cluster --container "$SRC" --dir "$BACKUPS/pitr" 2>"$WORK/base.err")" \
  || fail "basebackup.sh --new-cluster: $(cat "$WORK/base.err")"
[[ "$base" == *.tar.age && -f "${base%.age}.sha256" ]] || fail "base backup is not .tar.age with a sidecar: $base"
write "$SRC" 2 >/dev/null
at_t="$(fingerprint "$SRC")"
target="$(now_utc "$SRC")"
write "$SRC" 3 >/dev/null
switch_and_wait "$SRC"
"$DEPLOY_DIR/basebackup.sh" --wal-only --container "$SRC" --dir "$BACKUPS/pitr" 2>/dev/null
logical="$("$DEPLOY_DIR/backup.sh" --container "$SRC" --dir "$BACKUPS" 2>/dev/null)"
[[ "$logical" == *.tar.age && -f "${logical%.age}.sha256" ]] || fail "logical backup is not .tar.age with a sidecar: $logical"
ok "base $(basename "$base"), $(find "$BACKUPS/pitr/wal" -name '*.age' | wc -l | tr -d ' ') WAL file(s), logical $(basename "$logical")"
volume_copy="$(copy_out "$SRC" /wal-archive)"
assert_no_plaintext "$volume_copy" || fail "plaintext in the pg-wal-archive volume"
ok "pg-wal-archive volume: $(find "$volume_copy" -type f | wc -l | tr -d ' ') files, all ciphertext, sidecars or markers"
assert_no_plaintext "$BACKUPS" || fail "plaintext in the backup directory"
ok "backup directory: $(find "$BACKUPS" -type f | wc -l | tr -d ' ') files, all ciphertext, sidecars or markers"
rm -rf "$volume_copy"
[[ "$(file_mode "$BACKUPS")" == 700 && "$(file_mode "$logical")" == 600 ]] || fail "backups are not 0700/0600"

step "4. an identity on the server or loose permissions: refused"
mkdir -m 700 "$WORK/deploy-copy"
cp "$DEPLOY_DIR"/*.sh "$WORK/deploy-copy/"
cp -R "$DEPLOY_DIR/lib" "$DEPLOY_DIR/postgres" "$DEPLOY_DIR/test" "$WORK/deploy-copy/"
cp "$KEYS/operational.key" "$WORK/deploy-copy/forgotten-key.txt"
for script in backup.sh basebackup.sh; do
  writes_nothing "$script, an identity file under deploy/" 'holds an age identity' \
    "$WORK/deploy-copy/$script" --container "$SRC" --dir "$NOTHING"
  writes_nothing "$script, BACKUP_AGE_IDENTITY set" 'BACKUP_AGE_IDENTITY is set' \
    env BACKUP_AGE_IDENTITY="$KEYS/operational.key" "$DEPLOY_DIR/$script" --container "$SRC" --dir "$NOTHING"
done
rm "$WORK/deploy-copy/forgotten-key.txt"
echo RESTAURANTPRO_ENVIRONMENT=test >"$WORK/deploy-copy/.env"
chmod 666 "$WORK/deploy-copy/.env"
writes_nothing "backup.sh, a world-writable deploy/.env" 'env is writable by group or others' \
  env -u RESTAURANTPRO_ENVIRONMENT "$WORK/deploy-copy/backup.sh" --container "$SRC" --dir "$NOTHING"
rm "$WORK/deploy-copy/.env"
chmod g+w "$WORK/deploy-copy/lib/age.sh"
writes_nothing "backup.sh, a group-writable script" 'writable by group or others' \
  "$WORK/deploy-copy/backup.sh" --container "$SRC" --dir "$NOTHING"
rm -rf "$WORK/deploy-copy"
chmod 755 "$BACKUPS"
if "$DEPLOY_DIR/backup.sh" --container "$SRC" --dir "$BACKUPS" 2>"$WORK/loose.err" >/dev/null; then fail "backup.sh accepted a 0755 backup directory"; fi
grep -q 'must be 0700. Fix: ' "$WORK/loose.err" || fail "unexpected: $(cat "$WORK/loose.err")"
chmod 700 "$BACKUPS"
ok "0755 backup directory: $(grep -m1 'must be 0700' "$WORK/loose.err" | cut -c1-120)"
chmod 644 "$logical"
if "$DEPLOY_DIR/basebackup.sh" --wal-only --container "$SRC" --dir "$BACKUPS/pitr" 2>/dev/null \
  && "$DEPLOY_DIR/backup.sh" --container "$SRC" --dir "$BACKUPS" 2>"$WORK/loose.err" >/dev/null; then
  fail "backup.sh accepted a 0644 backup file"
fi
grep -q 'must be 0600. Fix: ' "$WORK/loose.err" || fail "unexpected: $(cat "$WORK/loose.err")"
chmod 600 "$logical"
ok "0644 backup file: $(grep -m1 'must be 0600' "$WORK/loose.err" | cut -c1-120)"
cp "$KEYS/recipients.txt" "$WORK/loose-recipients.txt"
chmod 664 "$WORK/loose-recipients.txt"
writes_nothing "basebackup.sh, group-writable recipients file" 'not writable by group or others' \
  "$DEPLOY_DIR/basebackup.sh" --recipients-file "$WORK/loose-recipients.txt" --container "$SRC" --dir "$NOTHING"
writes_nothing "backup.sh, group-writable recipients file" 'not writable by group or others' \
  "$DEPLOY_DIR/backup.sh" --recipients-file "$WORK/loose-recipients.txt" --container "$SRC" --dir "$NOTHING"
cp "$KEYS/operational.key" "$KEYS/loose.key"
chmod 644 "$KEYS/loose.key"
if "$DEPLOY_DIR/pitr-restore.sh" --target-time latest --name "$PITR_NAME" --wal-dir "$BACKUPS/pitr/wal" \
  --identity "$KEYS/loose.key" "$base" 2>"$WORK/loose.err" >/dev/null; then fail "pitr-restore.sh accepted a 0644 identity"; fi
grep -q 'must be 0600 or 0400' "$WORK/loose.err" || fail "unexpected: $(cat "$WORK/loose.err")"
docker volume inspect "$PITR_NAME-data" >/dev/null 2>&1 && fail "the refused restore created a volume"
if "$DEPLOY_DIR/restore.sh" --container "$SRC" --identity "$KEYS/loose.key" "$logical" 2>"$WORK/loose.err"; then
  fail "restore.sh accepted a 0644 identity"
fi
grep -q 'must be 0600 or 0400' "$WORK/loose.err" || fail "unexpected: $(cat "$WORK/loose.err")"
ok "0644 identity: refused by pitr-restore.sh and restore.sh ($(head -1 "$WORK/loose.err" | cut -c1-110))"
cp "$KEYS/operational.key" "$BACKUPS/pitr/inside.key"
if "$DEPLOY_DIR/pitr-restore.sh" --target-time latest --name "$PITR_NAME" --wal-dir "$BACKUPS/pitr/wal" \
  --identity "$BACKUPS/pitr/inside.key" "$base" 2>"$WORK/inside.err" >/dev/null; then fail "pitr-restore.sh accepted an identity inside the backups"; fi
grep -q 'keep it outside deploy/ and the backups' "$WORK/inside.err" || fail "unexpected: $(cat "$WORK/inside.err")"
rm "$BACKUPS/pitr/inside.key"
ok "identity inside the backup directory: refused"

step "5. restore to T with the operational key, then with the recovery key alone"
t0=$SECONDS
"$DEPLOY_DIR/pitr-restore.sh" --target-time "$target" --name "$PITR_NAME" --wal-dir "$BACKUPS/pitr/wal" \
  --identity "$KEYS/operational.key" "$base" >/dev/null 2>"$WORK/op.err" || fail "restore with the operational key: $(tail -5 "$WORK/op.err")"
[[ "$(rows "$PITR_NAME")" == "1,2" && "$(fingerprint "$PITR_NAME")" == "$at_t" ]] || fail "operational-key restore differs from T"
ok "operational key: rows $(rows "$PITR_NAME"), every table equal to the fingerprint at T (${target}) in $((SECONDS - t0))s"
t0=$SECONDS
"$DEPLOY_DIR/pitr-restore.sh" --force --target-time "$target" --name "$PITR_NAME" --wal-dir "$BACKUPS/pitr/wal" \
  --identity "$KEYS/recovery-only.key" "$base" >/dev/null 2>"$WORK/rec.err" || fail "restore with the recovery key: $(tail -5 "$WORK/rec.err")"
[[ "$(rows "$PITR_NAME")" == "1,2" && "$(fingerprint "$PITR_NAME")" == "$at_t" ]] || fail "recovery-key restore differs from T"
ok "recovery key alone: the same fingerprint at T in $((SECONDS - t0))s"
docker exec "$PITR_NAME" createdb -U "$DB_USER" logical_check
"$DEPLOY_DIR/restore.sh" --container "$PITR_NAME" --db logical_check --identity "$KEYS/recovery-only.key" "$logical" 2>/dev/null \
  || fail "restore.sh of the logical archive with the recovery key failed"
[[ "$(docker exec "$PITR_NAME" psql -X -At -U "$DB_USER" -d logical_check -c 'SELECT count(*) FROM drill.pitr_event')" == 3 ]] \
  || fail "logical restore with the recovery key has the wrong rows"
ok "restore.sh of $(basename "$logical") with the recovery key alone: 3 rows"
docker rm -f -v "$PITR_NAME" >/dev/null
docker volume rm "$PITR_NAME-data" >/dev/null

step "6. a wrong key fails cleanly"
if "$DEPLOY_DIR/pitr-restore.sh" --target-time latest --name "$PITR_NAME" --wal-dir "$BACKUPS/pitr/wal" \
  --identity "$KEYS/wrong.key" "$base" >/dev/null 2>"$WORK/wrong.err"; then fail "pitr-restore.sh succeeded with a wrong key"; fi
grep -q 'cannot decrypt' "$WORK/wrong.err" || fail "unexpected: $(tail -5 "$WORK/wrong.err")"
docker container inspect "$PITR_NAME" >/dev/null 2>&1 && fail "the failed restore left container $PITR_NAME"
docker volume inspect "$PITR_NAME-data" >/dev/null 2>&1 && fail "the failed restore left volume $PITR_NAME-data"
if "$DEPLOY_DIR/restore.sh" --container "$SRC" --identity "$KEYS/wrong.key" "$logical" 2>"$WORK/wrong.err"; then fail "restore.sh succeeded with a wrong key"; fi
grep -q 'cannot decrypt' "$WORK/wrong.err" || fail "unexpected: $(tail -3 "$WORK/wrong.err")"
ok "wrong key: $(grep -m1 'cannot decrypt' "$WORK/wrong.err" | cut -c1-100); no container or volume left"
mkdir -m 700 "$WORK/tampered"
cp -p "$BACKUPS/pitr/wal/"* "$WORK/tampered/"
victim="$(find "$WORK/tampered" -name '*.sha256' | sort | tail -1)"
{ printf '%064d  %s\n' 0 "$(basename "${victim%.sha256}")"; sed -n 2p "$victim"; } >"$victim.new" && mv "$victim.new" "$victim"
if "$DEPLOY_DIR/pitr-restore.sh" --target-time latest --name "$PITR_NAME" --wal-dir "$WORK/tampered" \
  --identity "$KEYS/operational.key" "$base" >/dev/null 2>"$WORK/tampered.err"; then fail "pitr-restore.sh accepted WAL whose sidecar does not match"; fi
grep -q 'checksum mismatch' "$WORK/tampered.err" || fail "unexpected: $(tail -5 "$WORK/tampered.err")"
docker volume inspect "$PITR_NAME-data" >/dev/null 2>&1 && fail "the failed restore left volume $PITR_NAME-data"
rm -rf "$WORK/tampered"
ok "a WAL file that does not match its sidecar: refused ($(grep -m1 'checksum mismatch' "$WORK/tampered.err" | sed 's|/wal-source/||' | cut -c1-90))"
mkdir -m 700 "$WORK/plainwal"
cp -p "$BACKUPS/pitr/wal/"* "$WORK/plainwal/"
(umask 077 && head -c 8192 /dev/urandom >"$WORK/plainwal/000000010000000000000099")
if "$DEPLOY_DIR/pitr-restore.sh" --target-time latest --name "$PITR_NAME" --wal-dir "$WORK/plainwal" \
  --identity "$KEYS/operational.key" "$base" >/dev/null 2>"$WORK/plainwal.err"; then fail "pitr-restore.sh accepted plaintext WAL in production"; fi
grep -q 'plaintext WAL' "$WORK/plainwal.err" || fail "unexpected: $(tail -5 "$WORK/plainwal.err")"
rm -rf "$WORK/plainwal"
ok "plaintext WAL next to an encrypted base in production: refused"
mkdir -m 700 "$WORK/nosidecar" "$WORK/nosidecar/base"
cp -p "$logical" "$WORK/nosidecar/"
cp -p "$base" "$WORK/nosidecar/base/"
if "$DEPLOY_DIR/restore.sh" --container "$SRC" --identity "$KEYS/operational.key" "$WORK/nosidecar/$(basename "$logical")" 2>"$WORK/nosidecar.err"; then
  fail "restore.sh accepted an archive without its sidecar in production"
fi
grep -q 'no sidecar' "$WORK/nosidecar.err" || fail "unexpected: $(cat "$WORK/nosidecar.err")"
if "$DEPLOY_DIR/pitr-restore.sh" --target-time latest --name "$PITR_NAME" --wal-dir "$BACKUPS/pitr/wal" --identity "$KEYS/operational.key" \
  "$WORK/nosidecar/base/$(basename "$base")" >/dev/null 2>"$WORK/nosidecar.err"; then fail "pitr-restore.sh accepted a base without its sidecar"; fi
grep -q 'no sidecar' "$WORK/nosidecar.err" || fail "unexpected: $(cat "$WORK/nosidecar.err")"
docker volume inspect "$PITR_NAME-data" >/dev/null 2>&1 && fail "the refused restore left volume $PITR_NAME-data"
rm -rf "$WORK/nosidecar"
ok "a base or logical archive without its sidecar: refused by pitr-restore.sh and restore.sh"
mkdir -m 700 "$WORK/flipped" "$WORK/flipped/base" "$WORK/flipped/wal"
cp -p "$base" "${base%.age}.sha256" "$WORK/flipped/base/"
printf garbage | dd of="$WORK/flipped/base/$(basename "$base")" bs=1 seek=300 conv=notrunc status=none
if "$DEPLOY_DIR/pitr-restore.sh" --target-time latest --name "$PITR_NAME" --wal-dir "$BACKUPS/pitr/wal" --identity "$KEYS/operational.key" \
  "$WORK/flipped/base/$(basename "$base")" >/dev/null 2>"$WORK/flipped.err"; then fail "pitr-restore.sh accepted a damaged base ciphertext"; fi
grep -q 'checksum mismatch: .*ciphertext' "$WORK/flipped.err" || fail "unexpected: $(cat "$WORK/flipped.err")"
cp -p "$BACKUPS/pitr/wal/"* "$WORK/flipped/wal/"
f="$(find "$WORK/flipped/wal" -name '*.age' | sort | tail -1)"
printf garbage | dd of="$f" bs=1 seek=300 conv=notrunc status=none
if "$DEPLOY_DIR/pitr-restore.sh" --target-time latest --name "$PITR_NAME" --wal-dir "$WORK/flipped/wal" --identity "$KEYS/operational.key" \
  "$base" >/dev/null 2>"$WORK/flipped.err"; then fail "pitr-restore.sh accepted a damaged WAL ciphertext"; fi
grep -q "checksum mismatch against $(basename "${f%.age}").sha256" "$WORK/flipped.err" || fail "unexpected: $(tail -3 "$WORK/flipped.err")"
docker volume inspect "$PITR_NAME-data" >/dev/null 2>&1 && fail "the refused restore left volume $PITR_NAME-data"
rm -rf "$WORK/flipped"
ok "damaged base or WAL ciphertext: refused by pitr-restore.sh before decryption (sidecar's ciphertext SHA-256)"

step "8. offbox-sync.sh over ssh to an rrsync destination"
mkdir "$WORK/sshd"
cat >"$WORK/sshd/Dockerfile" <<'DOCKERFILE'
FROM postgres:18.6-alpine
RUN apk add --no-cache openssh-server=10.3_p1-r2 rsync=3.5.0-r0 rrsync=3.5.0-r0 \
 && ssh-keygen -A && adduser -D -s /bin/sh offbox && sed -i 's/^offbox:!/offbox:*/' /etc/shadow \
 && mkdir -p /srv/offbox /home/offbox/.ssh && chown offbox /srv/offbox /home/offbox/.ssh && chmod 700 /home/offbox/.ssh
ENTRYPOINT ["/usr/sbin/sshd", "-D", "-e"]
DOCKERFILE
docker build -q -t "$SSHD_IMAGE" "$WORK/sshd" >/dev/null
docker run -d --name "$SSHD" -p 127.0.0.1::22 --label restaurantpro.drill="$RUN_ID" "$SSHD_IMAGE" >/dev/null
port="$(docker port "$SSHD" 22/tcp | head -1 | sed 's/.*://')"
ssh-keygen -q -t ed25519 -N '' -C drill -f "$WORK/ssh/id_ed25519"
ssh-keygen -q -t ed25519 -N '' -C impostor -f "$WORK/ssh/impostor_host_key"
authorize() { # $1 options in front of the key ("" = an unrestricted login)
  docker exec -i "$SSHD" sh -c 'cat >/home/offbox/.ssh/authorized_keys && chown offbox /home/offbox/.ssh/authorized_keys && chmod 600 /home/offbox/.ssh/authorized_keys' \
    <<<"$1$(cat "$WORK/ssh/id_ed25519.pub")"
}
authorize 'command="rrsync -wo -no-del -no-overwrite /srv/offbox",restrict '
echo "[127.0.0.1]:$port $(docker exec "$SSHD" cat /etc/ssh/ssh_host_ed25519_key.pub | cut -d' ' -f1-2)" >"$WORK/ssh/known_hosts"
echo "[127.0.0.1]:$port $(cut -d' ' -f1-2 "$WORK/ssh/impostor_host_key.pub")" >"$WORK/ssh/impostor_known_hosts"
chmod 644 "$WORK/ssh/known_hosts" "$WORK/ssh/impostor_known_hosts"
for _ in $(seq 1 30); do docker exec "$SSHD" sh -c 'nc -z 127.0.0.1 22' 2>/dev/null && break; sleep 0.5; done
offbox() { "$DEPLOY_DIR/offbox-sync.sh" --dir "$BACKUPS" --target offbox@127.0.0.1: --port "$port" --ssh-key "$WORK/ssh/id_ed25519" "$@"; }
remote_files() { docker exec "$SSHD" find /srv/offbox -type f | wc -l | tr -d ' '; }
if offbox --known-hosts "$WORK/ssh/impostor_known_hosts" 2>"$WORK/impostor.err"; then fail "offbox-sync.sh accepted an unknown host key"; fi
grep -qiE 'host key|REMOTE HOST IDENTIFICATION' "$WORK/impostor.err" || fail "unexpected: $(tail -3 "$WORK/impostor.err")"
[[ "$(remote_files)" == 0 ]] || fail "files reached the destination despite the wrong host key"
ok "unknown host key: refused ($(grep -m1 -iE 'host key' "$WORK/impostor.err" | cut -c1-90)); nothing copied"
offbox --known-hosts "$WORK/ssh/known_hosts" 2>"$WORK/offbox.err" || fail "offbox-sync.sh with the pinned host key: $(tail -5 "$WORK/offbox.err")"
[[ -f "$BACKUPS/LAST_OFFBOX_SYNC" ]] || fail "LAST_OFFBOX_SYNC not written"
remote_copy="$(copy_out "$SSHD" /srv/offbox)"
sysid="$(cat "$BACKUPS/pitr/SYSTEM_IDENTIFIER")"
[[ -f "$remote_copy/$(basename "$logical")" && -f "$remote_copy/pitr-$sysid/base/$(basename "$base")" ]] || fail "the off-box copy lacks the backups"
[[ -z "$(find "$remote_copy" -name LAST_OFFBOX_SYNC -o -name RECIPIENTS_SHA256 -o -name ARCHIVER_FAILED_COUNT)" ]] \
  || fail "markers that change in place reached the write-once destination"
assert_no_plaintext "$remote_copy" || fail "plaintext at the off-box destination"
ok "pinned host key: $(remote_files) files copied, all ciphertext, sidecars or markers (3: volume, backups, off-box all clean)"
rm -rf "$remote_copy"
echo "plaintext" >"$BACKUPS/notes.txt"
chmod 600 "$BACKUPS/notes.txt"
if offbox --known-hosts "$WORK/ssh/known_hosts" 2>"$WORK/plain.err"; then fail "offbox-sync.sh copied a plaintext file"; fi
grep -q 'not age ciphertext' "$WORK/plain.err" || fail "unexpected: $(tail -3 "$WORK/plain.err")"
rm "$BACKUPS/notes.txt"
ok "a plaintext file in the backup directory: refused"
# Ransomware on the server: the logical archive is replaced by other ciphertext with a forged sidecar.
remote_sum() { docker exec "$SSHD" sha256sum "/srv/offbox/$1" | cut -d' ' -f1; }
good_sum="$(remote_sum "$(basename "$logical")")"
cp -p "$logical" "$WORK/logical.keep"
cp -p "${logical%.age}.sha256" "$WORK/sidecar.keep"
head -c 4096 /dev/urandom >"$WORK/ransom.bin"
seal "$WORK/ransom.bin" "${logical%.age}" "$KEYS/recipients.txt"
offbox --known-hosts "$WORK/ssh/known_hosts" 2>"$WORK/ransom.err" || fail "offbox-sync.sh after the rewrite: $(tail -3 "$WORK/ransom.err")"
[[ "$(remote_sum "$(basename "$logical")")" == "$good_sum" ]] || fail "the rewritten archive replaced the off-box copy"
[[ "$(remote_sum "$(basename "${logical%.age}.sha256")")" == "$(sha256_hex "$WORK/sidecar.keep")" ]] || fail "the forged sidecar replaced the off-box copy"
ok "a rewritten archive and its forged sidecar never replace the off-box copy (destination: rrsync -no-overwrite)"
authorize 'command="rrsync -wo -no-del /srv/offbox",restrict ' # the client's --ignore-existing alone
offbox --known-hosts "$WORK/ssh/known_hosts" 2>"$WORK/ransom.err" || fail "offbox-sync.sh after the rewrite: $(tail -3 "$WORK/ransom.err")"
[[ "$(remote_sum "$(basename "$logical")")" == "$good_sum" ]] || fail "the rewritten archive replaced the off-box copy (client side)"
authorize 'command="rrsync -wo -no-del -no-overwrite /srv/offbox",restrict '
mv "$WORK/logical.keep" "$logical"
mv "$WORK/sidecar.keep" "${logical%.age}.sha256"
ok "... and not either behind a destination without -no-overwrite (client: --ignore-existing)"
offbox --known-hosts "$WORK/ssh/known_hosts" --check-append-only 2>"$WORK/ao.err" || fail "--check-append-only behind rrsync: $(tail -5 "$WORK/ao.err")"
ok "--check-append-only behind rrsync -wo -no-del -no-overwrite: $(grep -m1 'write-once' "$WORK/ao.err")"
authorize 'command="rrsync -wo -no-del /srv/offbox",restrict '
if offbox --known-hosts "$WORK/ssh/known_hosts" --check-append-only 2>"$WORK/ao.err"; then fail "--check-append-only passed without -no-overwrite"; fi
grep -q 'could overwrite the canary' "$WORK/ao.err" || fail "unexpected: $(tail -3 "$WORK/ao.err")"
ok "--check-append-only without -no-overwrite: $(grep -m1 'could overwrite' "$WORK/ao.err" | cut -c1-110)"
authorize ''
if offbox --known-hosts "$WORK/ssh/known_hosts" --check-append-only 2>"$WORK/ao.err"; then fail "--check-append-only passed on an unrestricted login"; fi
grep -q 'not write-once' "$WORK/ao.err" || fail "unexpected: $(tail -3 "$WORK/ao.err")"
ok "--check-append-only without rrsync: $(grep -m1 'not write-once' "$WORK/ao.err" | cut -c1-110)"
authorize 'command="rrsync -wo -no-del -no-overwrite /srv/offbox",restrict '

step "7. verify-backups.sh catches a swapped recipients file"
verify() { "$DEPLOY_DIR/verify-backups.sh" --container "$SRC" --dir "$BACKUPS" "$@"; }
verify 2>"$WORK/verify.err" || fail "verify-backups.sh on healthy backups: $(cat "$WORK/verify.err")"
ok "healthy: $(tail -1 "$WORK/verify.err")"
cp "$KEYS/recipients.txt" "$WORK/recipients.orig"
(umask 077 && age_keygen >"$KEYS/attacker.key")
{ age_public_key "$KEYS/operational.key"; age_public_key "$KEYS/attacker.key"; } >"$KEYS/recipients.txt"
if verify 2>"$WORK/verify.err"; then fail "verify-backups.sh accepted a swapped recipients file"; fi
grep -q 'recipients changed' "$WORK/verify.err" || fail "unexpected: $(cat "$WORK/verify.err")"
ok "swapped: $(grep -m1 'recipients changed' "$WORK/verify.err" | cut -c1-120)"
if verify 2>/dev/null; then fail "verify-backups.sh forgot the change on the second run"; fi
if "$DEPLOY_DIR/backup.sh" --container "$SRC" --dir "$BACKUPS" 2>"$WORK/swap.err" >/dev/null; then fail "backup.sh ran with swapped recipients"; fi
grep -q 'recipients changed' "$WORK/swap.err" || fail "unexpected: $(cat "$WORK/swap.err")"
if "$DEPLOY_DIR/basebackup.sh" --wal-only --container "$SRC" --dir "$BACKUPS/pitr" 2>"$WORK/swap.err"; then fail "basebackup.sh ran with swapped recipients"; fi
grep -q 'recipients changed' "$WORK/swap.err" || fail "unexpected: $(cat "$WORK/swap.err")"
ok "backup.sh and basebackup.sh refuse the swapped recipients ($(grep -m1 'recipients changed' "$WORK/swap.err" | cut -c1-90))"
verify --accept-recipients-change 2>"$WORK/verify.err" || fail "--accept-recipients-change: $(cat "$WORK/verify.err")"
grep -q 'accepted' "$WORK/verify.err" || fail "the accepted change is not logged"
verify 2>/dev/null || fail "verify-backups.sh still fails after the change was accepted"
"$DEPLOY_DIR/basebackup.sh" --wal-only --container "$SRC" --dir "$BACKUPS/pitr" 2>/dev/null || fail "basebackup.sh still refuses after the change was accepted"
cp "$WORK/recipients.orig" "$KEYS/recipients.txt"
verify --accept-recipients-change 2>/dev/null || fail "could not accept the original recipients again"
ok "fails until --accept-recipients-change ($(grep -m1 accepted "$WORK/verify.err" | cut -c1-100))"
offbox --known-hosts "$WORK/ssh/known_hosts" 2>/dev/null || fail "second off-box sync failed"
write "$SRC" 4 >/dev/null
switch_and_wait "$SRC"
write "$SRC" 5 >/dev/null
switch_and_wait "$SRC"
"$DEPLOY_DIR/basebackup.sh" --wal-only --container "$SRC" --dir "$BACKUPS/pitr" 2>/dev/null
verify 2>/dev/null || fail "verify-backups.sh after more WAL"
broken() { # $1 label, $2 expected problem (regex), $3 a change to a copy of the backups (run in it)
  local copy="$WORK/verify-copy"
  rm -rf "$copy"
  cp -Rp "$BACKUPS" "$copy"
  (cd "$copy" && eval "$3")
  if "$DEPLOY_DIR/verify-backups.sh" --container "$SRC" --dir "$copy" 2>"$WORK/broken.err"; then fail "verify-backups.sh missed: $1"; fi
  grep -qE "$2" "$WORK/broken.err" || fail "verify-backups.sh, $1: unexpected: $(cat "$WORK/broken.err")"
  ok "verify-backups.sh alerts on $1: $(grep -m1 -E "$2" "$WORK/broken.err" | sed 's/^verify-backups: PROBLEM: //' | cut -c1-100)"
  rm -rf "$copy"
}
old_stamp="$(date -v-2H +%Y%m%d%H%M 2>/dev/null || date -d '-2 hours' +%Y%m%d%H%M)"
broken "a stale off-box copy" 'last off-box copy is older' "touch -t $old_stamp LAST_OFFBOX_SYNC"
broken "a plaintext file" 'not age ciphertext: .*notes.txt' '(umask 077; echo x >notes.txt)'
# shellcheck disable=SC2016 # evaluated by broken() in the copy
broken "a gap in the WAL chain" 'WAL chain .* is broken' \
  'f=$(ls pitr/wal | grep -E "^[0-9A-F]{24}\.age$" | sort | sed -n 2p); rm "pitr/wal/$f" "pitr/wal/${f%.age}.sha256"'
# shellcheck disable=SC2016 # evaluated by broken() in the copy
broken "tampered ciphertext (key-free)" 'does not match the ciphertext SHA-256' \
  'f=$(ls pitr/wal/*.age | head -1); printf garbage | dd of="$f" bs=1 seek=200 conv=notrunc status=none'
# shellcheck disable=SC2016 # evaluated by broken() in the copy
broken "a malformed sidecar" 'malformed sidecar|no well-formed' 'f=$(ls pitr/wal/*.sha256 | head -1); echo extra >>"$f"'
broken "ciphertext without a recorded recipients fingerprint" 'holds encrypted backups but no RECIPIENTS_SHA256' 'rm RECIPIENTS_SHA256'
broken "a base backup older than 26 h" 'newest base backup is older than 26 h' \
  "touch -t $(date -v-27H +%Y%m%d%H%M 2>/dev/null || date -d '-27 hours' +%Y%m%d%H%M) pitr/base/*"

step "9. dev mode, then the upgrade of an S27-style plaintext installation"
LEGACY="$WORK/legacy"
mkdir -m 700 "$LEGACY"
RESTAURANTPRO_ENVIRONMENT=test BACKUP_AGE_RECIPIENTS_FILE=/dev/null legacy up -d --no-build postgres >/dev/null 2>&1 \
  || fail "dev mode: compose up failed: $(RESTAURANTPRO_ENVIRONMENT=test BACKUP_AGE_RECIPIENTS_FILE=/dev/null legacy up -d --no-build postgres 2>&1 | tail -3)"
OLD="$(legacy ps -q postgres)"
wait_ready "$OLD"
[[ "$(docker logs "$OLD" 2>&1)" == *"WARNING: test mode without recipients"* ]] || fail "dev mode started without a plaintext WARNING"
q "$OLD" "CREATE SCHEMA drill; CREATE TABLE drill.pitr_event (id int PRIMARY KEY, label text NOT NULL,
          committed_at timestamptz NOT NULL DEFAULT clock_timestamp())" >/dev/null
write "$OLD" 1 >/dev/null
dev() { RESTAURANTPRO_ENVIRONMENT=test BACKUP_AGE_RECIPIENTS_FILE=/dev/null "$@"; }
old_base="$(dev "$DEPLOY_DIR/basebackup.sh" --new-cluster --container "$OLD" --dir "$LEGACY/pitr" 2>"$WORK/dev.err")" \
  || fail "dev mode basebackup.sh: $(cat "$WORK/dev.err")"
grep -q 'WARNING' "$WORK/dev.err" || fail "dev mode basebackup.sh did not warn about plaintext"
write "$OLD" 2 >/dev/null
old_t="$(now_utc "$OLD")"
write "$OLD" 3 >/dev/null
switch_and_wait "$OLD"
dev "$DEPLOY_DIR/basebackup.sh" --wal-only --container "$OLD" --dir "$LEGACY/pitr" 2>/dev/null
old_logical="$(dev "$DEPLOY_DIR/backup.sh" --container "$OLD" --dir "$LEGACY" 2>"$WORK/dev.err")"
grep -q 'WARNING' "$WORK/dev.err" || fail "dev mode backup.sh did not warn about plaintext"
[[ "$old_base" == *.tar && "$old_logical" == *.tar ]] || fail "dev mode did not write plaintext archives"
docker run -d --name "$RUN_ID-dev" --network none -e POSTGRES_PASSWORD=dev -e POSTGRES_USER="$DB_USER" -e POSTGRES_DB="$DB" \
  --label restaurantpro.drill="$RUN_ID" "$RP_AGE_IMAGE" >/dev/null
wait_ready "$RUN_ID-dev"
dev "$DEPLOY_DIR/restore.sh" --container "$RUN_ID-dev" "$old_logical" 2>/dev/null || fail "dev mode restore.sh failed"
[[ "$(rows "$RUN_ID-dev")" == "1,2,3" ]] || fail "dev mode restore has rows $(rows "$RUN_ID-dev")"
docker rm -f -v "$RUN_ID-dev" >/dev/null
[[ -n "$(docker exec "$OLD" sh -c 'ls /wal-archive | grep -E "^[0-9A-F]{24}$"')" ]] || fail "dev mode did not archive plaintext WAL"
ok "dev mode (RESTAURANTPRO_ENVIRONMENT=test, no recipients): starts, warns, archives and backs up in plaintext, restores"
legacy up -d --no-build postgres >/dev/null 2>&1 || fail "production compose up of the upgraded stack failed"
OLD="$(legacy ps -q postgres)"
wait_ready "$OLD"
write "$OLD" 4 >/dev/null
switch_and_wait "$OLD"
if "$DEPLOY_DIR/basebackup.sh" --container "$OLD" --dir "$LEGACY/pitr" 2>"$WORK/legacy.err" >/dev/null; then
  fail "basebackup.sh in production accepted plaintext backups"
fi
grep -q 'encrypt-legacy' "$WORK/legacy.err" || fail "unexpected: $(cat "$WORK/legacy.err")"
ok "upgraded to production: basebackup.sh refuses ($(grep -m1 'encrypt-legacy' "$WORK/legacy.err" | cut -c1-110))"
new_base="$(BACKUP_DIR="$LEGACY" "$DEPLOY_DIR/basebackup.sh" --encrypt-legacy --container "$OLD" --dir "$LEGACY/pitr" 2>"$WORK/legacy.err")" \
  || fail "--encrypt-legacy: $(cat "$WORK/legacy.err")"
grep 'encrypt-legacy' "$WORK/legacy.err" | sed 's/^/    /' >&2 || true
volume_copy="$(copy_out "$OLD" /wal-archive)"
assert_no_plaintext "$volume_copy" "$LEGACY" || fail "plaintext left after --encrypt-legacy"
rm -rf "$volume_copy"
[[ -f "$old_base.age" && ! -e "$old_base" && -f "$old_logical.age" && ! -e "$old_logical" ]] || fail "legacy archives not encrypted in place"
ok "--encrypt-legacy: volume, base, WAL and logical archives are ciphertext; new base $(basename "$new_base")"
"$DEPLOY_DIR/backup.sh" --container "$OLD" --dir "$LEGACY" 2>"$WORK/legacy.err" >/dev/null || fail "production backup.sh after --encrypt-legacy: $(cat "$WORK/legacy.err")"
ok "production backup.sh runs in the upgraded logical directory (its recipients were recorded)"
"$DEPLOY_DIR/pitr-restore.sh" --target-time "$old_t" --name "$PITR_NAME" --wal-dir "$LEGACY/pitr/wal" \
  --identity "$KEYS/recovery-only.key" "$old_base.age" >/dev/null 2>"$WORK/legacy.err" || fail "restore of the legacy base: $(tail -5 "$WORK/legacy.err")"
[[ "$(rows "$PITR_NAME")" == "1,2" ]] || fail "legacy restore to T has rows $(rows "$PITR_NAME")"
ok "the encrypted legacy base restores to its T: rows $(rows "$PITR_NAME")"
docker rm -f -v "$PITR_NAME" >/dev/null
docker volume rm "$PITR_NAME-data" >/dev/null
legacy down -v >/dev/null 2>&1

step "10. verify-restore.sh --key-recovery on the off-box copy, recovery key alone"
from="$(copy_out "$SSHD" /srv/offbox)"
mkdir -m 700 "$WORK/reports"
retrieval_started="$(date -u +%Y-%m-%dT%H:%M:%SZ)" # the custodian starts retrieving the recovery key from escrow
"$DEPLOY_DIR/verify-restore.sh" --from "$from" --identity "$KEYS/recovery-only.key" --key-recovery \
  --retrieval-started "$retrieval_started" --name "$RUN_ID-verify" --report-dir "$WORK/reports" 2>"$WORK/drill.err" \
  || fail "verify-restore.sh --key-recovery: $(tail -15 "$WORK/drill.err")"
report="$(find "$WORK/reports" -name 'drill-*.json' | head -1)"
mkdir -m 700 "$WORK/reports-weekly"
"$DEPLOY_DIR/verify-restore.sh" --from "$from" --identity "$KEYS/operational.key" --name "$RUN_ID-verify" \
  --report-dir "$WORK/reports-weekly" 2>"$WORK/weekly.err" || fail "weekly verify-restore.sh: $(tail -15 "$WORK/weekly.err")"
weekly="$(cat "$WORK/reports-weekly"/drill-*.json)"
[[ "$weekly" == *'"mode": "weekly"'* && "$weekly" == *'"result": "PASS"'* ]] || fail "weekly report: $weekly"
ok "weekly verify-restore.sh with the operational key: PASS ($(grep -m1 '^verify-restore: PITR' "$WORK/weekly.err" | cut -c1-120))"
mkdir -m 700 "$WORK/reports-wrong"
if "$DEPLOY_DIR/verify-restore.sh" --from "$from" --identity "$KEYS/wrong.key" --name "$RUN_ID-verify" \
  --report-dir "$WORK/reports-wrong" 2>"$WORK/drill-wrong.err"; then fail "verify-restore.sh passed with a key that is not a recipient"; fi
grep -q 'is not one of the current recipients' "$WORK/drill-wrong.err" || fail "unexpected: $(tail -5 "$WORK/drill-wrong.err")"
grep -q '"result": "FAIL"' "$WORK/reports-wrong"/drill-*.json || fail "the failed drill wrote no FAIL report"
ok "verify-restore.sh with a key that is not a recipient: FAIL report ($(grep -m1 'not one of' "$WORK/drill-wrong.err" | cut -c1-90))"
find "$from" -name 'RECIPIENTS-*' -exec mv {} "$WORK/" \;
if "$DEPLOY_DIR/verify-restore.sh" --from "$from" --identity "$KEYS/operational.key" --name "$RUN_ID-verify" \
  --report-dir "$WORK/reports-wrong" 2>"$WORK/drill-norec.err"; then fail "verify-restore.sh passed without the recipients copy"; fi
grep -q 'no RECIPIENTS-' "$WORK/drill-norec.err" || fail "unexpected: $(tail -5 "$WORK/drill-norec.err")"
mv "$WORK"/RECIPIENTS-* "$from/"
ok "verify-restore.sh without the copied recipients: FAIL ($(grep -m1 'no RECIPIENTS-' "$WORK/drill-norec.err" | cut -c1-80))"
[[ -f "$report" ]] || fail "no drill-<UTC>.json report"
grep -q '"result": "PASS"' "$report" || fail "report does not say PASS: $(cat "$report")"
grep -q "\"key\": \"$(age_public_key "$KEYS/recovery.key")\"" "$report" || fail "report does not name the recovery key"
grep -qE '"retrieval_to_restored_seconds": [0-9]+,' "$report" || fail "report does not time the key retrieval"
sed 's/^/    /' "$report" >&2
[[ -z "$(docker ps -a -q --filter name="$RUN_ID-verify")" ]] || fail "verify-restore.sh left containers"
rm -rf "$from"
ok "key-recovery drill passed with the recovery key alone; report $(basename "$report")"
