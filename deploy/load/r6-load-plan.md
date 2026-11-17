# R6 load plan and results — A92 (carrying the RLS run's A77)

**Criterion (spec §12, multitenancy-decisions A92):** p95 < 500 ms for simple local commands and queries with **20 tenants × 10 POS terminals**; printing and external services excluded. The RLS run left A77 open (`POST /pos/orders/{id}/discounts` p95 701–962 ms, 10 terminals, unproven against a no-RLS baseline); D189 asks for a no-RLS baseline on the same machine before any optimisation.

## Instrument

`backend/src/test/java/com/xavier/backend/e2e/R6TenantLoadSmoke.java`, an in-JVM smoke like `R3LoadSmoke` (named outside Surefire's include patterns: never in the gate, never skipped). It boots the backend on a random port against its own PostgreSQL 18.6 container (Flyway as `restaurantpro_owner`, the pool as `restaurantpro_app`, Hikari 20 as shipped), the real HTTP API and security chain with test-signed tokens of the tenant, ops and devices realms (the real Keycloak is `KeycloakLoginIT`'s; token validation cost is the same), and the Keycloak admin port faked.

```bash
cd backend && rtk mvn test -Dtest=R6TenantLoadSmoke \
  [-Dload.tenants=20] [-Dload.terminals=10] [-Dload.seconds=120] [-Dload.warmup-seconds=30] \
  [-Dload.items=100] [-Dload.think-ms=1000] [-Dload.gate=true]
```

`deploy/load/load-smoke.sh` (the stack smoke of A56/A77) cannot run against an R6 stack yet: it logs in with a password and enrols terminals the pre-R6 way (follow-up F24).

## Seed (not measured)

Per tenant: onboarding by the platform API (the event chain to ACTIVE), then through its administrator: one store, 4 tables per terminal, 100 items at 16 % VAT (5.00–54.00, fixed seed), a kitchen printer and routes (jobs stay QUEUED: no bridge claims them), fiscal series FR, discount limits 15 % free / 50 % max published in a promotions release, and per terminal a register, a cashier, the terminal's enrolment (written as the owner, as `Installation.enrolled`), its PIN login and an open cash session.

## Measured cycle (each terminal, then a think time of 1000 ms after every request)

`GET /pos/tables`, `GET /pos/catalog`, `GET /pos/promotions/current`, `POST /pos/tables/{id}/open`, 2 × `POST /pos/orders/{id}/lines`, `GET /pos/orders/{id}`, `POST /pos/orders/{id}/batches` (catalog and promotion versions, previewed gross), `GET /pos/orders/{id}`, `POST /pos/orders/{id}/discounts` (10 % on one line, the A77 route), `GET /pos/orders/{id}/settlement`, `POST /pos/payment-attempts` (cash, the due), `POST /pos/orders/{id}/close`; terminal 1 of each tenant also reads a 1-day sales report as its administrator. `CLOSE` issues the fiscal document through the fiscal adapter port (an external call, D48) and is **reported, not gated**, as in R3/S54. The think time is the load model (a cashier is slower; 200 terminals offer at most ~200 requests/s); it is not a wait for a result.

## Baseline method (why three phases)

A run without row-level security is **correct only while one company has data**: the tenant predicate of tenant-wide tables is RLS's alone (D179; the code never filters by tenant). With `BYPASSRLS` and three tenants, `PaymentAttemptService.payableMethod` → `findByCode("CASH")` returned three rows and the payment failed with 500 (first trial run). A 20-tenant no-RLS baseline is therefore impossible by design, so:

| phase | data and load | RLS | what it shows |
|---|---|---|---|
| warm-up | 1 tenant × 10, 30 s | on | JIT and pools warm (not reported) |
| **P1** | the bootstrap tenant only (the only company with data) × 10 terminals | **off**: superuser `ALTER ROLE restaurantpro_app BYPASSRLS`, pool soft-evicted, probe `rolbypassrls` true through the pool | the no-RLS baseline |
| **P2** | the same data and terminals | on (`NOBYPASSRLS`, evicted, probed false) | P2 − P1 = the cost of the tenant and store policies; also A77's configuration (10 terminals, one company) |
| **P3** | 19 more tenants onboarded and seeded the same way; all 200 terminals at once | on | the A92 gate |

## Results

### Run 1 — 2026-11-17 13:44–13:54 UTC, `R6TenantLoadSmoke` as committed in `e9e6853` (product code = main `9817098` + #112 + #113)

