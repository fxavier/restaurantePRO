# BackOffice BFF

The thin backend-for-frontend of the BackOffice SPA (R6 P69, D176, D184 amendment). It is a separate Spring Boot project
(Spring Boot 4.1.2 and Java 25, the same versions as `backend/`). It logs the user in at Keycloak as the confidential
client `backoffice-bff` and holds the tokens in its own server-side session. It relays the SPA's API calls to the
backend with the user's access token. The browser only ever holds the session cookie and a CSRF cookie, never a token.

## What it does

| route | behaviour |
| --- | --- |
| `GET /oauth2/authorization/keycloak` | Starts the login: authorization code with PKCE `S256` (Spring Security 7 requires a proof key by default). It sends no `acr_values`: Keycloak applies the client's default ACR `2`, so every login asks for the password and the OTP (D184, FE-F07). |
| `GET /login/oauth2/code/keycloak` | The callback. It exchanges the code with `private_key_jwt` (RFC 7523) and then redirects to `/`, or to the pending step-up's `return` path. |
| `/api/v1/backoffice/**`, `/api/v1/auth/**` | Token relay. The BFF adds `Authorization: Bearer <access token>`, and adds `X-Tenant-Id` when a tenant is selected. It drops the browser's `Cookie`, `Authorization`, `X-Tenant-Id`, `X-Expected-Tenant`, `X-Expected-User`, CSRF and `X-Forwarded-*` headers. Any method but `GET`, `HEAD` and `OPTIONS` carrying `X-Expected-User` must name the session's user (the Keycloak subject of the BFF login); a different value answers `409 SESSION_CHANGED` and nothing is relayed (FE-F22, see below). When a tenant is selected, any method but `GET`, `HEAD` and `OPTIONS` must carry `X-Expected-Tenant: <the tenant the tab shows>`; a missing or different value answers `409 TENANT_CHANGED` and nothing is relayed (FE-F16, see below). Without a selection, a request carrying `X-Expected-Tenant` (a UUID, else `400`) is relayed with that value as `X-Tenant-Id`: the backend checks it against the caller's memberships (D177) and refuses a foreign one with `403 TENANT_FORBIDDEN`, which passes through. So a tab pinned to a company the session's user does not belong to (another user logged in from another tab) can neither read nor write in that user's company. Without a selection or the header, the backend resolves the caller's only membership. Every relayed answer carries `X-Relayed-Tenant` with the tenant it was relayed in (the selection, else the expected tenant; read once per request, the same value the check and the relay used); none when neither exists, and none on `GET /api/v1/auth/tenants`, for which the backend resolves no tenant (FE-F23). Every relayed answer carries `X-Relayed-User` with the session user's Keycloak subject. A backend-supplied `X-Relayed-Tenant` or `X-Relayed-User` never reaches the browser. The backend's `Set-Cookie` never reaches the browser. Every other part of the backend's answer comes back unchanged, including a `401` with `WWW-Authenticate: Bearer error="insufficient_user_authentication"`. Each request is sent once and never replayed (D184 amendment). |
| `PUT /bff/tenant {"tenantId": "<uuid>"}` | Remembers the selected tenant in the BFF session and answers `204`. It is not checked against `X-Expected-Tenant` (it is the switch itself), and neither is `POST /bff/logout`. A malformed id answers `400`. The BFF only relays the selection; the backend checks the membership (D177). |
| `GET /bff/step-up?acr_values=2&max_age=300&return=/path` | Starts a new authorization request with those values. `return` must be a same-origin path: the BFF answers `400` to `//host`, `///host`, a scheme, a backslash or a control character. `acr_values` must be `1` or `2`, and `max_age` must be a whole number of seconds. |
| `POST /bff/logout` | Ends Keycloak's session over the back channel with a `POST` to the `end_session_endpoint` (refresh token plus `private_key_jwt`). Then ends the BFF session and redirects the browser to the `end_session_endpoint` with only `client_id` and `post_logout_redirect_uri={baseUrl}/`, so no token appears in any URL. A caller naming `application/json` in `Accept` (the SPA's `fetch`, which cannot follow that cross-origin redirect) gets `200 {"logoutUrl": "<that URL>"}` instead and navigates to it itself (FE-F10); `Accept: */*` alone, as a browser form sends it, keeps the redirect. Without an `end_session_endpoint` the URL is `{baseUrl}/`. The BFF session ends even when Keycloak does not answer. |

Any other path answers `403`. The BFF's own errors are ProblemDetails:

- `401 BFF_SESSION_EXPIRED` for an anonymous call, which is never redirected (the SPA navigates to the login itself).
  The same answer comes back when Keycloak refuses the refresh (400 `invalid_grant`, or 401/403 for a disabled client
  or a rejected assertion): retrying cannot help. The backend is not called.
- `503 IDP_UNAVAILABLE` only when Keycloak cannot be reached in time or answers 5xx. The session and its tokens are
  kept, so the SPA retries later. The backend is not called.
