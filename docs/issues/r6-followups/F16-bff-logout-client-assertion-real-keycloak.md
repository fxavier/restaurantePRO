# F16 — Real-Keycloak check of the BFF logout client assertion

**Status:** needs-triage
**Source:** S72b G5 (qa note)
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

The BFF ends the Keycloak session back-channel with a `private_key_jwt` client assertion; this is proven against a fake, not Keycloak's end-session endpoint.

## Proposed direction

One `*IT` against the Keycloak Testcontainer: log in through the BFF, log out, and assert the Keycloak session is gone.
