# 0008. Two login flows, one identity model

Status: Accepted, 2026-11-04 (D26; R1 follow-ups built in S10). Source: [backend-decisions.md](../design/backend-decisions.md) D26, [r2-decisions.md](../design/r2-decisions.md) §12; spec §3, §5, §10.

## Context

BackOffice users log in from a browser; POS operators log in on a shared terminal, fast, with a short PIN. Spec §10 requires strong credentials and protection against guessing.

## Decision

- BackOffice: username and password, BCrypt, a strong-password rule. MFA (TOTP) is deferred until remote admin access is planned.
- POS: employee code and PIN, only on an enrolled terminal. An admin enrols a terminal once with a one-time code, exchanged for a long-lived device token; only SHA-256 hashes of code and token are kept. The PIN is BCrypt-hashed. Five consecutive wrong PINs lock the employee until a manager unlocks it, audited. The POS session carries `employeeId`, `terminalId` and `storeId`.

## Consequences

- **Login throttle (S10, as built):** BackOffice logins are counted per username + client address (10 attempts, every attempt counted before the password check, reset by a successful login) and per address across usernames (30 failures; successes neither count nor reset it), both blocking for 15 minutes with 429 and `Retry-After` (`identity.LoginThrottledException`, code `LOGIN_THROTTLED`). The window is fixed from the first attempt, not sliding as the follow-up text said. Keys are stored as SHA-256, and the block is audited once, on its first 429. There is no username-only counter, so nobody can lock an administrator out from other addresses. Distributed guessing against one username is accepted for a LAN-only install.
- **mustChangePin (S10):** `reset-pin` sets `mustChangePin`. The next POS login must set a new PIN (`PIN_UNCHANGED`, `PIN_CHANGE_NOT_REQUIRED`) before the session gets any authority. A reset also ends the employee's open POS sessions.
- The bootstrap admin must change the initial password at first login (D30).
