#!/bin/sh
# PostgreSQL archive_command (D90, D98 rule 3a): store one finished WAL file in the pg-wal-archive volume,
# encrypted with age to every recipient of /etc/restaurantpro/backup-recipients.txt.
#   archive_command = 'sh /etc/restaurantpro/archive-wal.sh %p %f'
# Encrypts to <f>.age.tmp, then writes the sidecar <f>.sha256 (two lines: the plaintext's and the ciphertext's
# SHA-256) and renames it into place, then renames <f>.age, all flushed, so a complete <f>.age always has its
# sidecar. Exits 0 only once both are on disk: PostgreSQL may recycle the
# segment as soon as this returns. A non-zero exit makes PostgreSQL keep the segment and retry.
# Plaintext <f> (S27 behaviour) only in test mode without recipients; production without recipients fails.
# basebackup.sh --encrypt-legacy also calls this, with %p = the plaintext copy already in the volume.
set -eu
src="$1"
dst="/wal-archive/$2"
dir="$(dirname "$dst")"
recipients=/etc/restaurantpro/backup-recipients.txt

if [ -f "$recipients" ] && grep -q '^age1' "$recipients"; then
  # An S27 plaintext copy of this name: the same file is fine, a different one is another cluster's WAL.
  if [ -e "$dst" ] && [ "$src" != "$dst" ]; then cmp -s "$src" "$dst" || exit 1; fi
  sum="$(sha256sum "$src" | cut -d' ' -f1)"
  if [ -e "$dst.age" ]; then
    # Already archived: a crash after the copy but before PostgreSQL recorded it. Ciphertext is never
    # byte-stable, so compare the sidecar. A different file means the archive holds another cluster's WAL
    # (the database volume was recreated): archiving stops, loudly, until basebackup.sh --new-cluster.
    # Flush again: the earlier run may have died before its own sync.
    [ "$(head -1 "$dst.sha256" 2>/dev/null | cut -d' ' -f1)" = "$sum" ] || exit 1
    sync "$dst.sha256" "$dst.age"
    sync "$dir"
    exit 0
  fi
  age -R "$recipients" -o "$dst.age.tmp" "$src"
  sync "$dst.age.tmp"
  name="$(basename "$dst")"
  printf '%s  %s\n%s  %s\n' "$sum" "$name" "$(sha256sum "$dst.age.tmp" | cut -d' ' -f1)" "$name.age" >"$dst.sha256.tmp"
  sync "$dst.sha256.tmp"
  mv "$dst.sha256.tmp" "$dst.sha256"
  mv "$dst.age.tmp" "$dst.age" # the .age marks the copy complete
  sync "$dir"
  exit 0
fi

[ "${RESTAURANTPRO_ENVIRONMENT:-production}" = test ] || exit 1 # never plaintext in production (D98)
if [ -e "$dst" ]; then
  # Already archived (see above): success only if it is the same file. Flush again.
  cmp -s "$src" "$dst" || exit 1
  sync "$dst"
  sync "$dir"
  exit 0
fi
cp "$src" "$dst.tmp"
sync "$dst.tmp"
mv "$dst.tmp" "$dst" # the archive only ever holds complete files
sync "$dir"