Machine: a laptop, Intel Core i9-9880H 2.3 GHz, 16 logical CPUs, 16 GB; macOS 15.3; OpenJDK 25.0.4.1 (JVM max heap 4 GB); Docker Desktop VM 16 CPUs / 7.7 GB running PostgreSQL 18.6 (Testcontainers). Server, database and the 200 clients on one machine. Load average 9–11 at the start: the parallel S76b node's suite ran until about 13:50, i.e. during the seed, the warm-up and part of P1/P2; P3 ran after it. Volume at the end: 20 tenants (bootstrap + 19), 39 stores, 2 000 items, 2 406 order lines, 1 203 fiscal documents, 663 print jobs. Seed 51 s. 0 request errors in every phase.

p50/p95/p99/max in ms; P2−P1 at p95; verdict on P3's p95 (< 500 ms):

| operation | P1 no RLS, 1×10 | P2 RLS, 1×10 | P3 RLS, 20×10 (n) | P2−P1 | P3 |
|---|---|---|---|---|---|
| `GET /pos/tables` | 33/198/244/244 | 22/146/175/175 | 801/1664/2078/2463 (1000) | −52 | MISS |
| `GET /pos/catalog` | 27/64/96/96 | 20/69/87/87 | 461/1338/1836/2213 (1000) | +5 | MISS |
| `GET /pos/promotions/current` | 26/41/70/70 | 20/34/39/39 | 364/934/1421/1893 (1000) | −7 | MISS |
| `POST /pos/tables/{id}/open` | 69/209/249/249 | 43/159/177/177 | 768/1614/1977/2380 (1000) | −50 | MISS |
| `POST /pos/orders/{id}/lines` | 56/87/107/108 | 36/84/93/94 | 741/1293/1601/1999 (2000) | −3 | MISS |
| `GET /pos/orders/{id}` | 34/89/116/118 | 25/52/56/56 | 524/963/1290/2076 (2000) | −37 | MISS |
| `POST /pos/orders/{id}/batches` | 74/140/175/175 | 49/123/167/167 | 834/1834/2160/2466 (1000) | −17 | MISS |
| `POST /pos/orders/{id}/discounts` (A77) | 73/130/139/139 | 46/101/144/144 | 1017/1709/2138/3245 (1000) | −29 | MISS |
| `GET /pos/orders/{id}/settlement` | 42/86/96/96 | 29/93/102/102 | 605/1124/1579/2556 (1000) | +7 | MISS |
| `POST /pos/payment-attempts` | 90/116/140/140 | 52/138/176/176 | 1358/2454/3076/3893 (1000) | +22 | MISS |
| `GET /backoffice/reports/sales` | 31/49/49/49 | 28/78/78/78 | 256/1223/1434/1618 (100) | +29 | MISS |
| `POST /pos/orders/{id}/close` (reported) | 228/425/466/466 | 131/351/414/414 | 3637/5124/5623/6205 (1000) | −74 | reported |

Throughput: P1 9.2, P2 8.9, P3 91.9 measured requests/s (89, 90 and 1 000 cycles).

**Reading.**
- **RLS cost (A77, D189): not measurable at 10 terminals.** P2−P1 is between −52 and +29 ms at p95, within the run-to-run noise of 90 cycles (P1 ran first, on a machine still loaded by the parallel suite). Every P2 p95 is below 500 ms, including the A77 route `POST /pos/orders/{id}/discounts` (101 ms). With RLS on and one company, §12 holds at 10 terminals at this pace.
- **A92 at 20 × 10: MISS.** Every gated operation misses at p95 (934–2 454 ms) with p50 already 0.4–1.4 s: the system is saturated at about 92 requests/s (200 terminals at a 1 s pace offer up to ~200/s). P3 is not an RLS-versus-no-RLS comparison (no 20-tenant no-RLS run is possible, above); what saturates is not established by this run. Candidates: the Hikari pool of 20 sized for 10 terminals (D40), CPU shared by server, database and 200 clients on one laptop. Run 2 below tests the first.

### Run 2 (diagnostic) — 2026-11-17 13:56–14:06 UTC, the same smoke with `-Dspring.datasource.hikari.maximum-pool-size=60 -Dload.seconds=60`

Not a configuration proposal: a check of whether the pool of 20 is what saturates P3. **Contaminated:** the parallel S76b suite (6 surefire JVMs, load average 10–12) ran during the whole run, so the numbers are not comparable with run 1. P3 p95 832–2 691 ms (CLOSE 10 219 ms), 74.1 measured requests/s, 0 errors; P1/P2 p95 49–375 ms. A larger pool did not remove the saturation under contention; the bottleneck is **not isolated** by these runs.

