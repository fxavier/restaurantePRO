# F06 — `TENANT_WRITE_REFUSED` classification on store-class tables

**Status:** needs-triage
**Source:** S71 G5 (Low)
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

A `42501` raised by a store-class table's policy is classified by table class, so a tenant violation on a store table can be reported as a store-scope violation (or the reverse).

## Proposed direction

Classify from the failing policy name (`tenant_scope` vs the store policies) and pin both cases with a test per class.
