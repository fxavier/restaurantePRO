# F12 — Modulith republication test for a failed invite in the identity listener

**Status:** needs-triage
**Source:** S75 review
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

If `inviteUser` fails inside `AdministratorProvisioning`, completion relies on Spring Modulith republishing the incomplete publication; no test proves the tenant then reaches ACTIVE.

## Proposed direction

A test whose faked port fails the first invite, then `EventPublicationRecovery` resubmits: one account, one membership, tenant ACTIVE.