- `502 BACKEND_UNAVAILABLE` when the backend cannot be reached.
- `413 BFF_PAYLOAD_TOO_LARGE` for a request body over `max-request-body`.
- `409 TENANT_CHANGED` for a relayed call of any method but `GET`, `HEAD` and `OPTIONS` whose `X-Expected-Tenant` is missing or differs
  from the session's selected tenant. The selection is one per session and shared by every tab, so this is a tab that
  still shows the company another tab switched away from. The backend is not called; the SPA reloads the page in the
  selected tenant. Rules (FE-F16): reads are not checked (they cannot change data in the wrong company);
  without a selection there is nothing to compare with, so the expected tenant is relayed as the tenant and the
  backend decides (see the relay row); with a selection a missing header is refused
  like a different one, since a client that does not say which company it shows cannot be told apart from a stale tab.
- `409 SESSION_CHANGED` for a relayed call of any method but `GET`, `HEAD` and `OPTIONS` whose `X-Expected-User` differs
  from the Keycloak subject of the session's login. The session is one per browser, so this is a tab loaded for a user
  who has since been replaced by another login from another tab, possibly in the same company (FE-F22). The backend is
  not called; the SPA reloads the page. The subject is the only user id the BFF knows (the backend's account id
  `SessionView.userId` differs), so the SPA pins the subject from `X-Relayed-User`. A missing header is relayed: a tab
  sends none only before its first session read, when it holds nothing of anyone.

A backend status Spring has no constant for (for example `499`) passes through unchanged.

Cookies:

- `__Host-bff-session` carries `HttpOnly`, `Secure`, `SameSite=Lax` and `Path=/`. It idles out after 30 minutes, matching
  the SSO idle time in D175.
- `__Host-XSRF-TOKEN` is readable by JavaScript and carries `Secure`, `SameSite=Lax` and `Path=/`. The SPA echoes it as
  `X-XSRF-TOKEN` on every `POST`, `PUT`, `PATCH` and `DELETE`, including `/bff/tenant` and `/bff/logout`.

The `__Host-` prefix stops a sibling subdomain from setting either cookie.

The tokens live in the BFF's HTTP session: one set per login, gone with the session. Spring's default store keeps one
set per user name in memory instead, so two logins of the same user would share and overwrite each other's tokens.
Spring's authorized-client manager refreshes the access token when it is about to expire, with `private_key_jwt`, and
keeps the rotated refresh token. The manager is locked per session (`OneRefreshPerSession`), so parallel calls on one
session refresh once. Keycloak's reuse detection would otherwise revoke the session.

## Configuration

| property | environment variable | meaning |
| --- | --- | --- |
| `spring.security.oauth2.client.provider.keycloak.issuer-uri` | `RESTAURANTPRO_OIDC_TENANT_ISSUER` | the `restaurantpro` realm issuer. Discovery runs at startup. |
| `restaurantpro.bff.backend-url` | `RESTAURANTPRO_BACKEND_URL` (default `http://localhost:8080`) | the backend |
| `restaurantpro.bff.client-key-file` | `RESTAURANTPRO_BFF_CLIENT_KEY_FILE` | a **private JWK** (RSA or EC, with a `kid`) whose public half is registered on `backoffice-bff` |
| `restaurantpro.bff.backend-timeout` | | the connect and request timeout of a relayed call (default `PT30S`) |
| `restaurantpro.bff.idp-connect-timeout`, `.idp-read-timeout` | | the timeouts of the BFF's own calls to Keycloak: code exchange, refresh and back-channel logout (defaults `PT5S`, `PT10S`) |
| `restaurantpro.bff.max-request-body` | | the largest body the BFF relays (default `1MB`, that is 1 MiB) |

The key file is read once at startup, and startup fails when it is missing or holds no private key. The error message
names only the file path, never the key's content.

The BFF listens on port `8443`, which matches the redirect URI and web origin registered in
`deploy/keycloak/realm-restaurantpro.json`. Serve it over TLS (`SERVER_SSL_*`, or a TLS-terminating proxy that sends
forwarded headers) and on the same origin as the SPA. In development the SPA's Vite server (`http://localhost:5174`) is that
origin, and the BFF runs without TLS behind its proxy: see "BackOffice dev origin" in `deploy/keycloak/README.md`.

## Run and test

```bash
cd bff
rtk mvn clean verify                      # all tests
RESTAURANTPRO_OIDC_TENANT_ISSUER=https://auth.example/realms/restaurantpro \
RESTAURANTPRO_BFF_CLIENT_KEY_FILE=/run/secrets/backoffice-bff.jwk \
rtk proxy ./mvnw spring-boot:run
```

The tests need neither Docker nor Keycloak. `StubAuthorizationServer` plays the realm (discovery, JWKS, token
endpoint), and `StubBackend` records what the BFF relays. Both run on the JDK's `HttpServer`. Every login in the tests
goes through the real authorization-code flow.

## Limits (deliberate)

- The tokens live in the servlet container's in-memory session, and the refresh lock is that JVM's session mutex.
  That means one BFF instance, or sticky sessions behind the load balancer. Scaling the BFF out (D188) needs a shared
  session store and a lock that spans instances.
- The BFF does not forward the client's IP address to the backend (decided: no backend use after S72).
