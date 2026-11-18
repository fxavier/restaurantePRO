# Keycloak realms (R6, D175, P71)

Minimal realm exports, imported at first start by the development Keycloak of `backend/compose.yaml` (S75: it mounts
this directory as `/opt/keycloak/data/import` and runs `start-dev --import-realm`; `backend/run-local.sh` then runs
`deploy/keycloak-provisioners.sh`), and by the shared Keycloak Testcontainer of the identity and end-to-end suites
(`SharedKeycloak`, D189). Keycloak 26.x, pinned. The production stack, `deploy/compose.yaml`, defines no Keycloak: it
only receives the realms' issuer URLs (`RESTAURANTPRO_OIDC_*_ISSUER`), since where production Keycloak runs is open
item O3. Against any Keycloak (`--url`), `deploy/keycloak-provisioners.sh` imports the devices realm if it is missing
and registers the provisioners' keys.

| file | realm | holds |
| --- | --- | --- |
| `realm-restaurantpro.json` | `restaurantpro` | every tenant's users, terminals and bridges; Organizations enabled (one organization per tenant) |
| `realm-restaurantpro-ops.json` | `restaurantpro-ops` | platform operators (`platform-operator`) and support staff; never tenant users |
| `realm-restaurantpro-devices.json` | `restaurantpro-devices` | terminals and print bridges (`pos-*`, `bridge-*` clients, created at run time); no user |

Both have the `restaurantpro-api` audience client (bearer only; the BFF's audience mapper adds it to every access
token), the `backoffice-bff` confidential client (authorization code + PKCE `S256`, `private_key_jwt`, default ACR `1`),
and the browser flow `loa browser`: LoA 1 password, LoA 2 OTP with a 300 s maximum age (D184, RFC 9470). Token
lifetimes: access 5 min, SSO idle 30 min, maximum 10 h, refresh tokens rotated on every use. No secret is in these
files: clients authenticate with `private_key_jwt`, and no user is exported. S72 owns the clients' redirect URIs and keys.
The tenant realm also enables fine-grained admin permissions (`adminPermissionsEnabled`) and declares the
`tenancy-provisioner` confidential client (service account, `client-jwt` with ES256, no browser, implicit or password
flow); its key and its permissions are set by S75's `deploy/keycloak-provisioners.sh`, never in this file. The
terminal (`pos-*`) and bridge (`bridge-*`) clients are created at run time by the provisioner (S73). A `pos-*`
client's one web origin is `restaurantpro.pos.origin` (`RESTAURANTPRO_POS_ORIGIN`, ADR 0041, G13), so the POS browser
may call the token endpoint; a `bridge-*` client has none.

The devices realm (S73/S75, O1 option 3) holds one client only, `device-provisioner`: confidential, `client-jwt`, a
service account and no browser, implicit or password flow. Its one `users` entry is that client's service-account user
(`serviceAccountClientId`, no credentials), which carries the `realm-management` roles `manage-clients` and
`view-clients` it needs to create and remove the `pos-*` and `bridge-*` clients. No person signs in to this realm. Its
key is registered by `deploy/keycloak-provisioners.sh`, as the `tenancy-provisioner` one is.

## BackOffice dev origin (frontend R1, G15)

The BackOffice SPA runs in development on Vite at the fixed origin **`http://localhost:5174`** (the POS uses
`http://localhost:5173`; it never talks to `backoffice-bff`). Vite serves the SPA and proxies `/oauth2`, `/login`, `/bff`
and `/api` to the BFF with the `Host` header kept (`changeOrigin: false`), so the browser sees one origin, as in
production (ADR 0041). The BFF builds its redirect URI from the request (`{baseUrl}/login/oauth2/code/keycloak`), which
is then `http://localhost:5174/login/oauth2/code/keycloak`. For that, run the BFF in development without TLS (no
`SERVER_SSL_*`): with TLS it would build an `https://localhost:5174` URI. Its `Secure` and `__Host-` cookies are
accepted on `http://localhost`, which Chromium and Firefox treat as a secure context (WebKit does not).

`backoffice-bff` in `realm-restaurantpro.json` therefore also has:

- the redirect URI `http://localhost:5174/login/oauth2/code/keycloak`;
- the web origin `http://localhost:5174`;
- the post-logout redirect URI `http://localhost:5174/` (Keycloak separates them with `##`).

The alternative, the BFF's own origin as the browser origin, would mean serving a built SPA from the BFF and losing
Vite's reload. Production is not affected: this file is imported only by the dev Compose Keycloak and by the test
containers (`SharedKeycloak`). Production defines no Keycloak and receives only issuer URLs. Where production Keycloak
runs is still open (O3). If O3 ever imports these files there, remove every `localhost` entry (the existing `8443`
ones too).

