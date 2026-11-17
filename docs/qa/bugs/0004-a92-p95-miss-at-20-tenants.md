# BUG-0004: p95 misses §12 at 20 tenants × 10 terminals (A92)

- **Status:** open — likely cause identified (2026-11-17 runs 3 and 4 on an idle machine); **addressed by S77** (approved by the user): measurement topology first (load generator and database off the server's host), then fewer round trips per request (transaction bind, idempotency, event-registry transactions) and the per-tenant background polling. Design summary: `.scratch/sdlc/restaurantpro-backend-r6/s77-design.approved.md` (run ledger, main checkout).
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

## Runs 3 and 4 (idle machine, 2026-11-17 14:50–15:10 UTC)

P3 p95 with the shipped pool of 20: tables 951, catalog 782, promotions 606, open 642, line 557, order 413, confirm 652, discounts 1132, settlement 741, pay 1468, report 499 (close 3244); 115.8 requests/s. With the pool at 60 (test configuration): 942, 673, 467, 662, 569, 460, 847, 1102, 717, 1458, 416 (close 3386); 117.9 requests/s. P1/P2 (1 × 10, without/with RLS): every p95 ≤ 176 ms.

**Likely cause:** application-side time inside open transactions on a CPU-saturated single host. Pool 20: 18.3 of 20 connections busy, 56 threads waiting on average, 11 connections "idle in transaction / ClientRead"; pool 60: 50 busy, 30 waiting, 34 idle in transaction, no p95 gain. PostgreSQL ~450–650 % of 1 600 % CPU, negligible wait events; machine 85 % CPU with server, database and 200 clients together. Amplifiers: ~13 database transactions per HTTP request and ~10 background transactions/s per tenant (≈ 200/s at 20 tenants, mostly the 1 s PrintWorker per tenant with its lease). Not RLS (P2 − P1 within noise), not membership resolution (1 call, 0.3 ms per request), not the pool size. Details: [deploy/load/r6-load-plan.md](../../../deploy/load/r6-load-plan.md).

## Reproducing test

`e2e.R6TenantLoadSmoke` (`-Dload.gate=true` turns the miss into a failure).
