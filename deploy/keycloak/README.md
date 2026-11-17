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
terminal (`pos-*`) and bridge (`bridge-*`) clients are created at run time by the provisioner (S73).

The devices realm (S73/S75, O1 option 3) holds one client only, `device-provisioner`: confidential, `client-jwt`, a
service account and no browser, implicit or password flow. Its one `users` entry is that client's service-account user
(`serviceAccountClientId`, no credentials), which carries the `realm-management` roles `manage-clients` and
`view-clients` it needs to create and remove the `pos-*` and `bridge-*` clients. No person signs in to this realm. Its
key is registered by `deploy/keycloak-provisioners.sh`, as the `tenancy-provisioner` one is.
