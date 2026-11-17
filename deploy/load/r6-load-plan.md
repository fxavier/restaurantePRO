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

## Verdict

- **A77 (RLS cost, 10 terminals, one company): met.** Every P2 p95 < 500 ms (A77's discount route 101 ms in run 1); P2−P1 within noise in both runs.
- **A92 (20 × 10): MISS** on this machine (run 1, the cleaner one: every gated p95 934–2 454 ms) — [BUG-0004](../../docs/qa/bugs/0004-a92-p95-miss-at-20-tenants.md). Next steps before go-live, in order: a quiet machine (or the target hardware) with server, database and load generator on separate hosts; then profile P3 (pool wait time `hikaricp_connections_pending`, database CPU and lock waits, Tomcat threads) before changing anything; the decision about pool sizing per instance and the number of backend instances (D185 allows several) belongs to the architect.

