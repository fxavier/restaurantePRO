# 0044. Kitchen display: a round's kitchen progress beside its append-only ticket

Status: **Accepted** 2026-11-23 (user approval; proposed 2026-11-22 with the W3 KDS backend, built in PRs #183 and #184). Source: spec §4 "Produção e impressão" ("Ecrã de cozinha pode ser adicionado como interface do mesmo domínio"), §8.3 (OrderBatch: "preparação e impressão têm estados próprios"), §9 (change stream), §10; ADR 0006 (idempotency), ADR 0039/0040 (row-level security, tenant).

## Context

A kitchen display (KDS) is optional in the spec, but once a station uses one the kitchen needs to see each round on its station and move it along: start it, mark it ready, take it off the screen, and bring back one taken off by mistake. Several screens of one store show the same rounds, so they must refresh live and two cooks must not move the same round twice.

The production ticket (one per source × destination) is an append-only fact: a trigger allows one change only, routing an UNROUTED ticket (D89 (1)). A round of a store with no printer, or with its printer in IGNORE, still has its ticket, so the ticket is the right anchor for the KDS. Its preparation, however, changes many times.

## Decision

- **A separate row per round ticket, `production.kitchen_progress`**, created with every BATCH ticket in the same transaction (routed, unrouted or suppressed alike). The ticket stays append-only; the progress is operational state with `@Version`. The primary key is `(tenant_id, ticket_id)`, with a composite foreign key `(tenant_id, ticket_id, store_id)` to the ticket. `destination` is copied from the ticket (a fact, never changed) so the station filter needs no join.
- **States** `QUEUED → IN_PREPARATION → READY → BUMPED`, commands `start`, `ready`, `bump`, and `recall`, which steps back once: `READY → IN_PREPARATION`, `BUMPED → READY`. Every other command is a 409 `ILLEGAL_KITCHEN_STATE`. BUMPED means taken off the screen (served or handed over); one name covers both because the KDS cannot tell them apart.
- **Each step keeps its time and employee** (`started_at/by`, `ready_at/by`, `bumped_at/by`, `recalled_at/by`). A recall clears the steps it undid. Check constraints tie each pair to the status. The audit trail (`PRODUCTION_TICKET_STARTED`, `_READY`, `_BUMPED`, `_RECALLED`, with from and to) is the full history.
- **POS endpoints** under `/api/v1/pos/production-tickets`: `GET /kds?destination=&status=` (the session's store, oldest first; `status` repeats), and `POST /{id}/start|ready|bump|recall` with `{"version": n}` and an `Idempotency-Key`. A stale version is 409. Reading needs `PRODUCTION_VIEW` and moving needs `PRODUCTION_OPERATE`; no new permission. `TicketView` gains `kitchen` (null for a ticket that is not a round).
- **Change stream**: a new topic `PRODUCTION_TICKETS` with the ticket id, sent after commit when a round is queued and on every step.

## Alternatives considered

- **Status columns on `production_ticket`:** one table, but the append-only trigger would have to allow a second kind of change, and the ticket would no longer be a fact.
- **A row only after the first command (absent = QUEUED):** no write when production receives a round, but every past round of a store would then appear as QUEUED.
- **An append-only transition table, with the status derived from the last row:** history without the audit trail, but the optimistic version and the station and status filters become queries over the latest row. The audit trail already holds the history.
- **VOID tickets on the KDS:** a void needs an acknowledgement, not preparation. Left out for now (see Consequences).

## Consequences

- Migration V7013: the table, its RLS (STORE class plus the tenant policy) and `production_ticket_id_store_uk`.
- Rounds received before V7013 have no progress and are not on the KDS.
- Every round of every store is queued, including stores without a KDS, so those rows stay QUEUED. The cost is one small row per round. A KDS that comes online later sees earlier unfinished rounds, so a cut-off (for example by business day) is a follow-up.
- Voids are not shown on the KDS yet. A KDS-only kitchen learns of a void from the order screen or the printer. Showing voids needs an acknowledgement state (a follow-up decision).
