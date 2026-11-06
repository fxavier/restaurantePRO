# Architecture decision records

Each ADR records a decision as built, with its context and consequences. The full decision texts, with ids, are in [backend-decisions.md](../design/backend-decisions.md) (R1, D1–D33), [r2-decisions.md](../design/r2-decisions.md) (R2, D34–D63) and [r3-decisions.md](../design/r3-decisions.md) (R3, D64–D98). Those documents stay the record for decisions without an ADR here. Supersede an ADR with a new one; do not edit a decision in place.

| ADR | Title | Decision | Status |
|---|---|---|---|
| [0001](0001-flyway-version-blocks-per-module.md) | Flyway with one version block per module | D5, D61 | Accepted 2026-11-04 |
| [0002](0002-one-schema-per-module.md) | One PostgreSQL schema per module, no cross-module foreign keys | D6 | Accepted 2026-11-04 |
| [0003](0003-module-dependencies-and-access.md) | Allowed module dependencies, with authorization in `access` | D13 + amendment | Accepted 2026-11-04 |
| [0004](0004-event-publication-registry.md) | Cross-module events through the Spring Modulith publication registry | D19 | Accepted 2026-11-04 |
| [0005](0005-synchronous-audit.md) | Audit is written synchronously, in the caller's transaction | D20, D41 | Accepted 2026-11-04 |
| [0006](0006-idempotency-key.md) | One shared Idempotency-Key mechanism | D24 | Accepted 2026-11-04 |
| [0007](0007-server-side-sessions.md) | Server-side sessions with Spring Session JDBC | D25 | Accepted 2026-11-04 |
| [0008](0008-two-login-flows.md) | Two login flows, one identity model | D26 | Accepted 2026-11-04 |
| [0009](0009-rbac-and-access-policy.md) | RBAC with code-defined permissions, checked by `AccessPolicy` | D27, D42 | Accepted 2026-11-04 |
| [0010](0010-catalog-frozen-release.md) | Catalog working copy plus a frozen published release | D31, D58 | Accepted 2026-11-04 |
| [0011](0011-r2-module-dependencies.md) | R2 module dependencies (three amendments to D13) | D35 ★, D36, D37 | Accepted 2026-11-04 |
| [0012](0012-actor-kind.md) | The Actor carries its kind | D38 ★ | Accepted 2026-11-04 |
| [0013](0013-pos-context-from-session.md) | POS context comes from the session | D39 ★, D45 | Accepted 2026-11-04 |
| [0014](0014-approvals-before-locks.md) | Approvals are verified before locks, credentials in headers | D40 ★, D28 | Accepted 2026-11-04 |
| [0015](0015-idempotency-r2-rules.md) | Idempotency in R2: typed, headers kept, only 2xx stored, retention | D47 ★ | Accepted 2026-11-04 |
| [0016](0016-external-calls-persist-call-record.md) | External calls: persist, then call, then record | D48 ★, D53 | Accepted 2026-11-04 |
| [0017](0017-fiscal-adapter-port.md) | Fiscal adapter port, with a non-certified guard | D54 ★ | Accepted 2026-11-04 |
| [0018](0018-idempotent-multi-step-commands.md) | Idempotent multi-step commands (`executeSteps`) | D63 (amends D47) | Accepted (2026-11-05, R3 P39) |
| [0019](0019-r3-module-dependencies.md) | R3 module dependencies (amendments to D35) | D65 ★ | Accepted 2026-11-05 |
| [0020](0020-payment-targets.md) | Payment targets: ORDER, PARTITION, CREDIT_ACCOUNT | D69 ★ | Accepted 2026-11-05 |
| [0021](0021-credit-account-reservation-and-ledger.md) | Credit account, reservation and ledger in `customers` | D73 ★ | Accepted 2026-11-05 |
| [0022](0022-provider-statement-reconciliation.md) | Provider statements against payments | D78 ★ | Accepted 2026-11-05 |
| [0023](0023-reporting-projections-by-event-triggered-pulls.md) | Reporting projections fed by event-triggered pulls | D84 ★, D85–D87 | Accepted 2026-11-05 |
| [0024](0024-event-publication-recovery-without-restart.md) | Event publications are recovered without a restart | D88 ★ | Accepted 2026-11-05 |
| [0025](0025-tax-cent-of-a-settled-void.md) | The tax cent of a settled void: absorbed, otherwise a pending correction | D96 ★ | Accepted 2026-11-06 |
| [0026](0026-payment-breakdown-snapshot.md) | The payment breakdown on the document, a snapshot at issue | D97 ★ | Accepted 2026-11-06 |
| [0027](0027-production-backup-encryption.md) | Production backups encrypted, keys apart, restores tested | D98 ★ | Accepted 2026-11-06 |

## Where the code deviates from a decision text

Recorded in the ADRs above, plus one decision without an ADR:

- D24: the concurrent-duplicate 409 comes from `FOR UPDATE NOWAIT` (55P03), not the unique constraint (ADR 0006).
- D35: `orders` also uses `dining :: dto`, `settlement` also uses `fiscal :: dto` (ADR 0011).
- D35/D28: expenses use `CASH_EXPENSE` with no second approver — open product question (ADR 0011, 0014).
- D38: `serialVersionUID` does not invalidate record sessions; `SessionConfig` does (ADR 0012).
- D48/§4: resolve by provider query also settles a PENDING attempt as SUCCEEDED (ADR 0016).
- D50 §7.2: the print backoff waits 2, 4, 8 and 16 s, and the job is FAILED after the fifth refused attempt; there is no 32 s wait (`PrintJob.MAX_ATTEMPTS`).
- D63: takeover is `lease_until <= now` (the text was aligned); `orderVersion` is not checked once the order is frozen (ADR 0018).
- S10 login throttle: a fixed 15-minute window; the per-pair counter counts attempts (reset on success), the per-address counter counts failures; not a sliding per-username window (ADR 0008).
- D78: a statement's period is computed from calendar midnights, not from the store's business-day cutoff (BUG-0002, ADR 0022). Fixed in 8c1bd64.
- D97: the em dash of the payment breakdown prints as `?` on every printer table except WPC1252 (BUG-0001, ADR 0026). Fixed in ef16b3d: an ASCII hyphen fallback.
