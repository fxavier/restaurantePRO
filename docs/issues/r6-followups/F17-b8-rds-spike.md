# F17 — B8 RDS spike (run by the user on AWS)

**Status:** needs-triage
**Source:** D188, O2; `deploy/drills/b8-rds/README.md`
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

Whether managed PostgreSQL grants `BYPASSRLS` to the owner role decides D188 (a) or (b). The drills ran both where scripted (tenant export (a) and (b)), but the spike needs an AWS account.

## Proposed direction

The user runs the spike from the README and records (a) or (b) in the decision doc §5a (O2).
