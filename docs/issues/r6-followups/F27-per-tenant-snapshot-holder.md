# F27 — A per-tenant snapshot holder for stateful gauges and alerts

**Status:** needs-triage
**Source:** G6 architecture review (R6), 2026-11-17
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

Three hand-rolled per-tenant maps (`ProductionMetrics`, `InDoubtGauge`, `CashDiscrepancyAlertSource`) each implement accumulation across tenants, staleness and eviction of non-ACTIVE tenants their own way.

## Proposed direction

One shared holder (tenant → snapshot with timestamp, staleness from the poll interval, eviction of non-ACTIVE tenants) used by all three; one test of its rules instead of three.
