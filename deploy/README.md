# restaurantPRO — operator runbook

One stack per company (spec §2): Caddy (HTTPS on the LAN), the backend, and PostgreSQL. Only Caddy publishes ports (80, 443). PostgreSQL and the backend sit on an internal Docker network with no route to the LAN or the Internet. Run every command from `deploy/` of the installation. The Compose project name is fixed (`restaurantpro`), and `backup.sh`, `restore.sh` and `basebackup.sh` find the database through it (`pitr-restore.sh` finds the `restaurantpro_pg-wal-archive` volume the same way), so run them only from the installation directory and keep one installation per host.

| Image | Pin |
| --- | --- |
| PostgreSQL | `restaurantpro-postgres:18.6-r1`, built from `postgres/Dockerfile`: `postgres:18.6-alpine` (D4, same as the tests) plus `age=1.3.2-r1` (D98) |
| Caddy | `caddy:2.11.7-alpine` |
| Backend | built from `../backend`: `eclipse-temurin:25.0.5_8-jdk-alpine` → `eclipse-temurin:25.0.5_8-jre-alpine`, user uid 10001 |

## First install

1. Install Docker Engine with the Compose plugin. Give the server a fixed LAN address and a name clients can resolve (DNS or hosts file), for example `restaurantpro.lan`.
2. Configure:

   ```bash
   cp .env.example .env          # edit: hostname, company data, admin username, OFFBOX_TARGET
   mkdir -m 700 secrets backups logs   # backups/ holds only ciphertext; the cron logs go to logs/
   openssl rand -base64 32 | tr -d '\n' > secrets/db_password
   printf '%s' 'choose-a-strong-initial-password' > secrets/admin_initial_password
   chmod 644 secrets/*           # readable inside the containers; the 700 directory protects them on the host
   ```

   Then create `backup-recipients.txt` with the two public keys and pin the off-box host key, as described in "Backup encryption (production)" below. In production PostgreSQL refuses to start without that file. `.env`, `secrets/`, `backup-recipients.txt` and `offbox_known_hosts` are git-ignored. Never commit them. The `RESTAURANTPRO_BOOTSTRAP_*` values and `secrets/admin_initial_password` are consumed once, at the first start against an empty database (D30). After that, edit the company profile in the BackOffice. The admin must change the initial password at first login. After that login you may empty `secrets/admin_initial_password`, but keep the file: Compose needs it to start.
3. Start: `docker compose up -d --build`, then wait until `docker compose ps` shows all three services `healthy`. Then start the point-in-time recovery chain once with `./basebackup.sh --new-cluster`, run `./offbox-sync.sh --check-append-only` and `./verify-backups.sh` once by hand, and schedule the jobs in "Backup encryption (production)" below.

   **Fiscal adapter (B4).** This release has only a NON-CERTIFIED fiscal adapter: with the default `RESTAURANTPRO_FISCAL_ALLOW_NON_CERTIFIED=false` the backend refuses to start (its log says why) and never becomes healthy. Set it to `true` in `.env` only for a pilot without fiscal obligations, until a certified adapter for your market exists. Every document is then printed as `DOCUMENTO NÃO FISCAL`, and the health check reports the component `fiscal: NON_CERTIFIED` while the overall status stays `UP`: `docker compose exec backend wget -qO- http://127.0.0.1:8081/actuator/health`.
4. Trust the certificate. Caddy signs with its own local CA (`tls internal`). Install its root certificate once on each POS terminal and BackOffice browser:

   ```bash
   docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./restaurantpro-root.crt
   ```

   The CA lives in the `caddy-data` volume. If you lose that volume, Caddy makes a new root and every client must trust it again.

## Start, stop, status

```bash
docker compose up -d              # start (the services also restart on their own after a reboot)
docker compose stop               # stop, keep data
docker compose ps                 # health
docker compose logs -f backend    # logs (JSON; rotated at 5 x 20 MB per service)
```

Never run `docker compose down -v`: `-v` deletes the database volume.

## Backups

