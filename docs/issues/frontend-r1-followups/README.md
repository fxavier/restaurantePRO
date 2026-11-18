# Frontend R1 follow-ups

Status: needs-triage

Recorded during the frontend R1 build (tickets 01–12, 2026-11-18). Each row is one follow-up; split a row into its own file when it is picked up. FC = [frontend-conventions.md](../../architecture/frontend-conventions.md).

| id | area | follow-up | severity | found in |
|---|---|---|---|---|
| FE-F01 | backend | `restaurantpro.pos.origin`: an uppercase host passes validation but fails at runtime (Keycloak "Invalid origin"); lowercase or reject it | Low | 02 review |
| FE-F02 | backend | `restaurantpro.pos.origin` accepts `http://` for non-localhost hosts | Low | 02 review |
| FE-F03 | backend | no startup warning when `restaurantpro.pos.origin` is unset (terminal provisioning then fails with 503) | Low | 02 review |
| FE-F05 | CI | pin GitHub Actions by commit SHA instead of major tag | Low | 04 review |
| FE-F06 | backend | `PosSessionSecurityDocumentation` sets an immutable security list; `CurrencyAsStringDocumentation` mutates global springdoc config in a bean constructor | Low | FE-F04 review |
| FE-F09 | backend | ~~listing stores needs `STORE_MANAGE`, so a user with only `TERMINAL_MANAGE` cannot pick a store~~ resolved: `GET /backoffice/stores` needs no permission and lists the stores where the account holds any (all of them for any installation-wide grant, none → empty list, "Não tem acesso a nenhuma loja"); a POS session is 403; create/edit still need installation-wide `STORE_MANAGE` | Medium | 06 |
| FE-F10 | BFF | logout by `fetch` skips Keycloak's front-channel redirect; the BFF should return the logout URL for the SPA to navigate to | Low | 06 |
| FE-F11 | contract | `Pageable` is typed as one object; Spring reads flat `page`/`size` | Low | 06 |
| FE-F12 | backend | 429 `ENROLMENT_THROTTLED` carries no `Retry-After` | Low | 08 |
| FE-F13 | test | first login of a user without OTP shows Keycloak's "Configure OTP" (both realms); ops-realm default ACR 2 untested | Low | FE-F07 review |
| FE-F14 | test | backend test that a wrong-PIN 401 carries no `WWW-Authenticate` (the POS relies on it to never repeat a PIN) | Low | 09 review |
| FE-F15 | backend | no POS endpoint gives the store's name or timezone; times use the terminal's zone, the status bar shows no store | Medium | 10, 11 |
| FE-F17 | backend | `TableMap` has no open-order id; the POS searches `GET /pos/orders?lifecycle=OPEN&size=200` | Medium | 11 |
| FE-F18 | contract | ~~`PosCatalogResponse.catalog` typed as a JsonNode~~ resolved: documented as `CatalogSnapshot`, POS uses generated types | Medium | 11 |
| FE-F19 | backend | terminal create / enrolment code / revoke take no `Idempotency-Key` (PRD AC2 "same key on resume" cannot apply) | Medium | 07 |
| FE-F20 | e2e | all e2e stacks share one compose project name and fixed ports: only one can run at a time per machine | Low | 07, 12 |
| FE-F21 | backend | terminal code unique constraint is case-sensitive (the form now upper-cases) | Low | 07 review |
| FE-F22 | BFF | writes are bound to the pinned tenant but not to the pinned user: a second user of the same tenant logging in from another tab can have tab 1's write accepted as theirs | Low | FE-F16 review |
| FE-F23 | BFF | `/api/v1/auth/tenants` answers are stamped `X-Relayed-Tenant` though no tenant was resolved for them | Low | FE-F16 review |
| FE-F24 | backend | the POS change stream should write an event on subscribe (headers otherwise wait for the 25 s heartbeat; the client allows 40 s meanwhile) | Medium | 12 |
| FE-F25 | backend | the idempotency "in progress" 409 has no `code` (the POS disambiguates by re-reading the order); the batch endpoint is documented 200 but answers 201; pos-logout documented 200 but answers 204 | Low | 09, 11, 12 |
| FE-F26 | contract | `DeviceEnrolmentResponse` fields all optional; taken-code 409 has no `code` | Low | 07, 08 |
| FE-F27 | backend | `OrderView` should carry a server-computed draft total so the POS echoes it as `expectedGross` instead of summing (FC §12 exception) | Low | 12 review |

Fixed during the run (not open): FE-F04 (#124), FE-F07 (#126), FE-F08 (#128), FE-F16 (#132).
