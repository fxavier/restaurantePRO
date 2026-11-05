# 0016. External calls: persist, then call, then record

Status: Accepted, 2026-11-04 (D48 ★, with D50 §7.3–7.4 and D53). Source: [r2-decisions.md](../design/r2-decisions.md) D48, D50, D53; spec §7.3, §7.4.

## Context

Spec §7.3: persist the attempt before calling a provider, and hold no lock during the call. §7.4: a timeout is UNKNOWN, and the status is queried before anything is repeated. The idempotency action runs inside a DB transaction (ADR 0015), so it cannot make the call.

## Decision

A domain pattern, not a shared helper. The domain row is the claim: the HTTP command stores a `PaymentAttempt` in CREATED and returns 202 with its id. After commit, a bounded executor (`PaymentSubmitter`) runs tx-a, a compare-and-set CREATED → PENDING (exactly one submitter calls), then the provider call with no transaction or lock (`attemptId` as the provider's idempotency reference), then tx-b records SUCCEEDED (Payment + allocation + cash receipt), FAILED, PENDING, or UNKNOWN on timeout. A sweeper resubmits CREATED attempts older than `created-grace` (10 s) and makes PENDING attempts past the provider timeout plus `pending-margin` UNKNOWN. Every adapter's first line is `shared.ExternalCalls.requireNoTransaction()`.

Rejected: a generic `IdempotencyService.executeExternal`. Recovery after a crash mid-call is domain-specific (query the provider, or reclassify with authorization).

## Consequences

- An attempt CREATED, PENDING or UNKNOWN on an order blocks a new attempt on it (409 `PAYMENT_IN_DOUBT` with `attemptId`) and blocks order and session close. A timeout never means FAILED.
- **Review change, the PENDING-resolve rule (S16 G5):** a provider query answer `Succeeded` settles an UNKNOWN *or PENDING* attempt; `Failed` settles only an UNKNOWN attempt whose last transition is older than provider timeout + pending margin, so no charge can still be in flight. A PENDING attempt stays PENDING until the sweeper makes it UNKNOWN. **Deviation:** the r2-decisions §4 `PaymentAttempt` row allows only UNKNOWN → SUCCEEDED/FAILED by resolve; as built, resolve also turns a PENDING attempt SUCCEEDED.
- The CAS is `UPDATE … WHERE status = 'CREATED'` with a row-count check (`markSubmitted`), replacing an optimistic-lock catch found in review.
- R2 ships no real provider: `FakePaymentProvider` in tests only. Resolve by provider query is a multi-step command (ADR 0018).
- Proof: `ExternalCallsTest`, `IntegratedPaymentTest`.
