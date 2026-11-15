# BackOffice BFF

The thin backend-for-frontend of the BackOffice SPA (R6 P69, D176, D184 amendment). It is a separate Spring Boot project
(Spring Boot 4.1.2 and Java 25, the same versions as `backend/`). It logs the user in at Keycloak as the confidential
client `backoffice-bff` and holds the tokens in its own server-side session. It relays the SPA's API calls to the
backend with the user's access token. The browser only ever holds the session cookie and a CSRF cookie, never a token.

## What it does

| route | behaviour |
| --- | --- |
| `GET /oauth2/authorization/keycloak` | Starts the login: authorization code with PKCE `S256` (Spring Security 7 requires a proof key by default). |
| `GET /login/oauth2/code/keycloak` | The callback. It exchanges the code with `private_key_jwt` (RFC 7523) and then redirects to `/`, or to the pending step-up's `return` path. |
| `/api/v1/backoffice/**`, `/api/v1/auth/**` | Token relay. The BFF adds `Authorization: Bearer <access token>`, and adds `X-Tenant-Id` when a tenant is selected. It drops the browser's `Cookie`, `Authorization`, `X-Tenant-Id`, CSRF and `X-Forwarded-*` headers. The backend's `Set-Cookie` never reaches the browser. Every other part of the backend's answer comes back unchanged, including a `401` with `WWW-Authenticate: Bearer error="insufficient_user_authentication"`. Each request is sent once and never replayed (D184 amendment). |
| `PUT /bff/tenant {"tenantId": "<uuid>"}` | Remembers the selected tenant in the BFF session and answers `204`. A malformed id answers `400`. The BFF only relays the selection; the backend checks the membership (D177). |
| `GET /bff/step-up?acr_values=2&max_age=300&return=/path` | Starts a new authorization request with those values. `return` must be a same-origin path: the BFF answers `400` to `//host`, `///host`, a scheme, a backslash or a control character. `acr_values` must be `1` or `2`, and `max_age` must be a whole number of seconds. |
| `POST /bff/logout` | Ends the BFF session, then redirects to Keycloak's `end_session_endpoint` (RP-initiated logout, `post_logout_redirect_uri={baseUrl}/`). |

Any other path answers `403`. An anonymous call answers `401` and never redirects, so the SPA navigates to the login
itself. When a refresh is refused (for example when Keycloak detects refresh-token reuse and revokes the session), the
BFF answers `401` without calling the backend.

Cookies:

- `__Host-bff-session` carries `HttpOnly`, `Secure`, `SameSite=Lax` and `Path=/`. It idles out after 30 minutes, matching
  the SSO idle time in D175.
- `__Host-XSRF-TOKEN` is readable by JavaScript and carries `Secure`, `SameSite=Lax` and `Path=/`. The SPA echoes it as
  `X-XSRF-TOKEN` on every `POST`, `PUT`, `PATCH` and `DELETE`, including `/bff/tenant` and `/bff/logout`.

The `__Host-` prefix stops a sibling subdomain from setting either cookie.

Spring's authorized-client manager refreshes the access token when it is about to expire. It uses `private_key_jwt`
here too and keeps the rotated refresh token.

## Configuration

| property | environment variable | meaning |
| --- | --- | --- |
| `spring.security.oauth2.client.provider.keycloak.issuer-uri` | `RESTAURANTPRO_OIDC_TENANT_ISSUER` | the `restaurantpro` realm issuer. Discovery runs at startup. |
| `restaurantpro.bff.backend-url` | `RESTAURANTPRO_BACKEND_URL` (default `http://localhost:8080`) | the backend |
| `restaurantpro.bff.client-key-file` | `RESTAURANTPRO_BFF_CLIENT_KEY_FILE` | a **private JWK** (RSA or EC, with a `kid`) whose public half is registered on `backoffice-bff` |
| `restaurantpro.bff.backend-timeout` | | the connect and request timeout of a relayed call (default `PT30S`) |

The key file is read once at startup, and startup fails when it is missing or holds no private key. The error message
names only the file path, never the key's content.

The BFF listens on port `8443`, which matches the redirect URI and web origin registered in
`deploy/keycloak/realm-restaurantpro.json`. Serve it over TLS (`SERVER_SSL_*`, or a TLS-terminating proxy that sends
forwarded headers) and on the same origin as the SPA.

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

- The tokens live in the servlet container's in-memory session. That means one BFF instance, or sticky sessions behind
  the load balancer. A shared session store only becomes necessary when the BFF runs on several instances.
- The BFF does not forward the client's IP address to the backend. When the backend's audit needs the caller's IP, it
  needs a trusted, BFF-set header.
