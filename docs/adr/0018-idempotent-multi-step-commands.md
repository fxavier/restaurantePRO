# 0018. Idempotent multi-step commands (`executeSteps`)

Status: Accepted 2026-11-05 (user ratified as built, R3 P39). Amendment D63 to D47. Source: [r2-decisions.md](../design/r2-decisions.md) Amendment D63. Base: [ADR 0015](0015-idempotency-r2-rules.md).

## Context

`POST /orders/{id}/documents`, `/close` and `/payment-attempts/{id}/resolve` (by provider query) need an Idempotency-Key (§9) but contain a step that must run with no transaction: `FiscalApi.issue` or `provider.status`. `execute` runs the action under the record's row lock in a transaction, so it would trip the guard, or nest a second connection and hold the lock across the step.

## Decision

A second mode, `IdempotencyService.executeSteps` (same signature as `execute`), used only for those commands. (1) Claim in a short committed transaction with a lease (`lease_owner`, `lease_until` = now + `restaurantpro.idempotency.step-lease`, default `PT30S`); same hash, 422 and replay rules; a live lease gives 409; an expired lease (`lease_until <= now`) is taken over by compare-and-set. (2) The steps run with no transaction and no lock on the record; each step opens its own short transaction and is **idempotent by domain state**. (3) Only the lease owner completes; a lost lease replays the winner's stored response. (4) An exception expires the lease and stores nothing, so a retry runs at once.

## Consequences

- **Each operation scope uses exactly one mode.** `executeSteps` refuses an unfinished claim made by `execute`; `execute` does not look at leases.
- **Deviation fixed in the text:** D63 first said `lease_until < now`; code and review use `<=`, so a released lease is taken over at once (`690690e`).
- **Review changes (S16 G5):** `/close` s1 checks the order is settled (nothing in doubt, paid = due) before freezing or issuing, so a close that cannot succeed issues nothing. `freezeForInvoice` is a no-op on an already frozen order, even CLOSED, so a late `/documents` retry does not get 409. **Deviation:** the client's `orderVersion` is checked only until the order is frozen; once frozen nothing can change, so a retry whose version predates the freeze skips the check.
- Proof: `IdempotencyStepsTest` (live lease 409, takeover, lost-lease replay, immediate retry, no transaction in steps, pool size 1), `OrderCloseResumeTest`.
