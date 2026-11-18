# 02: Terminal clients accept browser token requests from the POS origin (G13)

**Status:** ready-for-agent
**Blocked by:** None (can start immediately)
**Owner:** `developer` (backend). Spec: PRD AC3, [ADR 0041](../../adr/0041-frontend-origins-and-pos-delivery.md), D182.

## What to build

When the backend provisions a `pos-<terminalId>` client in the devices realm, it sets the configured POS origin as the client's web origin, so a browser on the POS origin can call the devices realm's token endpoint with a signed assertion. Print-bridge clients get no web origin. The origin is one deployment property; it is required when terminals can be created.

## Acceptance criteria

- [ ] A test against the shared Keycloak container proves red first that a browser-style token request (with `Origin`) from the POS origin is refused before the change, and accepted afterwards with the CORS headers.
- [ ] A request from any other origin gets no CORS approval.
- [ ] `bridge-*` clients have no web origin.
- [ ] The property is documented with the other R6 properties, and the deploy env examples name it.
- [ ] The full backend suite passes.
