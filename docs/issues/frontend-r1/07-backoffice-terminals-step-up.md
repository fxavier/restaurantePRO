# 07: BackOffice terminals and step-up

**Status:** ready-for-agent
**Blocked by:** 05, 06
**Owner:** `frontend-developer`; `qa-engineer` for the e2e. Spec: PRD stories 12–20, AC2, AC10; FC §6, §8, §9.

## What to build

The terminals area of the selected store:

- **List** with kind and state.
- **Register** a POS terminal with a name.
- **Issue the one-time enrolment code**, shown once, large and copyable, with its 24 h single-use note.
- **Revoke** a terminal, after a confirmation.

When an action answers `MFA_STEP_UP_REQUIRED`, a dialog explains the step. The pending command, which holds no secret, is kept in session storage, and the browser goes to the BFF step-up. On return, the form is restored with "Continuar", which repeats the request with the same `Idempotency-Key`.

## Acceptance criteria

- [ ] App-level tests cover: empty and populated lists, a validation error on its field, step-up then resume with the same key, "Sem ligação" then retry with the same key, a 403, and the code shown once and gone after closing.
- [ ] The code is never written to storage, logs, query keys or the URL. A test asserts this for storage.
- [ ] A Playwright test of flow (a) on the real stack: sign in with OTP, pick a store, register a terminal, issue a code through step-up, and check the action ran once.
- [ ] Axe finds no serious or critical violation.
