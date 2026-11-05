# 0015. Idempotency in R2: typed, headers kept, only 2xx stored, retention

Status: Accepted, 2026-11-04 (D47 ★). Source: [r2-decisions.md](../design/r2-decisions.md) D47. Base: [ADR 0006](0006-idempotency-key.md). Amended by [ADR 0018](0018-idempotent-multi-step-commands.md).

## Context

R1 follow-ups: replays lost headers, non-2xx responses were stored, replays were untyped, records never expired. The rule that makes the mechanism crash-safe (the action runs inside the transaction that stores its response) had to stay.

## Decision

1. Typed: `<T> ResponseEntity<T> execute(scope, key, target, body, Class<T> type, Supplier<ResponseEntity<T>> action)`; a replay deserializes into `T`. The untyped overload is gone.
2. `Location` is stored (`response_location`) and replayed.
3. Only 2xx is stored. Business errors are thrown, rolling back the action and its claim, so a retry with the same key evaluates the rule again. A non-2xx `ResponseEntity` from an action is an `IllegalStateException`.
4. Retention: a daily job deletes COMPLETED records older than `restaurantpro.idempotency.retention` (default `P90D`).
5. The action's transaction stays `READ COMMITTED`; use cases take explicit short locks when they need more (D51).

## Consequences

- As built, retention also deletes abandoned IN_PROGRESS claims older than the retention (left by a crash or a refused action), but keeps a claim whose step lease is still live.
- `execute` throws `IllegalStateException` when called inside a transaction, before claiming.
- The action holds a DB transaction, so it never makes an external call (ADR 0016). Commands with a step that must run outside a transaction use `executeSteps` (ADR 0018). Each scope uses exactly one mode.
- Proof: `IdempotencyServiceTest`; replay tests per module (e.g. cash's typed replay of `Money` and nested lines).
