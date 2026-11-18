# 06: BackOffice access shell

**Status:** ready-for-agent
**Blocked by:** 04
**Owner:** `frontend-developer`. Spec: PRD stories 1–11, AC1, AC10; FC §4–§6, §9; DS layouts.

## What to build

A signed-out user is sent to the BFF login. Once signed in, the shell shows:

- a sidebar with only the areas the user's `permissions` allow;
- a top bar with a tenant switcher (only with more than one membership; switching clears the cache), a store selector kept as `?storeId`, and a user menu with logout.

Full-page states cover an expired session (`BFF_SESSION_EXPIRED` goes to the login), a suspended or closed company, and "Sem ligação".

## Acceptance criteria

- [ ] App-level tests with MSW cover:
  - signed out, signed in;
  - one membership, two memberships and switching;
  - store selection that survives a reload;
  - a permission-hidden area;
  - session expired;
  - `TENANT_SUSPENDED`;
  - unavailable.

  Each test is red first.
- [ ] Keyboard-only navigation of the shell works, with visible focus. Axe finds no serious or critical violation.
- [ ] **Visual gate:** screenshots at 1280×800 and 1024×768 are shown to the user, and work on 07 waits for their approval.
