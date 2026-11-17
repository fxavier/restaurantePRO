# F08 — `Projections.clear` in day chunks

**Status:** needs-triage
**Source:** S74d measurement
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

Clearing reporting projections for one store and one year takes more than 15 s and hits `statement_timeout` (one month: 3.2 s).

## Proposed direction

Clear in day chunks, each in its own transaction, idempotent on retry; test one year under the timeout.
