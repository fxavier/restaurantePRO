#!/bin/sh
# PostgreSQL archive_command (D90): copy one finished WAL file into the pg-wal-archive volume.
#   archive_command = 'sh /etc/restaurantpro/archive-wal.sh %p %f'
# Exits 0 only once the file is complete and flushed in the archive: PostgreSQL may recycle the
# segment as soon as this returns. A non-zero exit makes PostgreSQL keep the segment and retry.
set -eu
src="$1"
dst="/wal-archive/$2"

if [ -e "$dst" ]; then
  # Already archived: a crash after the copy but before PostgreSQL recorded it. Success only if it is
  # the same file; anything else means two clusters share the archive, which must stop archiving.
  exec cmp -s "$src" "$dst"
fi
cp "$src" "$dst.tmp"
sync "$dst.tmp"
mv "$dst.tmp" "$dst" # the archive only ever holds complete files
sync /wal-archive