### Runs 3 and 4 — 2026-11-17 14:50–15:10 UTC, idle machine (no other suite or agent; load average 2.8 before run 3), with diagnostics

Same smoke, method and think time; it now also samples every 2 s the Hikari pool (active, idle, threads waiting), the application role's non-idle backends by state and wait event (`pg_stat_activity`), JVM/machine CPU and load average, and counts per phase the database transactions (`pg_stat_database`, minus the background rate measured in a 10 s idle window before the phase) and `tenancy.resolve_membership` calls (`track_functions = all`). An external sampler recorded load average and the PostgreSQL container's CPU (`docker stats`). Run 4 = run 3 with `-Dspring.datasource.hikari.maximum-pool-size=60` (test configuration only).

p95 ms (P1 no RLS 1×10 / P2 RLS 1×10 / P3 RLS 20×10, pool 20 → P3 pool 60):

| operation | P1 | P2 | P3 pool 20 | P3 pool 60 |
|---|---|---|---|---|
| tables | 65 | 64 | 951 MISS | 942 MISS |
| catalog | 34 | 36 | 782 MISS | 673 MISS |
| promotions | 29 | 32 | 606 MISS | 467 |
| open | 112 | 176 | 642 MISS | 662 MISS |
| line | 61 | 81 | 557 MISS | 569 MISS |
| order | 39 | 51 | 413 | 460 |
| confirm | 77 | 140 | 652 MISS | 847 MISS |
| discounts (A77) | 74 | 87 | 1132 MISS | 1102 MISS |
| settlement | 65 | 65 | 741 MISS | 717 MISS |
| pay | 98 | 105 | 1468 MISS | 1458 MISS |
| sales report | 28 | 31 | 499 | 416 |
| close (reported) | 299 | 307 | 3244 | 3386 |

Throughput: P1/P2 8.9 requests/s each (90 cycles); P3 115.8 (pool 20, 1 207 cycles) and 117.9 (pool 60). 0 errors. Load average during P3 17–23 (run 3), 12–16 (run 4); before P1 ≈ 3–6.

**Diagnosis (P3).**
- **Pool-bound, but not by database work.** Pool 20: active 18.3 of 20, 56 threads waiting on average (max 161). Of the busy connections, **11 are "idle in transaction / ClientRead"**: the transaction holds the connection while the application, not the database, is busy between statements; ~1.7 are actively running SQL. Pool 60: active 49.7, still 30 waiting, **34 idle in transaction**: a larger pool only moves the queue into open transactions, and p95 does not improve.
- **The database is not saturated:** the PostgreSQL container at ~450–650 % of 1 600 % CPU; wait events are negligible (a little `LWLock/WALWrite` and `Lock/object` at pool 60).
- **The machine is:** 85 % CPU during P3, with server, database and 200 client threads on one laptop; application threads that hold a transaction are starved between statements, which is what "idle in transaction / ClientRead" shows.
- **Per request the database work is heavy:** ~13 transactions per HTTP request after removing the background (each with a `set_config` bind at begin: event listeners in their own transactions, the event publication registry, idempotency's short transactions); and the **background jobs alone cost ~10 transactions/s per active tenant** (≈ 190–200/s for 20 tenants idle: PrintWorker every second per tenant with lease acquire and release, sweepers, gauges).
- **Membership resolution is not the cause:** 1.0 `resolve_membership` call per request, 0.3–0.4 ms of database time each.
- **RLS is not the cause:** P2 − P1 is within noise again (−8 to +64 ms at p95, all P2 < 200 ms).

## Verdict

- **A77 (RLS cost, 10 terminals, one company): met.** Every P2 p95 < 500 ms (A77's discount route 101 ms in run 1); P2−P1 within noise in both runs.
- **A92 (20 × 10): MISS** on this machine in every run (idle run 3: 8 of 10 gated operations, p95 557–1 468 ms) — [BUG-0004](../../docs/qa/bugs/0004-a92-p95-miss-at-20-tenants.md). Likely cause: application-side time inside open transactions on a CPU-saturated single host (server, database and load generator together), amplified by ~13 transactions per request and ~10 background transactions/s per tenant; not RLS, not membership resolution, not database CPU, and not the pool size (pool 60 changes nothing). Next, for the architect: rerun with the load generator and the database on separate hosts (or the target hardware); then profile the application (thread CPU, JDBC round trips per request) and decide on the per-tenant background polling (PrintWorker 1 s × tenants) and the transactions per command before any pool or instance sizing.
