# F13 — Multi-page keyset test on PostgreSQL for the batch sweepers

**Status:** needs-triage
**Source:** S74b G5 (Low)
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

The keyset cursor of `CreditChargeSweeper`/`AttemptSweeper` is proven on one page only.

## Proposed direction

Seed more than two batches per tenant with ties on the sort key and assert every row is visited exactly once across pages.
