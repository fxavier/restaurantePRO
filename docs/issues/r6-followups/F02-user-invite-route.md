# F02 — User-invite route for users created after onboarding

**Status:** needs-triage
**Source:** S72 review; D181 (only onboarding invites the first administrator)
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

`POST /backoffice/users` creates a local account, but nothing invites its Keycloak user or grants its `ACCOUNT` membership; today only onboarding does (the e2e suites write the membership as the owner).

## Proposed direction

Invite on account creation (or an explicit `POST /backoffice/users/{id}/invite`): D48 persist–call–record, `inviteUser` + `grant`, idempotent on replay; test the half-done case.
