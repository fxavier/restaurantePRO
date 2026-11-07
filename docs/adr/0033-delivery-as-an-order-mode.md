# 0033. Delivery is an order mode plus a `Delivery` lifecycle in orders

Status: Accepted 2026-11-06 (R4 approval, P48, P49). Source: [r4-decisions.md](../design/r4-decisions.md) D127, with D128–D130.

## Context

§4 asks for delivery orders with an address, a courier ("estafeta") and a history; §8.2 says takeaway and delivery need no table; §8.3 keeps the lifecycle, financial and fiscal states separate and forbids SENT/PAID-style cycles on the order. §8.1 has no delivery entity of its own.

## Decision

- `OrderMode.DELIVERY` (CHECK: no table, customer required). `POST /api/v1/pos/orders {mode: DELIVERY, delivery: {customerId, addressId | address, zoneId, promisedAt?, notes?}}` opens the order and its `Delivery` (`PENDING`) with an **address snapshot**, editable while PENDING and frozen at dispatch.
- Lifecycle: `dispatch {courierEmployeeId}` (the courier is assigned to the store; no drafts left, at least one confirmed round) → `OUT_FOR_DELIVERY`; `delivered` → `DELIVERED`; `returned {reason}` → `RETURNED`, then a re-dispatch, or voids and a cancel. Every transition writes a `DeliveryEvent`.
- **Separate states**: the delivery status never changes `Order.lifecycle`, the financial state or the fiscal state. `OrdersApi.close` refuses a DELIVERY order that is not DELIVERED (422 `DELIVERY_NOT_DELIVERED`).
- Zones and the fee (D128): `DeliveryZone(store, code, name, fee item, active)` in orders; opening a DELIVERY order adds the zone's fee item as an automatic draft (kind `FEE`), priced and taxed from the published release (422 `FEE_ITEM_NOT_PUBLISHED`).
- Settlement and production (D129): nothing new to settle; the cashier records the courier's cash in their own session. `GET /pos/settlement/open-orders` shows `mode`, `customerId` and `delivery`. Dispatch publishes `DeliveryDispatched`; production prints a non-fiscal delivery slip (`DELIVERY_SLIP` ticket) deduped on the dispatch event, so a re-dispatch prints again.
- The customer on an order (D130): `order_header.customer_id`, set at open or by `POST /pos/orders/{id}/customer`; it does not set the document's buyer.

## Rejected

- A `delivery` module: no entity in §8.1, and the order owns its mode.
- Delivery as a dining area: §8.2 says delivery needs no table.
- Delivery statuses as order lifecycle values: §8.3.
- A courier cash session (P49) and an invoice at dispatch (P48).

## Consequences

- Delivery history per customer is available through `GET /api/v1/backoffice/deliveries`.
- Proofs: `DeliveryLifecycleTest`, `DeliveryCommandsTest`, `DeliveryStatesIndependentTest`, `DeliveryCloseGuardTest`, `DeliveryTransitionRaceTest`, `DeliveryZoneTest`, `DeliveryFeeLineTest`, `DeliveryMergeTest`, `DeliverySlipTest` (orders); `DeliverySlipOnceTest`, `DeliveryHeaderTest` (production); `OpenOrdersDeliveryTest` (settlement); `DeliveryModeProjectionTest` (reporting).
