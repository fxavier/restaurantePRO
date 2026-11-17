# F05 — Job lease renewal for runs longer than `lease-ttl`

**Status:** needs-triage
**Source:** S71 G5 (Low), D185
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

A reach-ALL run for one tenant that lasts longer than `restaurantpro.jobs.lease-ttl` (PT2M) loses its lease, and a second instance may start the same `(job, tenant)`.

## Proposed direction

Renew the lease from the running job (heartbeat every ttl/3) or derive the ttl per job from its statement timeout; a latch test with two instances and a run longer than the ttl.
