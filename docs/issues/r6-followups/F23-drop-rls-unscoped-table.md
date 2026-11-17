# F23 — Drop `shared.rls_unscoped_table`

**Status:** needs-triage
**Source:** S76 (strict coverage; PRD Flyway row S76 V1013)
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

The registry is empty on the integrated tree and `RowLevelSecurityCoverageTest` keeps it empty, but `StoreScopeStartupCheck`, `AppRoleSession`, `RlsScratch` and three module RLS tests still read or write it.

## Proposed direction

One slice changes those readers and adds a shared migration that drops the table and `rls_register_unscoped`.
