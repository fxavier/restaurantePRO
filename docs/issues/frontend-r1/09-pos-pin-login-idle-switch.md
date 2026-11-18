# 09: POS PIN login, idle logout and switching employee

**Status:** ready-for-agent
**Blocked by:** 08
**Owner:** `frontend-developer`. Spec: PRD stories 23–29, AC4; ADR 0008; FC §6.

## What to build

An enrolled terminal shows the PIN pad: the employee code, then the PIN with hidden digits, on 72 px keys. A successful login keeps the POS session in memory. If the login answers `mustChangePin`, the PIN-change screen comes first. A wrong PIN gets one generic message.

The app logs out and returns to the PIN pad in three cases: after 90 s without input, on "Trocar empregado", or on `POS_SESSION_INVALID`. Draft lines stay on the server.

## Acceptance criteria

- [ ] App-level tests cover:
  - success;
  - a wrong PIN;
  - a locked employee;
  - a forced PIN change;
  - the idle logout, using fake timers;
  - switching employee;
  - session invalid;
  - "Sem ligação".
- [ ] The PIN never appears in a request log, a query key, storage or an error report. A test asserts storage and query keys.
- [ ] Barcode or keyboard entry also works on the PIN pad.
- [ ] **Visual gate:** screenshots of the enrolment and PIN screens at 1024×768 and 1366×768 are shown to the user, and work on 10 waits for their approval.
