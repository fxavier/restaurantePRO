# BUG-0004: p95 misses §12 at 20 tenants × 10 terminals (A92)

- **Status:** open — cause not isolated.
- **Severity:** High (release gate: spec §12, A92). No functional error: 0 failed requests in every phase.
- **Breaks:** spec §12 "p95 < 500 ms para comandos locais simples"; multitenancy-decisions A92 ("p95 < 500 ms for simple commands and queries with 20 tenants × 10 terminals"); PRD user story 32.
- **Environment:** `r6/s76-integrate` (main `9817098` + #112 + #113), `R6TenantLoadSmoke` at `e9e6853`; laptop Intel i9-9880H, 16 logical CPUs, 16 GB, macOS 15.3, Java 25.0.4.1, PostgreSQL 18.6 in Docker Desktop (16 CPUs, 7.7 GB); server, database and 200 clients on one machine; a parallel test suite (S76b) ran during part of run 1 and all of run 2.
- **Owner:** `solution-architect` (capacity: pool size per instance, instances, hardware) with `developer` for any hot path a profile finds.

## Steps to reproduce

```bash
cd backend && rtk mvn test -Dtest=R6TenantLoadSmoke    # 20 x 10, 120 s per phase, think 1000 ms, 100 items per tenant
```

## Expected

Every gated operation of P3 (20 tenants × 10 terminals, RLS on) with p95 < 500 ms.

## Actual (run 1, 2026-11-17 13:44–13:54 UTC)

P3 p95 (ms): tables 1664, catalog 1338, promotions 934, open 1614, line 1293, order 963, confirm 1834, discount 1709, settlement 1124, pay 2454, sales report 1223 (close, reported only: 5124); p50 already 364–1358; 91.9 measured requests/s for an offered ~200/s. With RLS at 1 × 10 on the same data (P2) every p95 is 34–159 ms, and P2−P1 (no RLS) is within noise: the miss is load, not row filtering per request. A diagnostic run with the pool at 60 (run 2) still missed, but ran under heavy contention and is inconclusive. Full tables: [deploy/load/r6-load-plan.md](../../../deploy/load/r6-load-plan.md).

## Reproducing test

`e2e.R6TenantLoadSmoke` (`-Dload.gate=true` turns the miss into a failure).
