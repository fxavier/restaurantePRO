# F14 — Bound the `ReleaseFamilies` cache

**Status:** needs-triage
**Source:** S74d G5 (accepted with a ceiling comment)
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

`ReleaseFamilies` keys by `(tenantId, releaseId)` in an unbounded map; with many tenants and releases it grows without limit.

## Proposed direction

A bounded LRU (Caffeine is on the cache allow-list or a `LinkedHashMap` with access order) and a test that eviction keeps tenants apart.
