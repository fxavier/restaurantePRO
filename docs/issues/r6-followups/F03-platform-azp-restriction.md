# F03 — Restrict `/platform/**` to the ops console's `azp`

**Status:** needs-triage
**Source:** S75 G5 / S72; security rule 8
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

`/platform/**` checks the ops issuer and the `platform-operator` role but not `azp`, because the ops console client is not decided yet.

## Proposed direction

Once the ops console client exists, add it to `RouteFamily.PLATFORM`'s allowed `azp` and a `JwtValidationTest` case for another client of the ops realm.
