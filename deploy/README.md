# restaurantPRO — operator runbook

One stack per company (spec §2): Caddy (HTTPS on the LAN), the backend, and PostgreSQL. Only Caddy publishes ports (80, 443). PostgreSQL and the backend sit on an internal Docker network with no route to the LAN or the Internet. Run every command from `deploy/`.

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

`./backup.sh` writes `backups/restaurantpro-<UTC timestamp>.tar` with a `pg_dump -Fc` of the database, the configuration (`compose.yaml`, `Caddyfile`, `.env`) and checksums, and deletes archives beyond the newest 14. Options: `--dir`, `--keep N`, `--age-recipient` (or the env vars `BACKUP_DIR`, `BACKUP_KEEP`, `BACKUP_AGE_RECIPIENT`). It exits non-zero on any failure.

Encrypt the archive with [age](https://age-encryption.org) by setting a recipient (a public key, `age1…`). Only encrypted archives include `secrets/`. Keep the private key off the server. Spec §10 and §11 require encrypted copies off the machine: copy `backups/` elsewhere (for example with `rsync` to a NAS or external disk) after each run.

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

`./restore.sh [--force] [--identity KEY_FILE] ARCHIVE` decrypts `.age` archives (with the private key file), checks the checksums and the dump, then restores in a single transaction: either everything is restored or nothing changes. It refuses a database that already holds tables unless you pass `--force`, which replaces the existing objects.

Restore the live installation:

```bash
docker compose stop backend caddy
./restore.sh --force backups/restaurantpro-<timestamp>.tar
docker compose start backend caddy
```

On a new server, first restore the configuration from the archive, then the database:

```bash
tar -xf restaurantpro-<timestamp>.tar config     # for .age: age -d -i key.txt -o archive.tar <archive>.tar.age
cp config/.env config/compose.yaml config/Caddyfile .
cp -R config/secrets .                           # only in encrypted archives; otherwise recreate secrets/ by hand
docker compose up -d postgres
./restore.sh backups/restaurantpro-<timestamp>.tar
docker compose up -d
```

The database password in `secrets/db_password` must be the one the new PostgreSQL volume was created with. On a new volume, PostgreSQL takes whatever the file holds at its first start.

### Restore drill

`./test-backup-restore.sh` is the automated drill (acceptance criterion 10). It needs only Docker. It starts two throwaway PostgreSQL containers, loads `test/sample.sql`, runs `backup.sh`, restores into the second container with `restore.sh`, and compares per-table row counts and checksums and the sequence positions. It also checks retention, the refusal without `--force`, and that a corrupted archive is rejected. It removes everything it created and exits non-zero on any mismatch.

Spec §11 asks for restore tests. Run the drill after every upgrade. Also restore a real archive into a scratch server from time to time.

## Upgrade

Spec §11: upgrade in an agreed window, after a backup.

1. `./backup.sh`, and copy the archive off the server.
2. Update the code (new release tag), then `docker compose up -d --build`. Flyway migrates the schema at startup.
3. Check that `docker compose ps` shows all services healthy, and log in to the BackOffice.

To roll back, go back to the previous release and restore the backup from step 1. Starting the previous image alone does not undo a migration.
