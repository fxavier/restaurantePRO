# S73 — Devices: terminal enrolment, POS sessions on terminal tokens, print bridge

**Status:** ready-for-agent
**Blocked by:** S72, S74a, S75

## Goal

An administrator enrols a POS terminal with a one-time code; the terminal authenticates with private_key_jwt; employees log in with code + PIN on top of the terminal token; a revoked terminal is refused on its next request. Printing and the cash drawer work through an outbound-only print bridge per store that cannot claim another store's or tenant's jobs.

## Write scope (paths it may touch)

- `identity/**` (enrolment, POS session bound to the terminal membership, Keycloak session listing/logout)
- `organization/**` (terminal client id, drop `device_token_hash`)
- `production/**` (`PrintWorker` → dispatcher, `/api/v1/bridge/**`, bridge enrolment, `BRIDGE` membership)
- `access/**` (`PosContext` from `X-Pos-Session`)
- `bridge/**` (new Java agent project, own `pom.xml`)
- migrations `identity/V3017`, `organization/V2006`, `production/V7012`

## Must not touch

`backend/pom.xml`, `application.yaml`, `config/**`, `shared/**`, `tenancy/**`, other modules, `deploy/**`

## Named tests (red first)

`PosTerminalAuthTest`, `TerminalEnrolmentThrottleTest`, `TerminalRevocationTest`, `BridgeJobClaimTest`, `BridgeOutcomeTest`

## Acceptance criteria

A86, A89 (see the spec's "Acceptance criteria → slices").

- [ ] Named tests written and red for the expected reason (recorded)
- [ ] Code makes them green; slice tests pass
- [ ] Full suite green as `restaurantpro_app`, 0 skipped
- [ ] G5 review by a fresh reviewer: MERGE, the 10 security rules checked
- [ ] PR to `main` opened (not stacked)

## Verification

```bash
cd backend && rtk mvn test -Dtest='PosTerminalAuthTest,TerminalEnrolmentThrottleTest,TerminalRevocationTest,BridgeJobClaimTest,BridgeOutcomeTest'
cd backend && rtk mvn clean test   # G4: BUILD SUCCESS, 0 failures, 0 errors, 0 skipped, as restaurantpro_app
```

## Agent and parallelism

- Suggested agent: developer (top model)
- Parallel: No — runs alone in group 2. **Stop and ask** if S75's O1 evidence shows the provisioner cannot be confined to `pos-*`/`bridge-*` clients.

## Rules (all R6 tickets)

- Spec: [r6-multitenancy.md](../../prd/r6-multitenancy.md) (APPROVED). Its frozen names, error codes, SQL signatures and Flyway versions are binding; internals are the implementer's call.
- Red first: write the named tests, run them, record the red line and its cause; then the code. Deterministic (injected `Clock`, latches not sleeps), real PostgreSQL, no `@Disabled`, 0 skipped.
- Own worktree and branch from `main` after every blocker has merged; one PR to `main`, never stacked; the user merges.
- G5: a fresh code reviewer (top model) that never saw the work checks the spec's **10 security rules** and runs mutants on every new rule.
- Stop and ask the user on any condition of the spec's "Stop and ask" list.
- Shell commands through `rtk`; `rtk git add <explicit paths>` only.
