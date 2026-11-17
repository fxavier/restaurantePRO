# F15 — Tenant-restore drill against a real Keycloak back-channel logout

**Status:** needs-triage
**Source:** S75/S72b review
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

The restore drill suspends and restores a tenant but never checks that the tenant's Keycloak sessions end (back-channel logout) on a real Keycloak.

## Proposed direction

Run the drill with the dev Compose Keycloak; assert sessions of the tenant's users are gone after suspension and remain so until reactivation.