`./backup.sh` writes `backups/restaurantpro-<UTC timestamp>.tar.age`, encrypted with [age](https://age-encryption.org) to every key in `backup-recipients.txt`, next to its sidecar `restaurantpro-<UTC timestamp>.tar.sha256` (the SHA-256 of the plaintext). The archive holds a `pg_dump -Fc` of the database, the configuration (`compose.yaml`, `Caddyfile`, `.env`, `secrets/`) and checksums. The script then deletes archives beyond the newest 14. Retention only counts names in that exact format, so a renamed copy such as `restaurantpro-before-upgrade.tar.age` is never deleted. The dump leaves out the rows of the Spring Session tables: after a restore everyone logs in again. Options: `--dir`, `--keep N`, `--recipients-file` (or the env vars `BACKUP_DIR`, `BACKUP_KEEP`, `BACKUP_AGE_RECIPIENTS_FILE`). It exits non-zero on any failure. In production it refuses, writing nothing, without two recipients, with a private key on the server, or with loose modes (see "Backup encryption (production)").

Schedule it with cron (every night at 02:30):

```cron
30 2 * * * cd /opt/restaurantpro/deploy && ./backup.sh --keep 14 >>logs/backup.log 2>&1
```

Or with a systemd timer:

```ini
# /etc/systemd/system/restaurantpro-backup.service
[Service]
Type=oneshot
User=restaurantpro
WorkingDirectory=/opt/restaurantpro/deploy
ExecStart=/opt/restaurantpro/deploy/backup.sh --keep 14

# /etc/systemd/system/restaurantpro-backup.timer
[Timer]
OnCalendar=*-*-* 02:30:00
Persistent=true
[Install]
WantedBy=timers.target
```

Enable it with `systemctl enable --now restaurantpro-backup.timer`. Check failures with `systemctl status restaurantpro-backup.service`.

These are nightly logical backups: on their own they can lose up to a day of data. The spec's RPO of 15 minutes comes from WAL archiving and point-in-time recovery, next section.

## Point-in-time recovery (WAL archiving)

Spec §11 sets RPO ≤ 15 minutes (D90). PostgreSQL therefore also keeps its write-ahead log (WAL), the record of every change:

- `compose.yaml` runs PostgreSQL with `wal_level=replica`, `archive_mode=on`, `archive_timeout=300` and an `archive_command` (`postgres/archive-wal.sh`) that encrypts each finished 16 MB WAL segment with age into the `pg-wal-archive` volume, as `<segment>.age` plus `<segment>.sha256`. Both are flushed to disk before PostgreSQL may recycle the segment. The sidecar is renamed into place first and the `.age` last, so a `.age` marks a complete copy. The volume holds only ciphertext. `archive_timeout` forces a new segment at least every 5 minutes once anything was written, so at most the last 5 minutes of commits are not yet archived. A failing archive copy never loses data: PostgreSQL keeps the segment and retries (watch `docker compose logs postgres` and `SELECT * FROM pg_stat_archiver`). If it keeps failing, `pg_wal` grows on the database volume until the disk is full.
- `./basebackup.sh` (nightly) takes a base backup with `pg_basebackup`, checks it with `pg_verifybackup`, and writes `backups/pitr/base/restaurantpro-base-<UTC timestamp>-<system id>-<start WAL>.tar.age` and its `.tar.sha256` sidecar. It then copies new WAL files into `backups/pitr/wal/`, keeps the newest 7 base backups (`--keep`, `PITR_KEEP`), and deletes the WAL older than the oldest kept base backup, both in `backups/pitr/wal/` and in the `pg-wal-archive` volume. So you can restore to any moment since the oldest kept base backup.
- `./basebackup.sh --wal-only` (every 5 minutes) only copies the new WAL files into `backups/pitr/wal/`, as they are: the `.age` and `.sha256` files are never decrypted or re-encrypted. `./offbox-sync.sh` copies `backups/` off the machine right after it.
- Only one run works on `backups/pitr/` at a time. The lock is a directory, `backups/pitr/.lock`, holding the owner's pid and that process's start time. A lock is stale when that pid is gone or now belongs to a process with another start time (a pid reused after a reboot). A stale lock is first renamed aside, so two runs can't both clear it, and then deleted. A `--wal-only` run that finds a live lock skips and exits 0, because the run holding it syncs the WAL too. If that lock is older than an hour, it fails instead, so cron reports a stuck run. A base backup waits up to an hour for the lock.
- Each run checks that the archive and `backups/pitr/` belong to the running database cluster. Both hold a `SYSTEM_IDENTIFIER` file, and every base backup name carries the identifier. A default run writes the marker only into an empty archive or directory. It refuses, changing nothing, when they belong to another cluster or hold files without a marker ("unmarked archive"). A new installation has archived some WAL before its first base backup, so **start the chain with `./basebackup.sh --new-cluster`** (see "New database cluster" below). A run also exits non-zero, after its work, while PostgreSQL is failing to archive (`pg_stat_archiver`), so cron mail or the log shows it.
- Plaintext staging happens in a private (0700) temporary directory under `$TMPDIR` (or `/tmp`), never under `backups/`, and a trap removes it. Files appear in `backups/` under a temporary `*.tmp.*` name, are flushed to disk, and are then renamed. `backup.sh` works the same way.

The base backup runs at 02:17 (the schedule is in "Backup encryption (production)") so that it never starts at the same minute as a `--wal-only` run. If it is still running when the next `--wal-only` starts, that run skips (the lock above). `pitr-restore.sh` decrypts with `--identity`, on the restore host.

**RPO and RTO**, as measured by `./test-pitr.sh` (next section) on this machine:

| Incident | Data lost at most | Measured |
| --- | --- | --- |
| Database container or volume lost, the `pg-wal-archive` volume survives | `archive_timeout`: 5 min | 227 s, 283 s, 256 s and 276 s from the last commit to its segment in the archive (four full runs, 2026-11-05; the bound is 300 s) |
| Whole server lost, restore from the off-box copy | `archive_timeout` + the `--wal-only` interval + the off-box copy: 5 + 5 min, plus the copy, with the cron in "Backup encryption (production)" | not measured (depends on your copy job) |

The restore time (RTO) is the unpacking of the base backup plus the replay of the WAL written since it, at most a day with nightly base backups. The drill's database is small (a few MB): `pitr-restore.sh` took 3–8 s on an idle machine and up to 34 s while the backend test suite ran beside it. Measure it on your data by restoring a real backup to a scratch name from time to time.

### New database cluster

Run `./basebackup.sh --new-cluster` once on a new installation. Run it again whenever the database volume is recreated empty (lost, then started fresh): PostgreSQL is then a new cluster, with a new system identifier and WAL names that start again from the beginning. The old cluster's files in the `pg-wal-archive` volume then block archiving. When a new segment has the name of an old one, `archive-wal.sh` refuses to overwrite it, PostgreSQL retries forever, and `pg_wal` grows. `basebackup.sh` refuses to run and says why.

These keep the same cluster, and need nothing:
- a logical restore with `restore.sh`;
- a volume restored with `pitr-restore.sh` and swapped in. It keeps its system identifier and continues on a new timeline, whose WAL names (`00000002…`) don't clash with the old ones.

Once the new cluster is the one to keep:

```bash
./basebackup.sh --new-cluster
```

This moves aside everything that is not provably this cluster's healthy archive: files with another cluster's marker, files without a marker, or an archive PostgreSQL is failing to write into. The WAL in the volume goes to `/wal-archive/old-<old system id or unmarked>-<timestamp>/`. `backups/pitr/base`, `backups/pitr/wal` and the marker go to `backups/pitr/old-<…>-<timestamp>/`. It then takes a base backup of the new cluster and waits until PostgreSQL has archived the blocked segment. It refuses only when both markers already name the running cluster and archiving works. The moved-aside backups still restore to times before the change (`pitr-restore.sh --wal-dir backups/pitr/old-…/wal backups/pitr/old-…/base/…`). Delete both copies once you no longer need them, for example with `docker compose exec postgres rm -rf /wal-archive/old-<…>`.

If `--new-cluster` is interrupted, run it again. The old files may then be split between two `old-*` directories, one from each run, and the second may be named `old-unmarked-…`. Before you restore from them, merge them: move the files of one directory into the other (`mv old-B/wal/* old-A/wal/`, and the same for `base/` and for the volume's directories). The names don't clash, because each file was moved only once.

### Restore to a point in time

```bash
./pitr-restore.sh --target-time 2026-11-05T14:30:00+00:00 --identity /dev/shm/operational.key \
  backups/pitr/base/restaurantpro-base-<timestamp>-<system id>-<wal>.tar.age
```

Pick the newest base backup taken before the target time. The time needs an explicit offset (`Z`, `+00:00`, `+01:00`…); `--target-time latest` replays everything archived. PostgreSQL stops just before the first transaction that committed after the target time. The script creates a new container `restaurantpro-pitr` with no network and its own volume `restaurantpro-pitr-data`, and never touches the live installation. It refuses to run if they already exist unless you pass `--force` (or pick another `--name`). Even with `--force` it replaces only a container and volume that an earlier `pitr-restore.sh --name <same name>` created (label `restaurantpro.pitr`). It never replaces anything else, and above all no Docker Compose object such as the live database container or volume. By default it reads the WAL from the live `pg-wal-archive` volume, which is the most recent. If the server was lost, pass the off-box copy instead: `--wal-dir <copy>/pitr-<system id>/wal`. The identity must be yours, mode 0600 or 0400, and outside `deploy/` and the backups (a tmpfs such as `/dev/shm`). It is mounted read-only into the preparing container alone, which decrypts the base backup and each WAL file (checked against its sidecar) straight into the new volume, and runs `pg_verifybackup` before the start. Plaintext never lands anywhere else. The ciphertext SHA-256 in each sidecar is checked before anything is decrypted. In production, a missing sidecar, or plaintext WAL next to an encrypted base backup, is refused; `restore.sh` likewise requires the archive's sidecar. If the WAL does not reach the target time, PostgreSQL refuses to finish the recovery, and the script fails and removes what it created. The same happens with a wrong key ("cannot decrypt").

The identity is a private key, so the server is never its home (D98). Copy it to a tmpfs for the restore, then shred it: `shred -u /dev/shm/operational.key`. The backup scripts refuse to run while an identity is under `deploy/` or `BACKUP_AGE_IDENTITY` is set.

Check the result with `docker exec -it restaurantpro-pitr psql -U restaurantpro -d restaurantpro`, then bring it into the live database with the logical backup and restore above:

```bash
./backup.sh                                                  # the current state, in case you need it back
./backup.sh --container restaurantpro-pitr --dir backups/pitr-export
docker compose stop backend caddy
./restore.sh --force --identity /dev/shm/operational.key backups/pitr-export/restaurantpro-<timestamp>.tar.age
docker compose start backend caddy
./basebackup.sh                                              # a fresh base backup of the restored state
docker rm -f -v restaurantpro-pitr && docker volume rm restaurantpro-pitr-data
```

Everyone logs in again afterwards: `backup.sh` leaves out the session rows.

### Point-in-time recovery drill

`./test-pitr.sh` is the automated PITR drill (A33). It needs only Docker and starts the `postgres` service of `compose.yaml` itself (throwaway project, secrets and volumes), so it tests the settings that ship. It runs in production mode with throwaway age keys, so every file it checks is encrypted, and it scans the backup directory and the volume for plaintext after each phase. It checks the WAL settings, loads `test/sample.sql` and writes W1. It checks that the first default `basebackup.sh` run refuses the unmarked archive, then takes the base backup with `--new-cluster`. It writes W2, notes a target time T and a fingerprint of every table, writes W3, archives and copies the WAL, then restores to T into an isolated container. W1 and W2 must be present, W3 absent, and every table equal to the fingerprint at T. It then checks that a second restore on the same name is refused without `--force`, that `--force` refuses a volume it did not create and the Compose postgres container, and that a target beyond the archived WAL fails and leaves nothing behind. It also checks that base backup names and both markers carry the system identifier, that no staging or partial files are left under the backup directory, and that `--wal-only` skips behind a live lock, clears a stale one (dead or reused pid), and fails behind a lock two hours old. Last, it writes W4 and waits, without forcing anything, until `archive_timeout` archives it (the measured RPO), deletes the database container and volume, and restores to `latest` from the `pg-wal-archive` volume: W1 to W4 must be present. Then it starts a brand-new cluster next to the old archive. `basebackup.sh` must refuse it and change nothing, and the new cluster's archiving must block on a name clash. `basebackup.sh --new-cluster` must move the old files aside and unblock archiving, and a restore of the new cluster to a time T2 must hold N1 and not N2. Last comes a third cluster next to an archive and backup directory whose markers were removed. The default run must refuse the unmarked archive, `--new-cluster` must move it aside, a second `--new-cluster` must be refused (markers match, archiving healthy), and a restore to T3 must hold M1 and not M2. It prints the measured RPO and RTO, removes everything it created, and exits non-zero on any mismatch. It takes about 7 minutes, because it waits for `archive_timeout`. `./test-pitr.sh --quick` forces the WAL switch instead and takes about 2 minutes, but does not measure the RPO.

## Backup encryption (production)

D98: production backups are encrypted at rest and in transit, the keys are held apart from the backups, access is restricted, and restores are tested, including the recovery of the keys. The server holds **public keys only**. A stolen server, disk or backup copy reveals nothing.

**Mode.** `RESTAURANTPRO_ENVIRONMENT` comes from the environment, or else from `.env`, which the scripts read with grep and never source. Since `.env` decides the mode, every script refuses a `.env` that is writable by group or others, or owned by anyone but root or the user running it (`chmod 600 .env`). It is `production` when unset. In production, PostgreSQL does not start, and `backup.sh` / `basebackup.sh` exit non-zero writing nothing, unless the checks below pass. Nothing is ever written in plaintext as a fallback. `test` is for developer stacks only. It allows plaintext with a WARNING; for a stack without keys, set `BACKUP_AGE_RECIPIENTS_FILE=/dev/null` in that stack's `.env`. Any other value makes every script exit.

**What is encrypted.** WAL is encrypted by the `archive_command`, so the `pg-wal-archive` volume holds only `<segment>.age` and `<segment>.sha256`. Base backups and logical dumps are written as `.tar.age` and `.tar.sha256`, and `--wal-only` copies WAL as it is. Every `<x>.age` has a sidecar `<x>.sha256` with two lines in `sha256sum` format: the SHA-256 of the plaintext (`<x>`) and of the ciphertext (`<x>.age`). The ciphertext line lets the key-free checks catch bit rot or a swapped file. The only plaintext files allowed in `backups/` and the volume are well-formed sidecars and these markers:

- `SYSTEM_IDENTIFIER` and `RECIPIENTS-<UTC>-<fingerprint>` (a copy of the accepted recipients file), which are written once and go off the box;
- `RECIPIENTS_SHA256`, `LAST_OFFBOX_SYNC` and `ARCHIVER_FAILED_COUNT`, which change in place and therefore stay on the server. Logs go to `logs/`, never to `backups/`. Every script uses `lib/age.sh`, which runs the host's `age` when it is at least 1.3.2, and otherwise `age` from the pinned `restaurantpro-postgres:18.6-r1` image (`docker run --rm -i --network none`, key file mounted read-only).

### Keys and custody

There are two age key pairs. Each identity (private key) is created on its custodian's machine, never on the server: `age-keygen -o operational.key` prints the public key (`age1…`). Only the two public keys go into `deploy/backup-recipients.txt`:

```text
# operational key, custodian: <name>, created <date>
age1...
# recovery key, custodian: <name>, escrow: <where>, created <date>
age1...
```

```bash
chmod 644 backup-recipients.txt          # owned by root or the backup user; not writable by group or others;
                                         # readable by the postgres user (uid 70 in the container)
```

Fill in this table at installation and keep it with the drill log:

| Key | Purpose | Custodian | Where the identity lives | Escrow |
| --- | --- | --- | --- | --- |
| Operational | weekly restore check, real restores | `<name, role>` | the restore host, on an encrypted disk or a password manager | `<none / where>` |
| Recovery | used only when the operational key is lost; quarterly key-recovery drill | `<a second person, not the operator>` | nowhere online | `<sealed paper copy in a safe / an offline password manager>` |

The checks behind the keys:

- PostgreSQL's preflight (`postgres/preflight.sh`) and the backup scripts refuse fewer than two distinct X25519 recipients, and a recipients file writable by group or others. The preflight also refuses a file that age rejects, or one the postgres user cannot read (the archive command runs as postgres). The backup scripts and `verify-backups.sh` also refuse a file owned by anyone other than root or the backup user; the preflight cannot see host owners.
- The backup scripts refuse to run while `BACKUP_AGE_IDENTITY` is set, or while any file of 64 KiB or less under `deploy/` or the backup directory contains an age identity.
- Restores take `--identity FILE`. The file must be yours, mode 0600 or 0400, and outside `deploy/` and the backups. Use a tmpfs such as `/dev/shm`, and `shred -u` it afterwards.
- The recipients' fingerprint (the SHA-256 of their sorted keys) is recorded in `RECIPIENTS_SHA256` of `backups/` and of `backups/pitr/`, with a write-once copy of the file as `RECIPIENTS-<UTC>-<fingerprint>` for the restore host. A change makes `verify-backups.sh` fail, and `backup.sh` / `basebackup.sh` refuse to write, until `./verify-backups.sh --accept-recipients-change`, which writes an `AUDIT` line to the log. A directory that holds ciphertext but no record is treated the same way; only an empty installation is recorded silently. That is how a silent swap to an attacker's key is caught before anything is encrypted to it.

**Rotation.** Add the new public key to `backup-recipients.txt`, then run `./verify-backups.sh --accept-recipients-change` (backups are refused until then) and `docker compose up -d postgres`, so the archive command reads the new file. An editor that replaces the file gives it a new inode, which the running container does not see until that restart. Keep the old key until every backup encrypted only to it has aged out of retention, here and off the box. Then remove it the same way, and run a key-recovery drill with the new key.

### Access

The scripts run with `umask 077`. In production they **refuse rather than fix** the following, and the message gives the `chmod`/`chown` command to run:

- a backup directory or subdirectory that is not 0700;
- a file that is not 0600;
- an owner other than the user running the scripts;
- a group- or world-writable recipients file or script.

The `pg-wal-archive` volume stays `postgres:postgres 0700`. Run every backup job as one dedicated user (for example `restaurantpro`, a member of the `docker` group).

### In transit and off the box

Backup data crosses a network only on the off-box copy. `pg_basebackup` and `pg_dump` run through `docker exec` over the local socket, and PostgreSQL publishes no port. The backend's JDBC connection stays on the internal Docker network of the same host, without TLS. A remote backup client is not supported; if one is ever added, it must use `hostssl` with `sslmode=verify-full`.

`./offbox-sync.sh` is the only off-box copier. It runs `rsync` over ssh with `-F /dev/null` (no ssh config), `BatchMode=yes`, `StrictHostKeyChecking=yes`, the pinned `deploy/offbox_known_hosts`, key authentication only and no agent or port forwarding, or copies to a local removable disk. It copies the sidecars first and the ciphertext after.

The destination is **write-once**: the client passes `--ignore-existing`, and the destination enforces it with `rrsync -no-overwrite`. An archive and sidecar rewritten on the server, by ransomware for example, never replace a good copy off the box. Because of that, `backups/pitr/` goes to `pitr-<system identifier>/` at the destination: a new cluster starts its WAL names again, and those names must not collide with the old cluster's. The markers that change in place stay on the server. In production it refuses any file that is not ciphertext, a marker or a sidecar, and a local target on a network filesystem (nfs, cifs, smb). Each successful run writes `backups/LAST_OFFBOX_SYNC`.

Set up the destination so that this server can neither read nor delete what it wrote:

```bash
# On the destination host (rsync >= 3.2.4, which ships rrsync), as root:
adduser --disabled-password restaurantpro && install -d -o restaurantpro -m 700 /srv/restaurantpro
# ~restaurantpro/.ssh/authorized_keys, one line: the server's ssh public key, forced into write-only,
# no-delete, no-overwrite mode
command="rrsync -wo -no-del -no-overwrite /srv/restaurantpro",restrict ssh-ed25519 AAAA... restaurantpro@server

# On the server, as the backup user:
ssh-keygen -t ed25519 -N '' -f ~/.ssh/offbox_ed25519          # this key may write, nothing more
ssh-keyscan -t ed25519 backup-host.lan > offbox_known_hosts    # then compare the fingerprint, read on the destination
ssh-keygen -lf offbox_known_hosts                              #   with: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
chmod 644 offbox_known_hosts
./offbox-sync.sh --check-append-only
```

`--check-append-only` writes a canary under a new name, then checks that reading it back, deleting it and overwriting it are all refused. It detects an overwrite from rsync's itemized changes, since this server cannot read the file back. Run it after any change on the destination. Keep snapshots on the destination too (ZFS, btrfs or the NAS's own versioning), and its own retention, at least as long as the server's. With a removable disk only, `--check-append-only` is skipped: a disk the server can mount, it can also erase. Rotate two disks and keep one away from the server.

### Schedule

```cron
# /etc/cron.d/restaurantpro, run as the backup user, logs in deploy/logs/
*/5 * * * * restaurantpro cd /opt/restaurantpro/deploy && ./basebackup.sh --wal-only >>logs/pitr.log 2>&1 && ./offbox-sync.sh >>logs/offbox.log 2>&1
17 2 * * *  restaurantpro cd /opt/restaurantpro/deploy && ./basebackup.sh --keep 7 >>logs/pitr.log 2>&1
30 2 * * *  restaurantpro cd /opt/restaurantpro/deploy && ./backup.sh --keep 14 >>logs/backup.log 2>&1 && ./offbox-sync.sh >>logs/offbox.log 2>&1
*/15 * * * * restaurantpro cd /opt/restaurantpro/deploy && ./verify-backups.sh 2>&1 | tail -20   # output = cron mail = the alert
```

The same with systemd: one `oneshot` service per line above (`User=restaurantpro`, `WorkingDirectory=/opt/restaurantpro/deploy`), each with a timer. For example:

```ini
# /etc/systemd/system/restaurantpro-verify-backups.service
[Service]
Type=oneshot
User=restaurantpro
WorkingDirectory=/opt/restaurantpro/deploy
ExecStart=/opt/restaurantpro/deploy/verify-backups.sh
# /etc/systemd/system/restaurantpro-verify-backups.timer
[Timer]
OnCalendar=*:0/15
Persistent=true
[Install]
WantedBy=timers.target
```

Do the same for `--wal-only` + `offbox-sync` (`OnCalendar=*:0/5`), the nightly base backup and the nightly logical backup. Alert on a failed unit: `OnFailure=` to your notifier, or monitor `systemctl --failed`.

`verify-backups.sh` needs no key. It exits non-zero (spec §12, "backup em atraso") when any of these fails:

- the archiver: archiving is on with `archive_timeout` ≤ 300 s, it is not failing now, `failed_count` has not grown, and no segment has waited more than 15 min;
- every segment older than 15 min is in `backups/pitr/wal`;
- the newest base backup is at most 26 h old, and the WAL chain is continuous from the oldest kept base;
- every file in the volume and in `backups/` is ciphertext whose sidecar holds its SHA-256. This reads every backup file on each run: seconds for a few GB;
- the modes, the recipients and their fingerprint, no identity on the server;
- `LAST_OFFBOX_SYNC` is at most 30 min old.

### Restore checks

- **Weekly, on a restore host** (never the server; it needs only Docker and a checkout of `deploy/`). Pull the off-box copy down, then run `./verify-restore.sh --from <copy> --identity /dev/shm/operational.key`. It checks that the key is one of the current recipients, taken from the newest `RECIPIENTS-<UTC>-<fingerprint>`, whose keys must match its name. It uses the newest `pitr-<system id>/` tree. It restores the newest base backup with all copied WAL (`pitr-restore.sh --target-time latest`, with `pg_verifybackup` before the start) into an isolated container, and checks that recovery reached the newest copied segment, that `pg_amcheck --all` is clean, and the Flyway history. It does the same for the newest `backup.sh` archive with `restore.sh` in a scratch container. It writes `drill-<UTC>.json` (base, WAL reached, RTO, key) and removes what it created. A non-zero exit is an alert.
- **Quarterly key-recovery drill**, on a **clean machine** with no operational key:
  1. The second custodian retrieves the recovery identity from escrow and notes the time: `T0=$(date -u +%Y-%m-%dT%H:%M:%SZ)` when retrieval starts.
  2. Copy the identity to `/dev/shm/recovery.key`, mode 0600, holding that one key only.
  3. Run `./verify-restore.sh --key-recovery --retrieval-started "$T0" --from <copy> --identity /dev/shm/recovery.key`.
  4. It proves that the recovery key matches a current recipient, and that it alone decrypts and restores the newest base backup, its WAL and the newest logical backup. It also proves that retrieval plus restore fit the 2 h RTO (§11).
  5. `shred -u /dev/shm/recovery.key`, return the escrow, and record the drill below.

Drill log template (one row per drill, kept with the custody table):

| Date (UTC) | Kind (weekly / key-recovery) | Custodians present | Key (age1… prefix) | Base backup and logical archive | WAL reached | Retrieval → restored | RTO | Result | Report file |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-11-06 | key-recovery | A (operator), B (recovery) | age1xyz… | restaurantpro-base-…tar.age, restaurantpro-…tar.age | 0000000100000000000000A4 | 38 min | 6 s | PASS | drill-20261106T101500Z.json |

`./test-backup-encryption.sh` is the automated drill for all of this (A37): it uses throwaway keys and needs only Docker. It checks that production without keys, with one key, with an identity on the server or with loose modes is refused. It checks that the volume, `backups/` and the off-box copy hold only ciphertext, and that a restore to T works with either key alone and fails cleanly with a wrong one. It also checks the recipients-swap alarm, the pinned host key, the append-only check, `--encrypt-legacy`, and `verify-restore.sh --key-recovery`. It takes about 4 minutes and leaves nothing behind.

### Upgrade from S27 (plaintext backups)

Before D98, the `pg-wal-archive` volume and an unencrypted `backups/` held plaintext. Upgrade in this order:

1. Create `backup-recipients.txt` (above), then `docker compose up -d --build postgres`. From then on the archive command writes only `.age`.
2. `./basebackup.sh --encrypt-legacy`. It encrypts the plaintext WAL in the volume, the plaintext base backups and WAL in `backups/pitr` (including any `old-*` directories), and the plaintext `backup.sh` archives in `backups/`. It does this in place, and deletes each plaintext once its `.age` is on disk. It records the recipients for those directories (as the first production run does). It then takes a new base backup. Until then, production runs of `basebackup.sh` refuse and point here.
3. Run `./verify-backups.sh`, then `./offbox-sync.sh --check-append-only`. Off-box copies made before the upgrade are plaintext: delete them on the destination by hand.

## Restore

`./restore.sh [--force] [--identity KEY_FILE] ARCHIVE` decrypts `.age` archives (with the private key file, mode 0600 or 0400, outside `deploy/` and the archive's directory), checks the sidecar, the checksums and the dump, then restores in a single transaction: either everything is restored or nothing changes. It refuses a database that already holds tables unless you pass `--force`. With `--force` it restores into a staging database, then closes the target's connections, renames the target aside, renames the staging database into its place, and drops the old one. Nothing created after the backup survives, which is what a rollback needs. If the restore fails or is interrupted before the swap, the target stays as it was. If it is killed during the swap, no data is lost: the next run of `restore.sh` names the leftover databases (`restaurantpro_old_<pid>`, `restaurantpro_restore_<pid>`) and tells you how to rename one back.

Restore the live installation:

```bash
docker compose stop backend caddy
./restore.sh --force --identity /dev/shm/operational.key backups/restaurantpro-<timestamp>.tar.age
docker compose start backend caddy
```

On a new server, copy the archive into `deploy/backups/`, then restore the configuration from it, then the database. Run from `deploy/`. Never write a decrypted copy of an archive under `backups/`: that directory is copied off-site. The operational identity comes from its custodian onto a tmpfs for the restore only, and is shredded afterwards.

```bash
A=backups/restaurantpro-<timestamp>.tar.age    # the identity is on a tmpfs, never under deploy/
K=/dev/shm/operational.key                     # mode 0600, from its custodian
age -d -i "$K" "$A" | tar -xf - config         # no age here? docker run --rm -i --network none -v "$K:/k:ro" restaurantpro-postgres:18.6-r1 age -d -i /k < "$A" | tar -xf - config
cp config/.env config/compose.yaml config/Caddyfile .
mkdir -m 700 secrets && cp config/secrets/* secrets/
chmod 644 secrets/*                                   # postgres and the backend read them as non-root users
rm -rf config
# also put backup-recipients.txt back (from the custodians' public keys) before starting
docker compose up -d --build postgres
./restore.sh --identity "$K" "$A"
docker compose up -d
shred -u "$K"
```

`restore.sh` decrypts into a private temporary directory and deletes it when it finishes.

The database password in `secrets/db_password` must be the one the new PostgreSQL volume was created with. On a new volume, PostgreSQL takes whatever the file holds at its first start.

### Restore drill

`./test-backup-restore.sh` is the automated drill (acceptance criterion 10). It needs only Docker and runs in production mode with throwaway age keys. It starts two throwaway PostgreSQL containers, loads `test/sample.sql`, runs `backup.sh`, restores into the second container with `restore.sh`, and compares per-table row counts and checksums and the sequence positions. It also compares the normalised schema (`pg_dump --schema-only`), and checks retention, that Spring Session rows are left out, the refusal without `--force`, that `--force` works while a client is still connected and removes objects created after the backup, that a `--force` restore failing mid-way leaves the target unchanged with no leftover databases, and that a corrupted archive is rejected (by age for damaged ciphertext, by the checksums for a damaged payload). It also checks that the archive is ciphertext with its sidecar, mode 0600 in a 0700 directory. It removes everything it created and exits non-zero on any mismatch.

`test/real-schema-drill.sh` runs the same round trip on the schema the application really creates. It starts a throwaway PostgreSQL and runs the backend jar against it, so Flyway applies the real migrations and the bootstrap creates the company and the admin. It then fills every module through the HTTP API, runs `backup.sh` (production mode, throwaway keys), and restores into a second fresh container with `restore.sh`. It checks that every table and the schema match, and that the Spring Session tables come back empty. Last, it runs the backend on the restored database and checks that `GET /backoffice/stores` and the release list answer the same. PostgreSQL rewrites some CHECK constraints with equivalent casts after a dump; the drill accepts only those differences and counts them. Build the jar first with `(cd ../backend && ./mvnw -q package -DskipTests)`, then run `test/real-schema-drill.sh [JAR]`. It needs Docker, Java 25, curl, openssl and python3, takes about 1–2 minutes, generates throwaway credentials and never prints them, and removes everything it created.

Spec §11 asks for restore tests. Run the drill after every upgrade. Also restore a real archive into a scratch server from time to time.

## Upgrade

Spec §11: upgrade in an agreed window, after a backup.

1. `./backup.sh`, then `./offbox-sync.sh`.
2. Update the code (new release tag), then `docker compose up -d --build`. Flyway migrates the schema at startup.
3. Check that `docker compose ps` shows all services healthy, and log in to the BackOffice.

To roll back:

```bash
docker compose stop backend caddy
git checkout <previous release tag>          # the code and compose files of the old version
./restore.sh --force --identity /dev/shm/operational.key backups/restaurantpro-<timestamp from step 1>.tar.age
docker compose up -d --build
```

`--force` replaces the whole database, so the tables, schemas and Flyway history rows of the newer migration are gone. Starting the previous image alone does not undo a migration. Data entered after the backup is lost.

## Two installations on one host (isolation)

Spec §14 #1: each company is its own installation, with its own processes, database, volumes and secrets. Credentials of A do not open B, and restoring A does not change B. One installation is one Compose project: its name (`COMPOSE_PROJECT_NAME` in its `.env`, else `name:` in `compose.yaml`) prefixes every container, volume and network, so give each installation its own `deploy/` copy, `.env`, `secrets/`, `backup-recipients.txt`, backup directory and ports.

**The installation guard.** `backup.sh` and `restore.sh` refuse a `--container` that Docker Compose manages for another project than this installation's. This holds with or without `--force`, so a mistyped container name can never back up or overwrite another company's database. A container Compose did not create (a throwaway one, or one made by `pitr-restore.sh`) is allowed. `pitr-restore.sh --force` replaces only what an earlier `pitr-restore.sh` with the same `--name` created, and refuses every Compose-managed container and volume. If you really mean to act on the other installation, from this one, name it exactly:

```bash
./restore.sh --force --container rp-b-postgres-1 --other-installation rp-b --identity /dev/shm/b-operational.key ARCHIVE
```

`--other-installation PROJECT` accepts only that exact project, never this installation's own, and prints a WARNING naming both. It does not lift any other check: without `--force` the restore still stops at a non-empty database, and an archive still needs the identity of the installation it was encrypted for. Prefer running the scripts from the other installation's own `deploy/` directory and environment.

**The isolation drill (A55).** `./test-two-installations.sh` brings up Compose projects `rp-a` and `rp-b` from `drills/isolation/a.env` and `b.env`, with their own secret directories (`drills/isolation/compose.isolation.yaml`), throwaway age keys and generated credentials (production mode, never printed). It fails on the first miss:

1. **separation:** no container, network or volume is shared, and no secret, recipients file or writable path is mounted in both.
2. **credentials:** A's admin password and session cookie get 401 on B, and the reverse. A's database password fails on B's PostgreSQL, and A's Caddy root certificate does not verify B.
3. **network:** B's backend can neither resolve nor connect to A's database.
4. **restore:** back up A, change A, restore A. A matches its backup, and B's `pg_dump` checksum and per-table fingerprint are unchanged.
5. **tooling:** A's operator environment reaches only A. `restore.sh`, `backup.sh` and `pitr-restore.sh` refuse B's container from A's environment unless `--other-installation rp-b` is given, A's key cannot decrypt B's backup, and B is unchanged.
6. **teardown:** both stacks are removed with their volumes, and nothing of either is left.

It writes `drills/isolation/drill-isolation-<UTC>.json` and exits non-zero on any violation. It needs Docker, curl and openssl, and ports 18443, 18080, 28443 and 28080 free, and takes about 3–5 minutes. `ISOLATION_FAULT=<step> ./test-two-installations.sh` injects a real violation at that step, and the drill must fail there. `./test-two-installations.sh --self-test` runs shellcheck, the clean drill and then every fault in turn. Run the drill after an upgrade that touches `compose.yaml` or the backup scripts, and before putting a second company on a host.

## Load smoke (A56)

Spec §12 targets p95 < 500 ms for simple local commands and reads with 10 POS terminals. `load/load-smoke.sh` measures this against a running stack. It runs `load/LoadSmoke.java` with `java` (JDK 25 or later, no other dependency).

**Run it only against a test or staging installation, never production.** Each run seeds its own store through the BackOffice API, tagged `L<base36 time>`: 10 terminals with a cashier each, a manager, a courier, 40 tables, 300 company-wide catalog items and a fee item, printers on HOLD (nothing prints), a promotion release, discount limits, customers with credit and addresses, and a delivery zone. It then makes real sales with non-certified test documents. These are facts and cannot be removed (spec §8). The installation therefore needs `RESTAURANTPRO_FISCAL_ALLOW_NON_CERTIFIED=true`. The seed also prices, in its own store only, every catalog item that other stores created, because a release must price every active item.

```bash
umask 077; printf '%s\n' '<the admin password>' > /dev/shm/load-admin   # never on the command line
LOAD_ADMIN_USERNAME=admin LOAD_ADMIN_PASSWORD_FILE=/dev/shm/load-admin \
  deploy/load/load-smoke.sh --base-url https://restaurantpro-test.lan --cacert caddy-root.crt
shred -u /dev/shm/load-admin
```

The administrator must already have done the first login and changed the initial password. Copy Caddy's root certificate out of the container with `docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt caddy-root.crt`. Options:

| Option | Default | Meaning |
|---|---|---|
| `--terminals` | 10 | Concurrent POS terminals (1–40) |
| `--seconds` | 60 | Length of the measured loop |
| `--items` | 300 | Catalog items seeded |
| `--report-dir` | `deploy/load/reports/` | Report directory (git-ignored) |
| `--no-gate` | gate on | Report a MISS without failing |
| `--printer-host`, `--printer-port` | `127.0.0.1`, 9 | Address given to the HOLD printers |
| `--timezone` | UTC | Timezone of the seeded store |

**The plan.** Each terminal loops over these steps:

1. Read the table map, the catalog and the current promotions.
2. Open a table, or, in one cycle in five, a DELIVERY order with its automatic fee line.
3. Run two rounds, each confirmed with the catalog version, the promotion version and the preview's expected gross (R4 pricing at confirmation).
4. Read the order, and print the subtotal for a table.
5. Settle in turn by one of: equal parts in cash; an ITEMS partition with its own document, then the rest in cash; a credit sale; a 10 % discount plus an approved offer of one unit, then cash; or a delivery: dispatch to the courier, mark it delivered, then cash.
6. Close the order.

Meanwhile a BackOffice tab reads 1-day reports in turn: sales, taxes, receipts, margins and discounts. Every random choice uses a fixed seed, so runs are comparable.

**Thresholds.** The run fails, with exit 1, on any answer outside 2xx. Unless you pass `--no-gate`, it also fails on any gated operation whose nearest-rank p95 is not strictly below 500 ms. Every measured POS and BackOffice call is gated, with three exceptions that are measured and reported but not gated: `close`, `partitions/{id}/documents` and `credit-charges`. Each of these issues a fiscal document synchronously through the fiscal adapter port, which is an external call (D48, D54). Printing is asynchronous, and no payment provider is used. The report shows p50, p95, p99 and max per operation, the throughput and the variant counts. It is written to `load-<UTC>.json`.

**Checks without a server.** `deploy/load/load-smoke.sh --self-test` runs shellcheck and the program's own checks. These cover the percentile, the 500 ms boundary (exactly 500 ms is a MISS), the plan check with an endpoint removed, the ungated set, JSON and the options. `--dry-run --base-url URL` (logged in as the administrator) or `--dry-run --api-docs FILE` checks that every endpoint of the plan exists in `/v3/api-docs`, and writes nothing.

Measured in S54 on one laptop, with the backend jar, PostgreSQL and the 10 clients on the same machine and the non-certified internal fiscal adapter, over 60 s: 6,310 requests, 291 orders and 0 errors. Every gated p95 was at most 191 ms, except the discount and offer route at 441 ms, which includes an approver's PIN check. `close` was at p95 475 ms, and 544 ms in an earlier run. Re-run on the target hardware before go-live, and after any release that changes the order or settlement paths.

## Known limitations (later slices)

- The `pg-wal-archive` volume and `backups/` hold only ciphertext, but they are on the database server's disk until the off-box copy runs. Use a separate disk for `backups/` if you can.
- Backups are encrypted, not signed. Someone with access to the server can encrypt forged backups to the public recipients and send them off the box under new names; the write-once destination keeps the genuine ones beside them, but a restore cannot tell which is which. Signing backups against forgery is out of scope for now.
- The live database volume (`db-data`) is not a backup and is plaintext, as any running database is. Use full-disk encryption on the host (for example LUKS); it is recommended, not checked.
- A base backup is tied to the PostgreSQL major version (18). After a major upgrade, take a new base backup at once; older ones restore only with the old image.
- `backup.sh` does not include the `caddy-data` volume (Caddy's local CA). Back it up by hand if replacing the server without re-trusting clients matters.
- The Caddy container runs as root (image default) to bind ports 80 and 443.
