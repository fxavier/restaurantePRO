# F22 — Remove `CompanyBootstrapRunner`'s pre-SaaS path

**Status:** ready-for-agent
**Source:** S76 deliverable 3, not done (outside S76's write scope)
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

The runner still creates the bootstrap tenant's company from `restaurantpro.bootstrap.company.*` at startup. Removing it needs `CompanyBootstrapService`/`Properties`, `application.yaml`, `deploy/compose.yaml` and about 30 test classes that rely on a bootstrapped company, none in S76's scope.

## Proposed direction

A slice owning `organization/**`, the configuration and the test harness: keep the bootstrap tenant for migrated data, seed the test company through a fixture instead of the runner, delete the runner and its properties.

## Comments

- 2026-11-17 (G6 review, user decision): accepted as a recorded deviation from ADR 0040 for R6; this is the next follow-up.