A dev Keycloak imports the realms only at its first start, so an existing dev volume keeps the old client. Recreate it
(this deletes the dev Keycloak's users only), from `backend/`:

```bash
docker compose rm -sf keycloak keycloak-db && docker volume rm backend_restaurantpro-dev-keycloak-db && ./run-local.sh
```

The volume name has the Compose project as its prefix (`backend` by default; see `docker volume ls`).

## The BFF's key in development and end-to-end stacks (frontend R1, ticket 05)

Nothing in the realm exports or in `keycloak-provisioners.sh` registers a key on `backoffice-bff`, so a locally run
BFF cannot exchange its authorization code. `deploy/keycloak-bff-dev-key.sh` fixes that for loopback stacks only:

```bash
deploy/keycloak-bff-dev-key.sh --url http://localhost:8180 --admin-password-file <file> \
  --key-file <dir outside the repository>/backoffice-bff.jwk [--origin http://localhost:5174]
```

It generates a P-256 private JWK (ES256, with a `kid`) in `--key-file` (0600; the BFF's
`RESTAURANTPRO_BFF_CLIENT_KEY_FILE`), keeps it on later runs unless `--rotate`, and registers only the public JWK on
the client. `--origin` also adds that BackOffice origin's redirect URI, web origin and post-logout URI to the client.
It has the same loopback guard as the e2e seed (Keycloak URL, announced issuer, origin). `deploy/e2e/stack.sh up` runs
it with the e2e origin `http://localhost:14174`. Production keys are installation secrets (deploy/README.md,
"Frontend origins").

## End-to-end tenant administrator with a known OTP secret (frontend R1, G16)

`deploy/keycloak-e2e-seed.sh` gives a development or end-to-end environment a tenant, its first store and a tenant
administrator who signs in with a password and a TOTP code that the tests can compute. It never touches the realm
exports. Run it after `backend/run-local.sh` is up (Keycloak, the provisioners, the backend):

```bash
export E2E_ADMIN_PASSWORD=<choose> E2E_ADMIN_TOTP_SECRET=<base32, e.g. 32 characters of A-Z2-7>
deploy/keycloak-e2e-seed.sh --url http://localhost:8180 --backend-url http://localhost:8080 \
  --admin-password-file <file holding RESTAURANTPRO_DEV_KEYCLOAK_ADMIN_PASSWORD>
```

`E2E_ADMIN_EMAIL` (default `e2e-admin@restaurantpro.test`) and `E2E_TENANT_CODE` (default `e2e`) are optional. The
script prints `{"tenantId", "tenantCode", "adminEmail", "adminUserId"}` on stdout.

What it does:

1. As Keycloak's master administrator, in `restaurantpro`, it creates or updates the user whose username and e-mail
   are `E2E_ADMIN_EMAIL`. The e-mail is verified and there is no required action. The password is
   `E2E_ADMIN_PASSWORD`, and there is exactly one OTP credential on `E2E_ADMIN_TOTP_SECRET`: TOTP, HmacSHA1, 6 digits,
   30 s, the realm's policy, imported with `secretEncoding: BASE32`. The secret is therefore the one an authenticator
   app or Playwright (for example `otpauth`) uses as is.
2. It onboards the tenant through the public platform API (`POST /api/v1/platform/tenants`, D181), and replays that
   command until the tenant is `ACTIVE`. The operator is the client `e2e-seed` of `restaurantpro-ops`, created by the
   script: a service account with `platform-operator` and the `restaurantpro-api` audience. It is enabled for the
   run only. Its secret is regenerated for each run and kept in memory only (never in a file). When the script ends,
   whatever happened, the secret is regenerated again and the client is disabled. A token issued during the run stays
   valid until it expires (5 min), because the backend checks tokens offline. Onboarding invites the
   administrator's e-mail, finds the user of step 1, and grants it the tenant's ACCOUNT membership. The invitation
   e-mail goes to Mailpit, so the realm needs SMTP (`run-local.sh` passes `--smtp-host mailpit:1025` to the
   provisioners).

The script is idempotent. A rerun sets the password again and replaces the OTP credential, so a new secret takes
effect. It also replays the same onboarding, which D181 makes idempotent by tenant code and request. A rerun with the
same code but another e-mail is refused (`TENANT_CODE_TAKEN`).

Guard: before it uses any credential, the script checks three URLs: the Keycloak URL, the issuer that Keycloak
announces for `restaurantpro`, and the backend URL. It refuses to run unless each one matches, as a whole,
`http(s)://` followed by `localhost`, `127.0.0.1` or `[::1]`, then an optional port and path. User info (`@`), a
backslash, a query, a fragment or another scheme is refused, so `http://localhost:80@evil/` cannot pass.

No secret is in the repository. The password, the TOTP secret, the tokens and the client secret are never printed,
written to a file or put on a command line:

- curl reads request bodies on stdin and the `Authorization` header from a process substitution;
- jq reads the password and the TOTP secret from its environment.

Keycloak refuses a TOTP code that has already been used (`otpPolicyCodeReusable` is false). A test that signs in twice
within one 30 s period must wait for the next period.

### Verification

`deploy/test-keycloak-e2e-seed.sh --guard-only` runs the guard cases without a stack. Without that option, the script
runs against the running dev stack. On each run it picks a new tenant code,
e-mail, password and secret, and then checks, in order:

1. The guard refuses a non-loopback Keycloak and a non-loopback backend.
2. The seed and a rerun with a new password and a new secret give the same tenant (`ACTIVE`) and the same user, with
   one OTP credential.
3. A `backoffice-bff` sign-in (PKCE, `acr_values=2`, password and TOTP) with redirect URI
   `http://localhost:5174/login/oauth2/code/keycloak` comes back there with a code (G15).
4. The same sign-in through a temporary public client gives an access token with `acr` `2` whose subject is the seeded
   user. A temporary client is used because exchanging `backoffice-bff`'s code needs the BFF's key. The script removes
   the client when it ends.

```bash
deploy/test-keycloak-e2e-seed.sh --url http://localhost:8180 --backend-url http://localhost:8080 \
  --admin-password-file <file holding RESTAURANTPRO_DEV_KEYCLOAK_ADMIN_PASSWORD>
```
