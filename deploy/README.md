# restaurantPRO — operator runbook

One stack per company (spec §2): Caddy (HTTPS on the LAN), the backend, and PostgreSQL. Only Caddy publishes ports (80, 443). PostgreSQL and the backend sit on an internal Docker network with no route to the LAN or the Internet. Run every command from `deploy/` of the installation. The Compose project name is fixed (`restaurantpro`), and `backup.sh` / `restore.sh` find the database through it, so run them only from the installation directory and keep one installation per host.

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

These are nightly logical backups. The spec's target of RPO 15 minutes needs WAL archiving with point-in-time recovery (PITR), which a later ops slice adds (D32). Until then, an incident can lose up to one backup interval of data.

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

- No WAL archiving or point-in-time recovery yet: RPO is one backup interval, not 15 minutes.
- `backup.sh` does not include the `caddy-data` volume (Caddy's local CA). Back it up by hand if replacing the server without re-trusting clients matters.
- The Caddy container runs as root (image default) to bind ports 80 and 443.
