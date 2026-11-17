# F09 — Customer name search at more than 100k customers per tenant

**Status:** needs-triage
**Source:** S74c G5 (Low); the trigram index was dropped
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

Under RLS a `LIKE '%…%'` predicate is not leakproof, so the trigram index is not used; name search is a sequential scan within the tenant.

## Proposed direction

Measure at 100k/250k customers; options: a tenant-led expression index with prefix search, a search column maintained by trigger, or a leakproof wrapper (decision).
