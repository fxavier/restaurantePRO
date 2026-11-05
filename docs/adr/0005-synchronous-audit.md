# 0005. Audit is written synchronously, in the caller's transaction

Status: Accepted, 2026-11-04 (D20; helpers per D41). Source: [backend-decisions.md](../design/backend-decisions.md) D20, [r2-decisions.md](../design/r2-decisions.md) D41; spec §10.

## Context

Spec §10 requires an audit trail of critical actions with actor, approver, store, terminal, instant and reason. An audit written from an asynchronous event could be lost on a crash after the change committed.

## Decision

`AuditLog` writes `audit.audit_event` in the caller's transaction (`MANDATORY`), so the change and its audit row commit or roll back together. The table is append-only: a trigger refuses UPDATE, DELETE and TRUNCATE. Details never contain PINs, passwords, tokens or payment data; keys that look like secrets are rejected.

## Consequences

- R2 (D41): use cases call `recordAsCurrentActor(action, entityType, entityId, storeId, approverId, reason, details)`, which fills actor id, actor kind and terminal from the session. Writes nobody asked for in a session (bootstrap, jobs, listeners, enrolment) use `recordAsSystem(...)` with `actor_kind = SYSTEM`. `recordAs(actor, ...)` is only for login and logout. There is no hand-filled path.
- `audit` depends on `access` to read the current actor (ADR 0011). The database also refuses a SYSTEM row that names an actor, and an ACCOUNT or EMPLOYEE row without one (`AuditLogTest`).
- `terminal_id` is where the actor acted, not the entity's terminal.
- Known limit: the secret-key guard checks top-level keys only. R2 details are flat maps by convention; nested checks are deferred to R3 (audit query run).
