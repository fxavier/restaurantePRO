# 0027. Production backups are encrypted, keys are held apart, and restores are tested

Status: Accepted 2026-11-06 (user decision, D98). Source: [r3-decisions.md](../design/r3-decisions.md) §18 D98. Supersedes D32's optional `age` encryption, D90's reuse of it, and the README rule that the WAL volume is plaintext.

## Context

The user: "Require encryption at rest and in transit, store encryption keys separately from backups, restrict access, and regularly test restoration — including key recovery." §11 sets RPO 15 min and RTO 2 h.

## Decision

- `RESTAURANTPRO_ENVIRONMENT ∈ {production, test}`, default **production** (fail-safe). `test` keeps plaintext with a warning, for developer stacks only.
- **Recipient-only keys on the server:** at least two distinct X25519 recipients (operational and recovery, two custodians) in `deploy/backup-recipients.txt`; no identity on the server (the scripts refuse `BACKUP_AGE_IDENTITY` or any `AGE-SECRET-KEY-1` under `deploy/`). Restores take `--identity FILE` (0600/0400, outside `deploy/` and the backup directory). A recipients change fails until accepted.
- **At rest:** the archive_command encrypts WAL (`.age` + plaintext SHA-256 sidecar), so the `pg-wal-archive` volume holds ciphertext only; base backups and dumps are encrypted to every recipient; PostgreSQL refuses to start in production without valid recipients; `--encrypt-legacy` converts S27 plaintext.
- **In transit:** backups use the local socket; `offbox-sync.sh` is the only off-box copier (ssh with pinned host keys, write-only `rrsync`, no network filesystems, ciphertext only).
- **Access:** the scripts refuse loose modes or owners, with the fix in the message.
- **Tests:** `verify-backups.sh` (no key, every 15 min), `verify-restore.sh` (weekly, on a restore host, operational key), `verify-restore.sh --key-recovery` (quarterly, clean machine, recovery key alone, timed against the RTO).

## Consequences

- No plaintext fallback anywhere in production; a misconfigured installation does not start.
- Proof: `deploy/test-backup-encryption.sh`, `deploy/test-pitr.sh` and `deploy/test-backup-restore.sh` in production mode with throwaway keys; S33 re-ran them, plus `deploy/test/real-schema-drill.sh` against the R3 schema (A37).
