# 0012. The Actor carries its kind

Status: Accepted, 2026-11-04 (D38 ★, from the R1 architecture review G6 #3). Source: [r2-decisions.md](../design/r2-decisions.md) D38.

## Context

In R1, `Actor.userId` meant an account id for BackOffice and something else for POS, so audit rows and idempotency scopes could not tell an account from an employee.

## Decision

`Actor` gains `ActorKind kind` (`ACCOUNT` or `EMPLOYEE`). `userId` means the principal id: the account id for `ACCOUNT`, the employee id for `EMPLOYEE` (then equal to `employeeId`). Build it with `Actor.account(...)` or `Actor.employee(...)`. Business facts store typed columns: POS facts `*_employee_id`, BackOffice facts `*_account_id`, never the bare principal id. `audit.audit_event` gains `actor_kind` (`SYSTEM` also exists for writes with no session, ADR 0005). Idempotency scopes become `"<op>:<kind>:<id>"`.

Rejected: a sealed `BackOfficeActor`/`PosActor` hierarchy, which changes every `AccessPolicy` caller and the session serialization for the same gain.

## Consequences

- **Deviation from the decision text.** D38 assumed the `serialVersionUID` change would invalidate stored sessions. Java serialization does not check a record's `serialVersionUID`; the canonical constructor's checks are what reject an old shape. As built, `config.SessionConfig` treats a stored attribute the release cannot read as absent, so the user gets 401 and logs in again (`OutdatedSessionTest`). The outcome the decision wanted (one forced re-login at upgrade) holds.
- Every module's audit attribution is now consistent (`recordAsCurrentActor`).
