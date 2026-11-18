# 12: POS "Pedir" and the second-terminal e2e

**Status:** ready-for-agent
**Blocked by:** 02, 05, 11
**Owner:** `frontend-developer`; `qa-engineer` for the e2e. Spec: PRD stories 45–49, AC5–AC9; §7, §9, §14 #2, #6, #7; FC §8–§11.

## What to build

"Pedir" is the one primary action of the order screen. It confirms the round with one `Idempotency-Key` per press. The button is disabled in flight. A network failure keeps the key for "Tentar novamente". A 409 "in progress" waits and resends the same key.

On `PRICES_CHANGED` or `CATALOG_CHANGED`, the panel shows the new values for the waiter to confirm again. On a version conflict, the app refetches and shows the current order. On success, the round moves to "Enviado" as returned by the server.

## Acceptance criteria

- [ ] App-level tests cover: a double tap sending one request, a retry with the same key, prices changed, catalog changed, a version conflict, "Sem ligação" disabling "Pedir", and success.
- [ ] Playwright flow (b) on the real stack:
  1. Enrol with a code issued through the API.
  2. Log in with a PIN.
  3. Open a table.
  4. Add three lines: a variant, a menu choice and a note.
  5. Press "Pedir".

  A second browser context, enrolled as a second terminal of the same store, sees the table occupied and the round without reloading. A forced duplicate request creates exactly one batch, checked through the API.
- [ ] A Playwright test stops the backend and sees "Sem ligação" with nothing shown as sent. A revoked terminal returns to enrolment.
- [ ] Axe passes on every POS screen. Screenshots at the DS viewports are attached to the PR.
