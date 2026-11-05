# 0004. Cross-module events through the Spring Modulith publication registry

Status: Accepted, 2026-11-04 (D19). Source: [backend-decisions.md](../design/backend-decisions.md) D19; spec §7.

## Context

Spec §7 requires that work handed to another module (for example a confirmed round to production) is not lost on a crash, and rules out an external broker for this size.

## Decision

Every cross-module event is published through the Spring Modulith JPA event publication registry. The publication row (`event_publication`) is written in the publisher's transaction, so the event exists exactly when the change commits. Listeners use `@ApplicationModuleListener` and are idempotent through a unique key on the effect they produce. `spring.modulith.events.republish-outstanding-events-on-restart=true` delivers incomplete publications again at startup, which is safe because an installation runs one backend. No Kafka.

## Consequences

- Events are immutable records in `model/event`, named in the past tense, carrying ids and snapshots, never entities (ArchUnit `eventsAreRecords`, `entitiesStayOutOfDtosAndEvents`).
- `EventRepublicationTest` proves the restart delivery with a second application context on the same database. In R2, `OrderBatchConfirmed` carries the whole batch, so its publication row *is* the persisted production work (D50 §7.1); production dedupes by `UNIQUE(source_type, source_id, destination)` on `ProductionTicket`.
- A listener that must call back synchronously uses the other module's root API, not a reverse event, to avoid a cycle (production → `OrdersProductionApi.markBatchDispatched`, ADR 0011).
