# restaurantPRO — operator runbook

One stack per company (spec §2): Caddy (HTTPS on the LAN), the backend, and PostgreSQL. Only Caddy publishes ports (80, 443). PostgreSQL and the backend sit on an internal Docker network with no route to the LAN or the Internet. Run every command from `deploy/` of the installation. The Compose project name is fixed (`restaurantpro`), and `backup.sh`, `restore.sh` and `basebackup.sh` find the database through it (`pitr-restore.sh` finds the `restaurantpro_pg-wal-archive` volume the same way), so run them only from the installation directory and keep one installation per host.

| Image | Pin |
| --- | --- |
| PostgreSQL | `postgres:18.6-alpine` (D4, same as the tests) |
| Caddy | `caddy:2.11.7-alpine` |
| Backend | built from `../backend`: `eclipse-temurin:25.0.5_8-jdk-alpine` → `eclipse-temurin:25.0.5_8-jre-alpine`, user uid 10001 |

## First install

1. Install Docker Engine with the Compose plugin. Give the server a fixed LAN address and a name clients can resolve (DNS or hosts file), for example `restaurantpro.lan`.
2. Configure:

   ```bash
   cp .env.example .env          # edit: hostname, company data, admin username
   mkdir -m 700 secrets
   mkdir -p backups              # archives and the cron log go here
   openssl rand -base64 32 | tr -d '\n' > secrets/db_password
   printf '%s' 'choose-a-strong-initial-password' > secrets/admin_initial_password
   chmod 644 secrets/*           # readable inside the containers; the 700 directory protects them on the host
   ```

   `.env` and `secrets/` are git-ignored. Never commit them. The `RESTAURANTPRO_BOOTSTRAP_*` values and `secrets/admin_initial_password` are consumed once, at the first start against an empty database (D30). After that, edit the company profile in the BackOffice. The admin must change the initial password at first login. After that login you may empty `secrets/admin_initial_password`, but keep the file: Compose needs it to start.
3. Start: `docker compose up -d --build`, then wait until `docker compose ps` shows all three services `healthy`.

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

`./backup.sh` writes `backups/restaurantpro-<UTC timestamp>.tar` with a `pg_dump -Fc` of the database, the configuration (`compose.yaml`, `Caddyfile`, `.env`) and checksums, and deletes archives beyond the newest 14. Retention only counts names in that exact format, so a renamed copy such as `restaurantpro-before-upgrade.tar` is never deleted. The dump leaves out the rows of the Spring Session tables: after a restore everyone logs in again. Options: `--dir`, `--keep N`, `--age-recipient` (or the env vars `BACKUP_DIR`, `BACKUP_KEEP`, `BACKUP_AGE_RECIPIENT`). It exits non-zero on any failure.

