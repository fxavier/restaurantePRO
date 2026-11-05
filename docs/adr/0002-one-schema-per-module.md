# 0002. One PostgreSQL schema per module, no cross-module foreign keys

Status: Accepted, 2026-11-04 (D6). Source: [backend-decisions.md](../design/backend-decisions.md) D6; spec §8.2.

## Context

Spec §6 makes the backend a modular monolith on one PostgreSQL database. Spec §8.2 says modules refer to each other by id and through public APIs. With a single `public` schema, nothing in SQL shows where one module ends.

## Decision

Each module owns one schema, created by its first migration (`CREATE SCHEMA IF NOT EXISTS <module>`): `organization.store`, `orders.order_header`, `cash.cash_movement`, and so on. Foreign keys **inside** a module, and the constraints behind its invariants, are mandatory. Foreign keys **across** modules are not declared. The owning module's API checks that a referenced id exists (for example `DiningApi`, `CatalogApi`, `OrdersApi`).

## Consequences

- Boundaries are visible in SQL, and a module could be extracted with its schema.
- Referential integrity across modules is a code responsibility. The module that writes the reference checks it through the owner's API, in the same transaction where it matters (for example orders asks dining to open the occupation).
- Invariants that span modules are held by a constraint in one module plus a call to the other. Example: at most one OPEN order per table (`orders.order_header` partial unique index) is backed by at most one open occupation per table (`dining.table_occupation` partial unique index).
- R2 added the money-fact immutability triggers per schema (orders lines, payments, cash movements, ISSUED fiscal documents), following the `audit_event` trigger (D18).
