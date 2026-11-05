# 0007. Server-side sessions with Spring Session JDBC

Status: Accepted, 2026-11-04 (D25). Source: [backend-decisions.md](../design/backend-decisions.md) D25; spec §5, §10.

## Context

Spec §5 needs sessions that can be listed and revoked per user, and that end when a terminal is revoked. Spec §10 requires secure cookies and CSRF protection for browser clients.

## Decision

Server-side sessions stored by Spring Session JDBC in PostgreSQL (`shared.spring_session`), so they survive restarts and can be listed or revoked. The cookie is `Secure; HttpOnly; SameSite=Lax`. CSRF uses the SPA cookie-to-header pattern (`XSRF-TOKEN` cookie, `X-XSRF-TOKEN` header). No JWT.

## Consequences

- Revocation is a database operation. Identity ends POS sessions on `TerminalRevoked`, on `StoreDeactivated` (D45) and when a manager resets an employee's PIN.
- Boot applies the cookie flags only on a real server; `WebServerSecurityTest` proves them, `SecurityConfigTest` proves CSRF.
- The session stores a serialized `Actor`. Changing its shape makes stored sessions unreadable. **As built (R2):** `config.SessionConfig` treats an attribute this release cannot read as absent, so the user gets 401 and logs in again (`OutdatedSessionTest`). See ADR 0012 for why `serialVersionUID` was not the mechanism.
- Every request reads the session row, which is acceptable for one installation with about 10 terminals (§12).
