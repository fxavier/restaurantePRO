# 0014. Approvals are verified before locks, credentials travel in headers

Status: Accepted, 2026-11-04 (D40 ★, from G6 #5; pattern D28). Source: [r2-decisions.md](../design/r2-decisions.md) D40, [backend-decisions.md](../design/backend-decisions.md) D28.

## Context

Voids, expenses, day reopen and payment reclassification need a second employee's code and PIN (D28). Verifying inside the use-case transaction takes a second pooled connection and holds the order or settlement lock for the BCrypt time. A PIN inside the request body would be hashed into the stored idempotency record, and a SHA-256 over 4–8 digits can be brute-forced.

## Decision

An approved command runs in two phases. (1) A non-transactional `authorize(...)` calls `ApproverVerifier.verify` (which commits on its own connection) and returns an `Approval(…, approverEmployeeId)`. (2) The idempotent, transactional `apply(…, approval)` runs. The approver's code and PIN arrive in `X-Approver-Code` and `X-Approver-Pin` and are never part of the idempotency hash. The Hikari pool holds 20 connections (10 terminals, §12).

## Consequences

- As built, each module has its own small `service.Approval` record (orders void, cash day reopen, settlement reclassification), and the controller calls `authorize` before `idempotency.execute`. So a replay with the same key verifies the approver again before the stored response is returned.
- The approver must not be the operator: 422 `SELF_APPROVAL`, checked by the calling use case.
- The audit row names both operator and approver (A6).
