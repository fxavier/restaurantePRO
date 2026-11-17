# F07 — Stock reconciliation chunking above ~200k balances per tenant

**Status:** needs-triage
**Source:** S74c measurement
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

`StockBalanceReconciliationJob` reconciles 100k balances in 3.5–7.0 s under the 15 s `statement_timeout`; the ceiling is about 200k per tenant.

## Proposed direction

Chunk by warehouse (one statement per warehouse) and add a plan/timing test at 250k balances.
