# F10 — Consumption policy seeding for new tenants

**Status:** needs-triage
**Source:** S74c decision (the planner accepts none)
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

A tenant onboarded by the platform gets no consumption policy; the planner works with none, but the default the R5 installations had is missing.

## Proposed direction

Seed the default policy on `CompanyProvisioned` (an inventory listener, like `CashMethodSeeding`), idempotent; test in `TenantProvisioningChainTest`.
