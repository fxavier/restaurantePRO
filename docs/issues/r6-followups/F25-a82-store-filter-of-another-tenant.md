# F25 — Open question: a store-scoped caller naming another tenant's store id gets 403, not 404

**Status:** wontfix (decided 2026-11-17: behaviour accepted as is; A82 amended)
**Source:** S76 `R6AcceptanceTest` probe; A82, D42, security rule 5
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

For a store **filter** (`?storeId=`, `/stores/{id}`) a manager or POS limited to its stores answers 403 for another tenant's store id, exactly as for a random id; an all-stores administrator answers 404 for both. There is no existence oracle (foreign == random), but A82's wording says 404 for another tenant's resource by id. Resources addressed by their own id (price list, order) do answer 404.

## Proposed direction

The user decides whether the D42 order (store input checked before any read → 403) stands for store ids of another tenant (current behaviour, pinned by `R6AcceptanceTest.sameAsRandom`), or whether A82 requires 404 there too.

## Comments

- 2026-11-17 (orchestrator, recommended answer): accepted as is — a store filter outside the caller's scope answers 403 regardless of tenant (no existence oracle). A82 amended in multitenancy-decisions §4 and the PRD; pinned by `R6AcceptanceTest.sameAsRandom`.
