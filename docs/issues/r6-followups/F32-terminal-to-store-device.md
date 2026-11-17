# F32 — Rename `Terminal` → `StoreDevice`, or add a CHECK and clearer predicates

**Status:** needs-triage
**Source:** G6 architecture review (R6), 2026-11-17
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

`organization.terminal` now holds POS terminals and print bridges (`kind`); code and queries use "terminal" for both, and nothing in the database ties kind-specific columns to the kind.

## Proposed direction

Either rename the aggregate and table to StoreDevice (glossary term), or keep the name and add a CHECK per kind plus explicit `isPos()/isBridge()` predicates and queries filtered by kind.
