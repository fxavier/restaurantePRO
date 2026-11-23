# POS delivery: choose the courier on "Expedir"

**Status:** done (done in fu/courier-picker)
**Source:** PR #169 (w1/pos-delivery) G5 review (Low)
**Recorded by:** pos-delivery fix round, 2026-11-21

## Problem

"Expedir" on the POS dispatch board always sends the logged-in employee as `courierEmployeeId`
(`frontend/apps/pos/src/features/delivery/components/delivery-commands.tsx`). The contract has no POS endpoint that lists
the store's couriers (no `GET /api/v1/pos/couriers`), so a waiter cannot dispatch a delivery on behalf of another driver;
the driver has to log in on the terminal and tap "Expedir" themselves.

## Proposed direction

1. Backend `developer`: a POS read of the store's active employees who may carry a delivery (id and display name, no
   PIN or account data), authorized like the other delivery reads (`PERM_DELIVERY_OPERATE@store`).
2. Frontend: a courier choice on "Expedir" (radio list, 48 px targets), defaulting to the logged-in employee; the
   dispatch request is unchanged (`courierEmployeeId`, `version`, one Idempotency-Key per tap).
3. `COURIER_NOT_IN_STORE` is already shown in Portuguese; keep it for a courier deactivated meanwhile.

## Resolution (fu/courier-picker)

- `GET /api/v1/pos/couriers` and the "Expedir" radio list already existed; the list was every active employee of the
  store and nothing was picked by default.
- New permission `DELIVERY_COURIER` (Q4 of `../complete-app-followups/README.md`), seeded by
  `V3018__identity_delivery_courier_permission.sql` to every role (template and tenant role) that holds
  `DELIVERY_OPERATE`, i.e. Administrador, Gerente and Caixa as seeded: no seeded profile is a driver, and whoever
  dispatched themselves before still can. A dedicated driver gets a tenant role holding `DELIVERY_COURIER`.
- The courier list and the dispatch both require it: an active employee of the store without it is refused with
  `422 NOT_A_COURIER`; `COURIER_NOT_IN_STORE` stays for an unknown, inactive or unassigned one.
- The dialog picks the logged-in employee when they are in the list; the request is unchanged.
