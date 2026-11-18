# 0041. Frontend origins and POS delivery: one origin per app, the POS as a kiosk SPA without a service worker

Status: **Accepted** 2026-11-18 (user approval, frontend R1 Phase 2 gate; frontend R1 alignment, rounds R2–R3). Source: [frontend-conventions.md](../architecture/frontend-conventions.md) §3, §6, §11; spec §2, §9, §10, §11; [ADR 0040](0040-saas-multitenancy-tenant-id-rls-keycloak.md) (D182, D184); [bff/README.md](../../bff/README.md).

## Context

Both React apps call `/api/v1/auth/**`, but through different paths: the BackOffice through the BFF (which relays it with the user's token and requires its own origin for the `__Host-` cookies), the POS straight to the backend with a devices-realm bearer and `X-Pos-Session` (D182). The backend has no CORS configuration. The POS runs on shared terminals in a dining room; §11 and ADR 0040 forbid offline confirmation, and an app shell cached by a service worker can serve stale code or look available when it is not.

## Decision

- **One origin per app, each same-origin with its API.** `backoffice.<domain>` serves the BackOffice SPA and the BFF; `pos.<domain>` serves the POS SPA and a reverse proxy for `/api/v1/pos/**` and `/api/v1/auth/**` to the backend. The backend stays without CORS. In development each app's Vite server proxies to the BFF or to the backend.
- **The POS is a plain SPA run in a browser's kiosk mode** (Chrome or Edge `--kiosk`, a setup note per terminal), landscape, ≥ 1024 × 768. **No service worker, no web manifest** in R1.
- **POS session handling:** the device key is a non-extractable WebCrypto P-256 key in IndexedDB (D182); the terminal access token and the POS session token are kept in memory only; after 90 s idle or on "Trocar empregado" the app logs the employee out and returns to the PIN pad (draft lines are server rows and are kept).
- Environment values (devices issuer, origins) are Vite build-time variables; one build per environment.

## Alternatives considered

- **One origin for both apps with path prefixes:** fewer DNS names, but `/api/v1/auth/**` would need routing by caller, and the BFF's `__Host-` cookies would be sent on POS requests.
- **POS calling the backend cross-origin with CORS:** no reverse proxy, but a CORS policy to own in the backend and a preflight on every POS command.
- **Installable PWA:** fullscreen install and an icon, but a service worker cache to manage so it never serves stale code or fakes availability; revisit when offline work is in scope.
- **Client lock screen keeping the session:** faster resume, but without a backend PIN-verify endpoint the lock would be a fake one.

## Consequences

- Deploy gains a static host and a reverse-proxy rule per app; the devices realm's `pos-*` clients need the POS origin as a web origin so the browser can call the token endpoint (backend gap G13). The BackOffice's dev origin must be a redirect URI of `backoffice-bff` in the dev realm (gap G15).
- Clearing a terminal's site data un-enrols it; an administrator issues a new code (D182).
- A terminal shows "Sem ligação" and confirms nothing whenever the backend, the reverse proxy or Keycloak is unreachable.