Encrypt the archive with [age](https://age-encryption.org) by setting a recipient (a public key, `age1…`). Without one, `backup.sh` prints a WARNING on every run: spec §10 asks for encrypted backups. Only encrypted archives include `secrets/`. Keep the private key off the server. Spec §10 and §11 require encrypted copies off the machine: copy `backups/` elsewhere (for example with `rsync` to a NAS or external disk) after each run.

Schedule with cron (every night at 02:30):

```cron
30 2 * * * cd /opt/restaurantpro/deploy && BACKUP_AGE_RECIPIENT=age1... ./backup.sh --keep 14 >>backups/backup.log 2>&1
```

Or with a systemd timer:

```ini
# /etc/systemd/system/restaurantpro-backup.service
[Service]
Type=oneshot
WorkingDirectory=/opt/restaurantpro/deploy
Environment=BACKUP_AGE_RECIPIENT=age1...
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

- `compose.yaml` runs PostgreSQL with `wal_level=replica`, `archive_mode=on`, `archive_timeout=300` and an `archive_command` (`postgres/archive-wal.sh`) that copies each finished 16 MB WAL segment into the `pg-wal-archive` volume, flushed to disk before PostgreSQL may recycle it. `archive_timeout` forces a new segment at least every 5 minutes once anything was written, so at most the last 5 minutes of commits are not yet archived. A failing archive copy never loses data: PostgreSQL keeps the segment and retries (watch `docker compose logs postgres` and `SELECT * FROM pg_stat_archiver`). If it keeps failing, `pg_wal` grows on the database volume until the disk is full.
- `./basebackup.sh` (nightly) takes a base backup with `pg_basebackup`, checks it with `pg_verifybackup`, and writes `backups/pitr/base/restaurantpro-base-<UTC timestamp>-<start WAL>.tar`. It then copies new WAL files into `backups/pitr/wal/`, keeps the newest 7 base backups (`--keep`, `PITR_KEEP`), and deletes the WAL older than the oldest kept base backup, both in `backups/pitr/wal/` and in the `pg-wal-archive` volume. So you can restore to any moment since the oldest kept base backup.
- `./basebackup.sh --wal-only` (every 5 minutes) only copies the new WAL files into `backups/pitr/wal/`. Copy `backups/` off the machine right after it.

```cron
*/5 * * * * cd /opt/restaurantpro/deploy && BACKUP_AGE_RECIPIENT=age1... ./basebackup.sh --wal-only >>backups/pitr.log 2>&1 && rsync -a backups/ nas:/srv/restaurantpro/
15 2 * * * cd /opt/restaurantpro/deploy && BACKUP_AGE_RECIPIENT=age1... ./basebackup.sh --keep 7 >>backups/pitr.log 2>&1
```

**Encryption.** As with `backup.sh`, `--age-recipient` / `BACKUP_AGE_RECIPIENT` encrypts every base archive and every copied WAL file (`.age`). It is optional today, and without it every run prints a WARNING (spec §10 asks for encrypted backups). The `pg-wal-archive` volume itself is plaintext on the server, like the database volume. `pitr-restore.sh` decrypts with `--identity` / `BACKUP_AGE_IDENTITY`.

**RPO and RTO**, as measured by `./test-pitr.sh` (next section) on this machine:

| Incident | Data lost at most | Measured |
| --- | --- | --- |
| Database container or volume lost, the `pg-wal-archive` volume survives | `archive_timeout`: 5 min | 227 s and 283 s from the last commit to its segment in the archive (two full runs, 2026-11-05; the bound is 300 s) |
| Whole server lost, restore from the off-box copy | `archive_timeout` + the `--wal-only` interval + the off-box copy: 5 + 5 min, plus the copy, with the cron above | not measured (depends on your copy job) |

The restore time (RTO) is the unpacking of the base backup plus the replay of the WAL written since it, at most a day with nightly base backups. The drill's database is small (a few MB): `pitr-restore.sh` took 3–8 s on an idle machine and up to 34 s while the backend test suite ran beside it. Measure it on your data by restoring a real backup to a scratch name from time to time.

### Restore to a point in time

```bash
./pitr-restore.sh --target-time 2026-11-05T14:30:00+00:00 backups/pitr/base/restaurantpro-base-<timestamp>-<wal>.tar
```

Pick the newest base backup taken before the target time. The time needs an explicit offset (`Z`, `+00:00`, `+01:00`…); `--target-time latest` replays everything archived. PostgreSQL stops just before the first transaction that committed after the target time. The script creates a new container `restaurantpro-pitr` with no network and its own volume `restaurantpro-pitr-data`, and never touches the live installation. It refuses to run if they already exist unless you pass `--force` (or pick another `--name`). By default it reads the WAL from the live `pg-wal-archive` volume, which is the most recent. If the server was lost, pass the off-box copy instead: `--wal-dir backups/pitr/wal` (with `--identity key.txt` for `.age` files). If the WAL does not reach the target time, PostgreSQL refuses to finish the recovery, and the script fails and removes what it created.

Check the result with `docker exec -it restaurantpro-pitr psql -U restaurantpro -d restaurantpro`, then bring it into the live database with the logical backup and restore above:

```bash
./backup.sh                                                  # the current state, in case you need it back
./backup.sh --container restaurantpro-pitr --dir backups/pitr-export
docker compose stop backend caddy
./restore.sh --force backups/pitr-export/restaurantpro-<timestamp>.tar
docker compose start backend caddy
./basebackup.sh                                              # a fresh base backup of the restored state
docker rm -f -v restaurantpro-pitr && docker volume rm restaurantpro-pitr-data
```

Everyone logs in again afterwards: `backup.sh` leaves out the session rows.

### Point-in-time recovery drill

`./test-pitr.sh` is the automated PITR drill (A33). It needs only Docker and starts the `postgres` service of `compose.yaml` itself (throwaway project, secrets and volumes), so it tests the settings that ship. It checks the WAL settings, loads `test/sample.sql`, writes W1, takes a base backup, writes W2, notes a target time T and a fingerprint of every table, writes W3, archives and copies the WAL, then restores to T into an isolated container. W1 and W2 must be present, W3 absent, and every table equal to the fingerprint at T. It then checks that a second restore on the same name is refused without `--force`, and that a target beyond the archived WAL fails and leaves nothing behind. Last, it writes W4 and waits, without forcing anything, until `archive_timeout` archives it (the measured RPO), deletes the database container and volume, and restores to `latest` from the `pg-wal-archive` volume: W1 to W4 must be present. It prints the measured RPO and RTO, removes everything it created, and exits non-zero on any mismatch. It takes about 6 minutes, because it waits for `archive_timeout`; `./test-pitr.sh --quick` forces the WAL switch instead and takes under a minute, without measuring the RPO.

## Restore

`./restore.sh [--force] [--identity KEY_FILE] ARCHIVE` decrypts `.age` archives (with the private key file), checks the checksums and the dump, then restores in a single transaction: either everything is restored or nothing changes. It refuses a database that already holds tables unless you pass `--force`. With `--force` it restores into a staging database, then closes the target's connections, renames the target aside, renames the staging database into its place, and drops the old one. Nothing created after the backup survives, which is what a rollback needs. If the restore fails or is interrupted before the swap, the target stays as it was. If it is killed during the swap, no data is lost: the next run of `restore.sh` names the leftover databases (`restaurantpro_old_<pid>`, `restaurantpro_restore_<pid>`) and tells you how to rename one back.

Restore the live installation:

```bash
docker compose stop backend caddy
./restore.sh --force backups/restaurantpro-<timestamp>.tar
docker compose start backend caddy
```

On a new server, copy the archive into `deploy/backups/`, then restore the configuration from it, then the database. Run from `deploy/`. Never write a decrypted copy of an archive under `backups/`: that directory is copied off-site.

```bash
A=backups/restaurantpro-<timestamp>.tar.age    # encrypted archive; key.txt is the private key, kept off the server
age -d -i key.txt "$A" | tar -xf - config      # unencrypted archive (.tar): tar -xf "$A" config
cp config/.env config/compose.yaml config/Caddyfile .
mkdir -m 700 secrets && cp config/secrets/* secrets/   # only encrypted archives hold secrets/; otherwise recreate them
chmod 644 secrets/*                                   # postgres and the backend read them as non-root users
rm -rf config
docker compose up -d postgres
./restore.sh --identity key.txt "$A"           # unencrypted archive: ./restore.sh "$A"
docker compose up -d
```

`restore.sh` decrypts into a private temporary directory and deletes it when it finishes.

The database password in `secrets/db_password` must be the one the new PostgreSQL volume was created with. On a new volume, PostgreSQL takes whatever the file holds at its first start.

### Restore drill

`./test-backup-restore.sh` is the automated drill (acceptance criterion 10). It needs only Docker. It starts two throwaway PostgreSQL containers, loads `test/sample.sql`, runs `backup.sh`, restores into the second container with `restore.sh`, and compares per-table row counts and checksums and the sequence positions. It also compares the normalised schema (`pg_dump --schema-only`), and checks retention, that Spring Session rows are left out, the refusal without `--force`, that `--force` works while a client is still connected and removes objects created after the backup, that a `--force` restore failing mid-way leaves the target unchanged with no leftover databases, and that a corrupted archive is rejected. It removes everything it created and exits non-zero on any mismatch.

`test/real-schema-drill.sh` runs the same round trip on the schema the application really creates. It starts a throwaway PostgreSQL and runs the backend jar against it, so Flyway applies the real migrations and the bootstrap creates the company and the admin. It then fills every module through the HTTP API, runs `backup.sh`, and restores into a second fresh container with `restore.sh`. It checks that every table and the schema match, and that the Spring Session tables come back empty. Last, it runs the backend on the restored database and checks that `GET /backoffice/stores` and the release list answer the same. PostgreSQL rewrites some CHECK constraints with equivalent casts after a dump; the drill accepts only those differences and counts them. Build the jar first with `(cd ../backend && ./mvnw -q package -DskipTests)`, then run `test/real-schema-drill.sh [JAR]`. It needs Docker, Java 25, curl, openssl and python3, takes about 1–2 minutes, generates throwaway credentials and never prints them, and removes everything it created.

Spec §11 asks for restore tests. Run the drill after every upgrade. Also restore a real archive into a scratch server from time to time.

## Upgrade

Spec §11: upgrade in an agreed window, after a backup.

1. `./backup.sh`, and copy the archive off the server.
2. Update the code (new release tag), then `docker compose up -d --build`. Flyway migrates the schema at startup.
3. Check that `docker compose ps` shows all services healthy, and log in to the BackOffice.

To roll back:

```bash
docker compose stop backend caddy
git checkout <previous release tag>          # the code and compose files of the old version
./restore.sh --force backups/restaurantpro-<timestamp from step 1>.tar
docker compose up -d --build
```

`--force` replaces the whole database, so the tables, schemas and Flyway history rows of the newer migration are gone. Starting the previous image alone does not undo a migration. Data entered after the backup is lost.

## Known limitations (later slices)

- The `pg-wal-archive` volume and `backups/pitr/` are on the database server's disk until the off-box copy runs. Use a separate disk for `backups/` if you can.
- A base backup is tied to the PostgreSQL major version (18). After a major upgrade, take a new base backup at once; older ones restore only with the old image.
- `backup.sh` does not include the `caddy-data` volume (Caddy's local CA). Back it up by hand if replacing the server without re-trusting clients matters.
- The Caddy container runs as root (image default) to bind ports 80 and 443.
