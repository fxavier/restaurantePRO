# F33 — Gauges with several backend instances

**Status:** needs-triage
**Source:** G6 architecture review (R6), 2026-11-17
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

Each instance computes the same database-backed gauges (print queue, in-doubt attempts, cash discrepancies), so with N instances Prometheus sees N duplicated series and alert rules that sum them over-count.

## Proposed direction

Either one instance publishes (a lease per gauge) or the rules aggregate with `max by` instead of `sum`; document it in the operations section and test the rule.
