# POS delivery: choose the courier on "Expedir"

**Status:** needs-triage
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
