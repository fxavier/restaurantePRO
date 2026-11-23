# Complete-app run: follow-ups and open product questions

**Status:** needs-triage
**Source:** the complete-app run (waves W0–W5, PRs #162–#190), G7 close, 2026-11-23
**Recorded by:** the run's orchestrator, at the user's request ("keep your suggestions")

Each item says what is open and its recommended default. Promote an item to its own issue file when it is picked up.
The POS courier choice is already recorded in [../w1-followups/pos-delivery-courier-picker.md](../w1-followups/pos-delivery-courier-picker.md).

## Product questions (a decision is needed; the default is what the app does today unless said)

| # | Question | Recommended default |
| --- | --- | --- |
| Q1 | Should the POS employee code ignore case at login (`ab12` = `AB12`)? | Yes: look the code up by `upper(code)`, as `TerminalRepository` and the FE-F35 unique indexes do; keep the stored case (upper-casing the input would lock out employees saved in lower case). |
| Q2 | Financeiro and Stocks land on their first tab; should the landing tab follow the user's permissions (the first tab they may open)? | Yes: the first permitted tab; today a user without the first tab's permission sees its denial first. |
| Q3 | An inventory transfer received short: should the receiver record the shortfall (quantity and reason) instead of only accepting the transfer? | Yes: a received quantity per line, the difference posted as a transfer loss movement with a reason. |
| Q4 | Who may be a courier: every active employee of the store, or only employees with a courier role? | A courier permission (`DELIVERY_COURIER`), so the dispatch list stays short. |
| Q5 | The BackOffice shows permission codes (`PERM_…`) in the role editor; should it show Portuguese labels? | Yes: a pt-PT label map in the BackOffice, codes kept as the stored value and shown as secondary text. |

## Contract gaps (the API lacks what the screen would use)

- **TicketView structured lines**: the production ticket view carries its lines as text; KDS and reprint would use
  item, quantity, modifiers and notes as fields.
- **SessionRow operatorName**: the cash-session list shows the operator's id to users without `EMPLOYEE_MANAGE` (the
  name comes from a separate employees read); add the display name to the row.
- **Session adjustments GET**: cash adjustments of a session can be posted but not listed; add a read for the session
  detail.
- **POS production destinations list**: the KDS builds its station filter from the tickets it shows, so a station with
  no active rounds never appears as a filter; a read of the store's active destinations would list them all.
- **KDS cut-off for stores without a KDS**: kitchen progress (ADR 0044) stays QUEUED forever in a store that prints
  tickets only; decide whether such a store skips the progress or closes it on print.

## Cleanup and tooling

- `frontend/apps/backoffice/src/routes/area-placeholder.tsx` and `frontend/apps/pos/src/routes/section-placeholder.tsx`
  are no longer referenced (every area and section is built); delete them.
- Add `@vitest/coverage-v8` so crapper can read frontend coverage (UB tools ran advisory on the frontend this run).
- The e2e stack ports are set per run through `E2E_PROJECT` and `E2E_*_PORT` (FE-F20); deriving them per worktree
  automatically would stop two worktrees colliding on the defaults.
- `RoleHttpTest.theSeededRolesAreListed` and `SystemEventPublicationHttpTest` assert on the first page (200 rows) of
  the shared test tenant; read every page, as `StoreHttpTest` now does, before those tables outgrow it.
- `StockBalanceReconciliationJob.reconcile()` seems to return only the last tenant's result when it runs for every
  tenant; check whether the return value should cover all of them (found while fixing test-order failures, #191).
- Docker on the dev machine holds about 120 GB of Testcontainers images and volumes from this run; prune it
  (`docker system prune` after checking nothing needed is stopped).

## Deferred features (out of this run's scope)

- Fiscal adapter and the SAF-T / AT exports.
- A payment provider integration (card terminal).
- Pub mode: consumption cards and "bolsas".
- The AT transport guide for stock transfers between stores.
