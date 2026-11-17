# F24 — `deploy/load/load-smoke.sh` against an R6 stack

**Status:** needs-triage
**Source:** S76 load smoke
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

The stack load smoke logs in with a password and enrols terminals the pre-R6 way, so it cannot run against an R6 stack (Keycloak tokens, devices realm).

## Proposed direction

Teach `LoadSmoke.java` client credentials (devices realm) and an operator/admin token source, or retire it in favour of `R6TenantLoadSmoke` plus a staging run.
