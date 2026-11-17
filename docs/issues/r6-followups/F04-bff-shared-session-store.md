# F04 — BFF shared session store for several instances

**Status:** needs-triage
**Source:** S72b decision; D188
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

The BFF keeps tokens in an in-memory session: one instance, or sticky sessions, until a shared store is chosen.

## Proposed direction

Decide the store (PostgreSQL via Spring Session JDBC in the BFF's own schema, or Redis) in an ADR; encrypt tokens at rest; test failover between two instances.
