# restaurantPRO print bridge

A small agent per store (R6, D187): the central backend cannot reach a restaurant's LAN, so the bridge, on an
always-on device of the store's network, claims the store's print jobs one at a time over outbound requests
(`GET /api/v1/bridge/jobs`, polled every 2 s when idle), sends each one's ESC/POS bytes (rendered by the backend for the printer's profile,
D154–D158) to the printer over TCP 9100, and reports what the send achieved
(`POST /api/v1/bridge/jobs/{id}/outcome`). No inbound port is opened on the LAN. It keeps no business data.

It is a confidential client of the `restaurantpro-devices` Keycloak realm (`bridge-<id>`, `private_key_jwt`), enrolled
like a POS terminal (D182): an administrator registers the bridge (`POST /api/v1/backoffice/terminals` with
`"kind": "PRINT_BRIDGE"`) and issues a one-time code; the bridge makes its own P-256 key pair and sends only the
public key with the code.

## Settings (environment)

| variable | value |
|---|---|
| `RESTAURANTPRO_BRIDGE_BACKEND_URL` | the API, e.g. `https://api.example.com` |
| `RESTAURANTPRO_BRIDGE_ISSUER` | the devices realm, e.g. `https://auth.example.com/realms/restaurantpro-devices` |
| `RESTAURANTPRO_BRIDGE_CLIENT_ID` | answered by the enrolment (`bridge-<id>`) |
| `RESTAURANTPRO_BRIDGE_KEY_FILE` | the private key's file (written by the enrolment, owner-only) |
| `RESTAURANTPRO_BRIDGE_POLL_INTERVAL` | optional, ISO-8601 (default `PT2S`): how often an idle bridge asks |

## Build, enrol, run

```bash
cd bridge && ./mvnw -q verify                    # tests and target/bridge-0.0.1-SNAPSHOT.jar
java -jar target/bridge-0.0.1-SNAPSHOT.jar enrol <code>
java -jar target/bridge-0.0.1-SNAPSHOT.jar       # or as a service: packaging/restaurantpro-bridge.service
```
