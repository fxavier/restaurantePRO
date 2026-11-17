# F11 — Signed tenant-restore archives and a foreign-key check after the load

**Status:** needs-triage
**Source:** S75 G5 (follow-ups for S76/architect)
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

`tenant-restore.sh` proves archive integrity with a hash, not its origin, and loads with triggers disabled for one transaction without re-checking foreign keys afterwards.

## Proposed direction

Sign archives (age plus a detached signature, or minisign) and verify before restore; after the load, run a per-table FK validation query and abort on any orphan; extend `test-tenant-restore.sh`.
