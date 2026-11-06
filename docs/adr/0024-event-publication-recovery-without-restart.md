# 0024. Event publications are recovered without a restart

Status: Accepted 2026-11-05. Source: [r3-decisions.md](../design/r3-decisions.md) D88. Base: [ADR 0004](0004-event-publication-registry.md).

## Context

R2 redelivered an incomplete publication only at startup. A listener that failed once (a print, a projection) stayed incomplete until the next restart, which on a restaurant server may be weeks away (§7, §14 #5).

## Decision

- `shared.events.EventPublicationRecovery` runs every `restaurantpro.events.resubmit-interval` (PT1M) and resubmits each incomplete publication untouched for `resubmit-older-than` (PT5M) with fewer than `max-attempts` (10) attempts; past that it stays FAILED and alerts. Startup republishing stays on. Listeners are idempotent (D19).
- Completed publications are deleted after `completed-retention` (P30D), daily.
- `EventPublicationStats` exposes `event_publications{state, module}` gauges and the per-listener pending count (D86), and the BackOffice can list and resubmit (`SYSTEM_MONITOR`, audited).

## Consequences

- A publication left RESUBMITTED by a crash is marked FAILED by the staleness monitor (PT10M) and retried.
- S33 made `EventPublicationRecoveryTest` deterministic: its context uses a synchronous `taskExecutor`, so a delivery has finished when `resubmitDue()` returns (the 10-attempt case used to wait on wall-clock time and timed out once under load).
- Proofs: `EventPublicationRecoveryTest`, `EventPublicationStatsTest`, `EventRepublicationTest`; system level, `RestartMatrixTest` (f: a reporting listener failing twice is completed by the job on attempt 3, without a restart; a: a round republished at the second instance's start).
