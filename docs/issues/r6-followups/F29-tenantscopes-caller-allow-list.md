# F29 — ArchUnit allow-list for `TenantScopes.call/run/callIn` callers

**Status:** needs-triage
**Source:** G6 architecture review (R6), 2026-11-17
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

Any class can bind an arbitrary tenant through `TenantScopes.call/run/callIn`; only review keeps it to jobs, listeners, enrolment and tests.

## Proposed direction

An ArchUnit rule naming the allowed callers, with an `ArchitectureRulesFireTest` fixture.
