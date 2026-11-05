# 0006. One shared Idempotency-Key mechanism

Status: Accepted, 2026-11-04 (D24). Source: [backend-decisions.md](../design/backend-decisions.md) D24; spec §9. Amended for R2 by [ADR 0015](0015-idempotency-r2-rules.md) and [ADR 0018](0018-idempotent-multi-step-commands.md).

## Context

Spec §9 requires an `Idempotency-Key` on financial and other repeatable confirmations: the same key replays the stored response, and the same key with another payload is rejected. Each module writing its own version would drift.

## Decision

One mechanism in `shared.idempotency`: table `shared.idempotency_record(scope, key, request_hash, status, response_body, created_at, …)`, unique `(scope, key)`. The request hash is SHA-256 over the target (method + URI) and the canonical JSON body. Same key and hash → the stored response is replayed. Same key, different hash → 422. A concurrent duplicate → 409 "in progress". The scope is the operation name plus the actor. The controller calls it, outside any transaction.

## Consequences

- As built, the claim is `INSERT … ON CONFLICT DO NOTHING` followed by `SELECT … FOR UPDATE NOWAIT`: the action runs while the record's row lock is held, and its effects and the stored response commit together (crash-safe). **Deviation from the D24 text:** the 409 for a concurrent duplicate comes from SQLSTATE 55P03 (`lock_not_available`) on that lock, not from the unique constraint. A deadlock (40P01) or serialization failure (40001) propagates.
- A missing, blank or over-255-character key → 400.
- The scope became `"<op>:<kind>:<principalId>"` with the Actor kind (ADR 0012).
- Approver credentials travel in headers and are never hashed (ADR 0014).
- Proof: `IdempotencyServiceTest` (real PostgreSQL). Modules never write their own.
