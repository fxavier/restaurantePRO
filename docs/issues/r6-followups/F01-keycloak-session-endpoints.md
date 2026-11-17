# F01 — Keycloak session listing and logout endpoints

**Status:** needs-triage
**Source:** S73 hand-off (not built); PRD §5 / D175, `IdentityProviderAdmin.userSessions/logoutUser`
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

An administrator cannot list or end a user's Keycloak sessions from the BackOffice; the port methods exist (`userSessions`, `logoutUser`) but no route uses them.

## Proposed direction

Routes under `/api/v1/backoffice/users/{id}/sessions` (list, logout) behind `USER_MANAGE` (sensitive, step-up), calling the port in the bound tenant only for that tenant's accounts; tests with the faked port and one `KeycloakLoginIT` case.
