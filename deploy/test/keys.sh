# shellcheck shell=bash
# Throwaway age keys for the drills (D98): the drills run in production mode, like an installation.
# Sourced after lib/common.sh and lib/age.sh. Never use these keys for real backups.

# $1 directory outside deploy/ and outside every backup directory. Writes operational.key and recovery.key
# (0600) and recipients.txt (0644, both public keys), and exports what the backup scripts read.
make_throwaway_keys() {
  local d=$1
  (umask 077 && age_keygen >"$d/operational.key" && age_keygen >"$d/recovery.key")
  grep -qE "$RP_AGE_SECRET_KEY" "$d/operational.key" || die "age-keygen did not write an identity"
  {
    echo "# operational key (drill, throwaway)"
    age_public_key "$d/operational.key"
    echo "# recovery key (drill, throwaway)"
    age_public_key "$d/recovery.key"
  } >"$d/recipients.txt"
  chmod 644 "$d/recipients.txt"
  export RESTAURANTPRO_ENVIRONMENT=production BACKUP_AGE_RECIPIENTS_FILE="$d/recipients.txt"
  unset BACKUP_AGE_RECIPIENT BACKUP_AGE_IDENTITY
}

# $@ directories or files: fails when any file is plaintext backup data. Allowed: age ciphertext with its
# .sha256 sidecar, the sidecars and the markers. Also rejects, whatever its name, a file that starts with a
# WAL page magic ($WAL_MAGIC, 4 hex digits), a pg_dump archive (PGDMP), gzip, or holds a tar header.
assert_no_plaintext() {
  local f head4 problems=0
  while IFS= read -r f; do
    head4="$(head -c 2 "$f" | od -An -tx1 | tr -d ' \n')"
    if [[ -n "${WAL_MAGIC:-}" && "$head4" == "$WAL_MAGIC" ]] || [[ "$(head -c 5 "$f")" == PGDMP || "$head4" == 1f8b ]] \
      || [[ "$(dd if="$f" bs=1 skip=257 count=5 2>/dev/null)" == ustar ]]; then
      echo "PLAINTEXT: $f" >&2; problems=$((problems + 1)); continue
    fi
    if is_marker "$f"; then continue; fi
    if ! is_age_file "$f"; then echo "not age ciphertext: $f" >&2; problems=$((problems + 1)); continue; fi
    [[ -f "${f%.age}.sha256" ]] || { echo "no sidecar for $f" >&2; problems=$((problems + 1)); }
  done < <(find "$@" -type f ! -name '*.tmp.*' ! -path '*/.lock/*')
  ((problems == 0))
}

# $1 container, $2 directory inside it: copies it to a fresh directory under the drill's $WORK (removed by the
# drill's trap even when a check fails) and prints its path.
copy_out() {
  local out
  out="$(mktemp -d "$WORK/scan.XXXXXX")"
  docker exec "$1" tar -C "$2" -cf - . | tar -xf - -C "$out"
  echo "$out"
}
