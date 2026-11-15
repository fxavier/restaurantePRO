# S76 — Integrate: end to end, strict coverage, load, ADR 0040 accepted

**Status:** ready-for-agent
**Blocked by:** S71, S72, S73, S74a, S74b, S74c, S74d, S75

## Goal

The integrated tree proves A80–A92 end to end over HTTP with real security: tenants A and B with identical codes, POS, managers, an accountant with two memberships and support in A all see exactly their tenant; suspension of B takes effect mid-test; coverage is strict over every schema; the load smoke meets §12 at 20 tenants × 10 terminals against a no-RLS baseline; ADR 0040 becomes Accepted. Then the architecture review (G6).

## Write scope (paths it may touch)

- `src/test/.../e2e` (`R6AcceptanceTest`)
- `RowLevelSecurityCoverageTest`, `CompositeForeignKeyCoverageTest` (all-schemas strict mode)
- removal of `CompanyBootstrapRunner`'s pre-SaaS path; migration `shared/V1012` only if needed (drop `rls_unscoped_table`)
- `CONTEXT.md` (the terms slices proposed), ADR 0040 → Accepted, ADR README, `docs/architecture/modules/`, the spec's and the decision doc's amendment markers, the load plan

## Must not touch

production code outside the paths above; any fix beyond integration goes back to the owning ticket's area as a recorded orchestrator change

## Named tests (red first)

`R6AcceptanceTest`, strict `RowLevelSecurityCoverageTest` and `CompositeForeignKeyCoverageTest`, the load smoke (A92 / RLS A77), every drill once

## Acceptance criteria

A80–A92 end to end (see the spec's "Acceptance criteria → slices").

- [ ] Named tests written and red for the expected reason (recorded)
- [ ] Code makes them green; slice tests pass
- [ ] Full suite green as `restaurantpro_app`, 0 skipped
- [ ] G5 review by a fresh reviewer: MERGE, the 10 security rules checked
- [ ] PR to `main` opened (not stacked)

## Verification

```bash
cd backend && rtk mvn test -Dtest='R6AcceptanceTest,RowLevelSecurityCoverageTest,CompositeForeignKeyCoverageTest'
cd backend && rtk mvn clean test   # G4: BUILD SUCCESS, 0 failures, 0 errors, 0 skipped, as restaurantpro_app
```

## Agent and parallelism

- Suggested agent: qa-engineer (top model); G6 by software-architect
- Parallel: No — runs alone, last.

## Rules (all R6 tickets)

- Spec: [r6-multitenancy.md](../../prd/r6-multitenancy.md) (APPROVED). Its frozen names, error codes, SQL signatures and Flyway versions are binding; internals are the implementer's call.
- Red first: write the named tests, run them, record the red line and its cause; then the code. Deterministic (injected `Clock`, latches not sleeps), real PostgreSQL, no `@Disabled`, 0 skipped.
- Own worktree and branch from `main` after every blocker has merged; one PR to `main`, never stacked; the user merges.
- G5: a fresh code reviewer (top model) that never saw the work checks the spec's **10 security rules** and runs mutants on every new rule.
- Stop and ask the user on any condition of the spec's "Stop and ask" list.
- Shell commands through `rtk`; `rtk git add <explicit paths>` only.
