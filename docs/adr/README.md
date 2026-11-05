# Architecture decision records

Each ADR records a decision as built, with its context and consequences. The full decision texts, with ids, are in [backend-decisions.md](../design/backend-decisions.md) (R1, D1–D33) and [r2-decisions.md](../design/r2-decisions.md) (R2, D34–D63). Those documents stay the record for decisions without an ADR here. Supersede an ADR with a new one; do not edit a decision in place.

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
| [0018](0018-idempotent-multi-step-commands.md) | Idempotent multi-step commands (`executeSteps`) | D63 (amends D47) | Accepted for build 2026-11-04 |

## Where the code deviates from a decision text

Recorded in the ADRs above, plus one decision without an ADR:

- D24: the concurrent-duplicate 409 comes from `FOR UPDATE NOWAIT` (55P03), not the unique constraint (ADR 0006).
- D35: `orders` also uses `dining :: dto`, `settlement` also uses `fiscal :: dto` (ADR 0011).
- D38: `serialVersionUID` does not invalidate record sessions; `SessionConfig` does (ADR 0012).
- D48/§4: resolve by provider query also settles a PENDING attempt as SUCCEEDED (ADR 0016).
- D50 §7.2: the print backoff waits 2, 4, 8 and 16 s, and the job is FAILED after the fifth refused attempt; there is no 32 s wait (`PrintJob.MAX_ATTEMPTS`).
- D63: takeover is `lease_until <= now` (the text was aligned); `orderVersion` is not checked once the order is frozen (ADR 0018).
- S10 login throttle: a fixed 15-minute window and per-pair/per-address counters, not a sliding per-username window (ADR 0008).
