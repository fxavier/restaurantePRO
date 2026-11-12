# restaurantPRO backend — R5 decisions for approval (hardening: identity, store availability, alerts, printers)

Status: **APPROVED** 2026-11-12 by Xavier Francisco ("aprovado": every recommendation P52–P60 accepted; the R4 P49 completion items move to R6). This document continues [backend-decisions.md](backend-decisions.md) (D1–D33), [r2-decisions.md](r2-decisions.md) (D34–D63), [r3-decisions.md](r3-decisions.md) (D64–D98) and [r4-decisions.md](r4-decisions.md) (D99–D136, P40–P51). Those decisions stay in force unless a decision below amends one by id. Ids continue from **D137, P52 and A57**. Answer by id, for example "P57: priced = available" or "D142: drop PROMOTION_MANAGE from the list". Once approved, the decisions marked ★ become ADRs 0034–0038 (written now with status *Proposed*; the integration slice S63 flips them to *Accepted*).

Source of truth: [product_description.md](../product_description.md), cited as §n. Slices, contracts and the graph are in [PRD R5](../prd/r5-stage5.md). Glossary: [CONTEXT.md](../../CONTEXT.md). Coding rules: [backend-conventions.md](../architecture/backend-conventions.md). Baseline: `main` at 8f3725d (R4 merged, S54 integrated).

R5 is not a §13 stage. It is the hardening run the user scoped on 2026-11-12, with four binding user decisions: (1) no Keycloak as the core identity provider; native TOTP MFA and optional OIDC federation; (2) per-store catalog availability; (3) the §12 operational alerts; (4) a real ESC/POS driver.

---

## 0. Blockers and questions that need you

**Blockers to building R5: none.** The launch conditions stay as they were: **B4** (a certified fiscal adapter for the chosen market) and **B5** (tests with the real printer models, §12 "testes com impressoras reais"). R5 makes B5 executable: S63 writes the hardware checklist, but running it on real printers is a **human checkpoint**, not something a slice can close.

**No spec change is proposed.** R5 implements §10 ("MFA para acesso remoto e funções sensíveis, conforme implantação"), §5 ("disponibilidade" in the catalog), §12 (alerts) and §4/§8.6 (printers). Where the spec is silent, the simplest consistent rule is chosen and marked as an assumption.

**Earlier decisions this run amends** (each named again in its decision):
- **D26 / ADR 0008** ("MFA (TOTP) is deferred"): replaced by D139–D143 (ADR 0035).
- **D83 / P36** (support access assumed MFA stays deferred, support on a VPN): support accounts now need MFA whenever MFA is on (D145).
- **D31 / ADR 0010 and the R1 S4 rule** "every active item has a price": an item excluded from a store needs no price there (D146, ADR 0036).
- **D33** (Actuator admin-only): `/actuator/prometheus` also accepts a scrape token (D152, ADR 0037).
- **D57** (one renderer, 32-column layouts): printer profiles and styled tickets (D154–D157, ADR 0038).
- **R4 rule "no slice edits `pom.xml`"**: R5 adds one dependency, `spring-boot-starter-oauth2-client`, in the prefactor only (D144).
- **R4 P49** planned "a run R5 'completion'" for waiter floats (bolsas), pub cards, "takeaway seguro" and own consumption. The user scoped this R5 differently, so **those items move to R6** (confirmation below).

**Questions only you can answer.** Each has my recommendation; I build R5 with it unless you override it.

| id | question | recommendation (default) | what changes if you choose otherwise |
|---|---|---|---|
| P52 | MFA policy of a **new** installation. | Code default **`OFF`** (an upgraded installation behaves exactly as R4 until its operator opts in); `deploy/.env.example` sets **`SENSITIVE`** for new installations: a second factor for every remote BackOffice login and a step-up for sensitive functions everywhere (§10). | `ALL_BACKOFFICE` in `.env.example`: every BackOffice login asks for a code, also on the LAN. Code default `SENSITIVE`: an upgrade forces every administrator to enrol before the next sensitive action. |
| P53 | How long a step-up lasts. | **15 minutes** from the last verified code (`restaurantpro.security.mfa.step-up-max-age`). | 5 min: safer, more prompts during a publication session. 60 min: fewer prompts, a stolen unlocked browser keeps power longer. |
| P54 | Which functions are sensitive (need a fresh second factor). | The 15 permissions of **D142**: company, users, roles, employees, terminals, support access, fiscal, catalog publication, promotions, closing approval, cash adjustments, credit limits, payment methods, report and audit exports. | Add or remove permissions by name. A permission is the unit: every use case that checks it is covered. |
| P55 | Does an IdP's own MFA count as the second factor? | **No.** A federated login is a first factor; the local TOTP rules of D140 apply on top (`trust-idp-mfa = false`). | **Yes, when the ID token's `amr` claim shows MFA** (`mfa`, `otp`, `hwk`, `swk`): users of Entra or Keycloak with MFA are not asked twice, but the installation then trusts a claim it cannot verify. |
| P56 | May an OIDC-linked account still log in with its password? | **Yes.** The password is the offline fallback: a local server keeps working without Internet (§11), and a cloud IdP does not. | **IdP only for linked accounts:** one credential fewer, but no BackOffice login during an Internet outage. |
| P57 | Per-store catalog availability. | **Explicit exclusion record** (`StoreItemExclusion`): every active item is in every store unless excluded there; an excluded item needs no price in that store; an active, non-excluded item without a price still fails validation with `ITEM_WITHOUT_PRICE` (D146). | **"Priced = available":** no new table; an item without a price in a store is silently left out of that store's release and listed as a warning. A forgotten price then removes an item from the POS instead of blocking the publication. |
| P58 | Ship Prometheus in the Compose package? | **Yes, as an opt-in Compose profile `monitoring`** (Prometheus only, internal network, pinned image), with the rules file and the scrape configuration. **No Alertmanager:** the notification channel is installation-specific; the in-app alert list (D149) covers installations without one. | **Rules file and scrape example only:** the operator runs Prometheus elsewhere. **With Alertmanager:** one more service and a notification configuration per company. |
| P59 | When does the cash drawer open? | **Automatically on a cash payment and on a cash refund** recorded on a terminal, plus a **manual, audited "open drawer"** command for cash movements (entries, withdrawals, float) (D158). | **Payments only:** refunds and movements use the manual command. **Also on every cash movement automatically:** one more event from cash. |
| P60 | Default code page offered for a new printer. | **CP850** (ESC/POS table 2): it has every Portuguese letter (ã, õ, ç, Ã, Õ…), is in the base table set of Epson and of nearly every compatible printer. CP860 stays available. **No UTF-8** (D156). | **CP860** (table 3, the Portuguese table): same letters, a little less common on low-cost clones. **CP858** (CP850 with €): only when a price must print the euro sign; amounts print the ISO code today. |

**Confirmations (no choice expected):**
- **P49 items move to R6.** Waiter floats and envelopes (bolsas, `CashShift`), pub cards, "takeaway seguro" and own consumption are not in this R5.
- **B4 and B5 stay launch conditions.** R5 adds no fiscal behaviour. The printer checklist is the B5 procedure; a person runs it on the purchased models.

---

## 1. Scope

| area | IN R5 | deferred / out |
|---|---|---|
| Identity (§10, §5 "Organização e acessos") | Native TOTP second factor for BackOffice accounts; per-installation MFA policy; remote-access rule; step-up for sensitive permissions; recovery codes; enrolment codes; audited admin reset; break-glass reset from the server console; optional OIDC federation (one provider per installation) with pre-provisioned, linked accounts | Keycloak as core IdP (D138); WebAuthn/passkeys; SMS or e-mail codes; more than one OIDC provider; role mapping from IdP claims; RP-initiated logout at the IdP |
| Catalog (§5 "disponibilidade") | Per-store exclusion of an item; release validation lists excluded items and warnings; reference rules for menus, macros, quick pages, automatic items, daily dishes, fee items | Variant-level exclusion; per-store scheduling ("only at lunch") |
| Operations (§12) | Seven alerts: late backup, disk space, print queue, failed events, payments of unknown outcome, cash discrepancy, export failure. Metrics, an in-app alert list, a backup status file, a Prometheus rules file, scrape authentication, an opt-in Prometheus profile | Alertmanager and notification channels; dashboards; log shipping |
| Printing (§4, §8.6, D57) | Printer profiles (width, code table, cut, feed, status probe, drawer); styled production tickets; word wrap per printer; pre-send status probe; cash-drawer kick on cash payments and refunds; manual drawer open | Printer-specific commands outside the Epson-compatible common subset; logos and bitmaps; UTF-8 printing; kitchen display |

**Module placement.** No new module (§6 lists them; D1 creates a module only when its stage needs it). MFA and OIDC live in `identity`, the step-up rule in `access`, the alert contract in `shared.ops`, the aggregation in `config`, printer profiles and the drawer kick in `production`, the kick request in `settlement`.

---

## 2. Modules and dependencies

**D137 — No new module edge.** Every D99 rule stays. R5 adds types, not edges:

```
access     → shared                                   (unchanged; gains AuthenticationAssurance, StepUpRequiredException)
identity   → shared, audit, access, organization(+dto,+event)   (unchanged; owns MFA and OIDC)
production → … D35 …, settlement :: event             (unchanged edge; consumes the new CashDrawerKickRequested)
config     → shared, access, audit                    (unchanged; aggregates shared.ops.AlertSource beans)
shared.ops                                            NEW package in shared (OPEN): AlertSource, OperationalAlert, ExportMonitor
```

| rule | why | rejected alternative |
|---|---|---|
| The step-up rule is in `access.AccessPolicy`; `identity` only reports the session's assurance (`access.AuthenticationAssurance`, implemented by identity, looked up through `ObjectProvider` as `StoreActivity` is). | Authorization stays in one place (D27). Every use case that already calls `require(SENSITIVE_PERMISSION, …)` is covered without editing 9 modules. | A `requireSensitive(...)` call in each sensitive use case: about 40 call sites in 9 modules, easy to forget on the next endpoint. |
| Alert sources implement `shared.ops.AlertSource`; `config` collects them as `List<AlertSource>`. | `shared` is open to every module and depends on none, so each module contributes its own alert without any new module edge, and `config` (on which nothing depends) aggregates. | Actuator health groups by name: a group naming a contributor of an unmerged slice fails startup (`validate-group-membership`), and health is the wrong shape for a list with counts and stores. |
| Only `identity` and `config` may import `org.springframework.security.oauth2..`. | Federation is an identity concern; config wires the filter chain. | — |

Enforcement: `ModularityTests` (`verify()`) unchanged lists; `ArchitectureTests` gains `onlyIdentityAndConfigUseOAuth2` and `sharedOpsDependsOnNoModule`, each with an `ArchitectureRulesFireTest` fixture.

---

## 3. Identity: MFA and federation (§3, §5, §10)

**D138 ★ — Keycloak is not adopted as the core identity provider (ADR 0034).** Reasons:
1. **One installation per company (§2).** Keycloak would be one more stateful service, database schema, upgrade path, backup and restore drill **per company**, on hardware sized for a restaurant server.
2. **Offline operation (§11).** The POS must keep working with no Internet and must refuse cleanly with no server. A second server process on the critical login path adds a failure mode and buys nothing for a LAN.
3. **The POS flow does not fit an IdP.** PIN on an enrolled terminal, store-scoped authority, approver code + PIN in headers (D28, D40), lockout and manager unlock: none of these is an OIDC flow, so they would stay local anyway.
4. **Authority is local and store-scoped (D27, D42).** Roles × stores live in `identity`; an IdP's groups cannot express "Gerente in store A, Caixa in store B" without a mapping layer that becomes a second authority.
5. **Existing, tested code.** Sessions (D25), throttling (S10), support grants (D83), session revocation and audit are built and tested.
The **integration path** is D144: an installation that already has an IdP links BackOffice accounts to it for authentication only.

**D139 ★ — A native TOTP second factor (RFC 6238) for BackOffice accounts (ADR 0035).**
- HMAC-SHA1, 6 digits, 30-second step, accepted window ±1 step (clock drift up to 30 s). Implemented with `javax.crypto.Mac`; **no new dependency** (a TOTP library is ~50 lines over the JDK and RFC 6238 Appendix B gives the test vectors). The secret is 160 random bits from `SecureRandom`, shown base32 (RFC 4648, our own small codec) inside an `otpauth://totp/<issuer>:<username>?secret=…&issuer=…&digits=6&period=30` URI. The BackOffice draws the QR code; the backend sends no image (no QR dependency).
- **Secret at rest:** AES-256-GCM with a 96-bit random nonce per secret and the key from a secret file (`RESTAURANTPRO_MFA_KEY_FILE`, 32 random bytes, base64), with a `key_id` column for a later rotation. A database dump alone then cannot generate codes. The installation does not start when MFA is not `OFF` and the key file is missing or malformed (fail fast, like D98). The key travels in the encrypted backup's `secrets/` (D98).
- **Replay:** the factor stores `last_used_step`; a code is accepted only for a step greater than it, with a compare-and-set update, so a code used once (or a code of an earlier step) is refused, also by a second session at the same time.
- **Guessing:** 5 consecutive wrong codes lock that account's second-factor checks for 15 minutes (`failed_attempts`, `locked_until` on the factor; a correct code resets). A pending login session is also ended after 5 failures. The lock is audited once (`MFA_LOCKED`), like the S10 login throttle.
- **Recovery codes:** 10 per enrolment, 10 characters of base32 (50 bits) each, shown **once**; stored as SHA-256 hashes (high entropy, so an adaptive hash adds nothing, as for terminal tokens, ADR 0008). Each works once (compare-and-set on `used_at`). Regenerating replaces all of them and needs a fresh step-up.

**D140 — MFA policy per installation and the "remote access" rule.**
- `restaurantpro.security.mfa.mode ∈ {OFF, SENSITIVE, ALL_BACKOFFICE}` (P52). It is a deployment property, not data: §10 says "conforme implantação", the operator sets it in `.env`, and an attacker with an administrator session cannot switch it off through the API.

| mode | login with a password (or OIDC) | sensitive permission (D142) |
|---|---|---|
| `OFF` | no second factor (R4 behaviour) | no step-up |
| `SENSITIVE` | second factor **when the request is remote**, or the account is a support account (D145) | fresh second factor (P53) **everywhere** |
| `ALL_BACKOFFICE` | second factor for every BackOffice login | fresh second factor everywhere |

- **Remote** means: the client address of the request (after the reverse proxy's `X-Forwarded-For`, which Spring trusts only from the private-network proxy, D32) is **not** inside `restaurantpro.security.lan-cidrs`. Default: `127.0.0.0/8, ::1/128, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fc00::/7, fe80::/10`. The installer narrows it to the store LANs and **must leave VPN address pools out**, otherwise a VPN user counts as local (README). Caddy replaces any client-sent `X-Forwarded-For` (its default with no `trusted_proxies`), so a client cannot claim a LAN address.
- **Per request, not only at login:** a session that has never proved a second factor and receives a remote request is refused with 403 `MFA_REQUIRED` (step-up satisfies it). A cookie taken from the LAN is then useless from outside.
- POS sessions (`ActorKind.EMPLOYEE`) are never affected: the PIN flow stays local and offline (user decision 1).

**D141 — Login with a second factor: a pending session.**
- `POST /auth/login` (and the OIDC callback, D144) checks the password as today. When D140 asks for a second factor, the session starts in state **`MFA_PENDING`**: it holds **no authority**, `MustChangeCredentialFilter` (extended with the two MFA states) serves only `/api/v1/auth/**`, and the pending state expires after 5 minutes. `POST /auth/mfa/verify {code | recoveryCode}` restarts the session (new id, as `LoginSessions.start`) with the account's authorities and `secondFactorAt = now`.
- An account that needs a second factor but has none gets state **`MFA_ENROLMENT_REQUIRED`** (same restrictions) when the request is from the LAN, so it can enrol right away; from a remote address it gets **403 `MFA_NOT_ENROLLED`** unless it presents an enrolment code (D143). A stolen password then cannot enrol the attacker's phone from outside.
- `SessionView` gains `mfa {state: NONE | PENDING | ENROLMENT_REQUIRED | VERIFIED, stepUpValidUntil}`. A must-change-password session (D30) changes its password first, then does the second factor.
- Throttling: the S10 login throttle still counts password attempts; second-factor attempts are counted on the factor (D139).

**D142 ★ — Step-up: a fresh second factor before a sensitive permission (ADR 0035).**
- `access.Permission` gets `boolean sensitive()`, backed by one `EnumSet` in the enum, code-defined like the permissions themselves (D27). The set, derived from the spec (P54):

| permission | spec basis |
|---|---|
| `COMPANY_MANAGE` | §2 single fiscal profile; §14 "não altera fiscal" |
| `USER_MANAGE`, `ROLE_MANAGE` | §5 accounts, roles and permissions; privilege escalation |
| `EMPLOYEE_MANAGE` | §5 PIN, blocking, store assignments (grants POS power) |
| `TERMINAL_MANAGE` | enrolment codes are device credentials (D26) |
| `SUPPORT_ACCESS_MANAGE` | §5 "acesso de suporte… autorizado" |
| `FISCAL_MANAGE` | §14 "não altera fiscal" |
| `CATALOG_PUBLISH`, `PROMOTION_MANAGE` | §14 "não altera… preços" |
| `CASH_CLOSING_APPROVE`, `CASH_ADJUST` | §14 "não altera… fechos"; money corrections |
| `CREDIT_LIMIT_MANAGE` | §10 "autorização adicional para… crédito" |
| `PAYMENT_METHOD_MANAGE` | where money is routed (provider configuration) |
| `REPORT_EXPORT`, `AUDIT_EXPORT` | §10 "autorização adicional para… exportações" |

- `AccessPolicy.require(p)`, `require(p, store)`, `requireAny(store, ps…)` and `scopeOf(p)`: when the actor is an `ACCOUNT`, the permission that grants access is sensitive (for `requireAny`: **every** granting permission is sensitive; a reader holding the non-sensitive `CASH_VIEW` lists closings without a prompt), and `AuthenticationAssurance.current()` says step-up is enforced, then `secondFactorAt` must be within `step-up-max-age`. Otherwise **403 `MFA_STEP_UP_REQUIRED`** (or **403 `MFA_ENROLMENT_REQUIRED`** when the account has no factor). The permission check runs first: a user without the permission still gets the plain 403.
- `POST /auth/mfa/step-up {code | recoveryCode}` sets `secondFactorAt = now` in the session. The client then repeats the refused request **with the same `Idempotency-Key`**: a 403 is never stored (D47), so the retry runs once.
- POS approvals (approver code + PIN, D28/D40) are unchanged and never ask for a second factor. A sensitive permission used from a POS session (`EMPLOYEE`) is not affected.
- `AuthenticationAssurance` absent (a module test without identity): step-up is not enforced, so R4 module tests stay green unchanged.

**D143 — Enrolment, recovery, reset and break-glass.**
- **Enrolment** (self): `POST /auth/mfa/enrolment {currentPassword, enrolmentCode?}` creates a PENDING factor and returns the secret and `otpauth` URI (valid 10 minutes); `POST /auth/mfa/enrolment/confirm {code}` activates it, returns the 10 recovery codes once, and restarts the session as VERIFIED. One PENDING and one ACTIVE factor per account (partial unique indexes). From a remote address enrolment needs an **enrolment code**.
- **Enrolment code:** one-time, 24 hours, 12 base32 characters, SHA-256 hashed, issued by an administrator's reset (below) and handed over out of band, like a terminal enrolment code (D26).
- **Admin reset:** `POST /backoffice/users/{id}/mfa/reset {reason}` (`USER_MANAGE`, so itself a step-up action): revokes the factor and its recovery codes, ends the account's sessions (the existing per-user revoke), issues an enrolment code, audits `MFA_RESET` with the reason. Not on one's own account (422 `MFA_SELF_RESET`: use a recovery code).
- **Secrets never pass through the idempotency store:** responses that carry a TOTP secret, recovery codes or an enrolment code take **no `Idempotency-Key`** (D24 stores response bodies). Repeating them is harmless: a second reset issues a new code and voids the first; a second enrolment replaces the PENDING factor.
- **Break-glass** (the last administrator lost the phone and the codes): `deploy/mfa-break-glass.sh <username> <reason>` runs, through `docker exec … psql` and in one transaction, `identity.mfa_break_glass_reset(username, reason)` (V3013: revokes the account's ACTIVE factor) and `audit.record_console_event(…)` (V1105: an audit row with `actor_id NULL`, the system, action `MFA_BREAK_GLASS_RESET`). Each schema is written only by its own module's function (D6). It needs a shell on the server and Docker, which is the infrastructure administrator's separate risk (§10, last paragraph).
- Audited actions: `MFA_ENROLMENT_STARTED`, `MFA_ENROLLED`, `MFA_VERIFIED` (login and step-up, with `kind`), `MFA_RECOVERY_CODE_USED`, `MFA_RECOVERY_CODES_REGENERATED`, `MFA_LOCKED`, `MFA_RESET`, `MFA_BREAK_GLASS_RESET`. Never a secret, a code or a hash in `details` (the recursive secret guard of D82 applies).

**D144 ★ — Optional OIDC federation (ADR 0034).**
- **New dependency:** `spring-boot-starter-oauth2-client`, because it implements the authorization-code flow with PKCE, `state` and `nonce` checks and ID-token signature validation, which must not be hand-written.
- **Off by default** (`restaurantpro.security.oidc.enabled=false`): no filter, the routes answer 404, no outbound call. **One provider per installation** (Keycloak, Entra ID, Google… any OpenID Provider): `issuer-uri`, `client-id`, `client-secret-file`, `display-name`, `scopes` (default `openid`).
- **No discovery at startup.** Spring Boot's issuer-based registration fetches the discovery document at boot, so an installation without Internet would not start (§11). R5 uses its own `ClientRegistrationRepository` that resolves the discovery document on first use, caches it, and answers 503 `OIDC_UNAVAILABLE` for the OIDC routes only while the IdP is unreachable. Password login never depends on it.
- **Authentication only.** The callback maps `(iss, sub)` of the validated ID token to an `ExternalIdentity` row → its `UserAccount`. **No account is ever created**: no link → 401, audited `OIDC_LOGIN_REFUSED` (with the issuer, never the token). An inactive account, or a support account, is refused like a wrong password. The session is the same server-side session as a password login (D25), so CSRF, revocation and audit are unchanged; MFA follows D140 (P55). Access, refresh and ID tokens are discarded after the login.
- **No role mapping.** Groups, roles and any other claim are ignored: local RBAC per store is the authority (D27). Justification for not mapping: a mapping would make the IdP a second authority, cannot express per-store scope, and changes to IdP groups would bypass the audited role administration.
- **Linking** (pre-provisioned accounts only): an account logged in locally (with a fresh second factor when MFA is not `OFF`) calls `POST /auth/oidc/link`, is sent to the IdP, and the callback links that `(iss, sub)` to the **session's** account. Self-linking is needed because subjects are opaque (Entra issues a pairwise `sub` per application, which an administrator cannot type). An administrator can list and remove links (`USER_MANAGE`). `UNIQUE (issuer, subject)` and one link per account.
- Audited: `OIDC_LOGIN`, `OIDC_LOGIN_REFUSED`, `OIDC_LINKED`, `OIDC_UNLINKED`.

**D145 — Support accounts and MFA** (amends D83, P36). P36 kept MFA deferred on the assumption that support connects over a VPN. With MFA available, a **support account always needs a second factor at login when the mode is not `OFF`**, wherever it connects from, because support access is remote by nature. The first enrolment of a support account uses an enrolment code issued by an administrator's reset. Support accounts cannot link an external identity.

---

## 4. Catalog: per-store availability (§5 "disponibilidade", §8.3)

**D146 ★ — An explicit per-store exclusion (ADR 0036, P57).**
- New working-copy entity **`StoreItemExclusion`** ("artigo não disponível na loja"; spec term "disponibilidade"): `(store_id, item_id)` plus who and when, and an optional reason. **Default: every active item is in every store** (today's behaviour). An excluded item is left out of that store's next release and **needs no price** in that store's price list.
- Three availabilities, kept separate (§8.3): `Item.availability` ACTIVE/INACTIVE (company-wide, the item's life), `StoreItemExclusion` (configuration, takes effect at the store's next publication), and the operational **sold-out flag** (D118, immediate, by service staff).
- **"Forgot to price" stays an error:** an active, non-excluded item without a price still fails validation with `ITEM_WITHOUT_PRICE`, now with the hint "price it, or exclude it from this store". The validation response also **lists every excluded item** (`excludedItems`), so a publisher sees what the store will not sell.

| option | for | against |
|---|---|---|
| **Exclusion record (recommended)** | Unchanged default and data: no row = R4 behaviour. A missing price is still caught. Excluding keeps the price for a later return. | One table and three endpoints. |
| Priced = available | No new table. | A forgotten price silently removes an item (only a warning). Unlisting means deleting a price. The R1 check stops protecting anything. |
| Opt-in listing per store | Explicit for both directions. | Every existing item × store needs a row: a data migration, and every new item must be listed per store or it never sells. |

**D147 — Reference rules under exclusion.** The working copy view (`WorkingCopy.of(storeId, at)`) drops excluded items from `activeItems` before validation. Validation gains **warnings** next to errors; warnings never block.

| reference | rule | code |
|---|---|---|
| MENU option excluded in the store (menus are company-wide items) | The option is dropped from that store's snapshot; if a group can then no longer meet its minimum, it is an error | warning `MENU_OPTION_EXCLUDED`; error `MENU_GROUP_BOUNDS_INVALID` (existing) |
| Macro (company-wide) with an excluded entry | The whole macro is omitted from that store's snapshot (a partial macro would change its meaning) | warning `MACRO_OMITTED` |
| Quick page of **all stores** with an excluded entry, or with an omitted macro | The entry is omitted | warning `QUICK_PAGE_ENTRY_OMITTED` |
| Quick page **of this store** naming an excluded item | Error (the store's own configuration contradicts itself) | `QUICK_PAGE_ENTRY_EXCLUDED` |
| Automatic item of **all stores** naming an excluded item | Omitted from that store's snapshot | warning `AUTOMATIC_ITEM_OMITTED` |
| Automatic item **of this store** naming an excluded item | Error | `AUTOMATIC_ITEM_EXCLUDED` |
| Daily dish (D109) | Already validated against the published snapshot at `PUT` (422 `ITEM_NOT_PUBLISHED`). `GET /pos/catalog/daily-dishes` and `CatalogApi.dailyDishes` now also drop ids absent from the current snapshot (a dish set before a republish that excluded it). | — |
| Delivery zone fee item (D128) | **No change.** A zone may be set up before the catalog is published, so the check stays at delivery opening: 422 `FEE_ITEM_NOT_PUBLISHED` covers an excluded fee item. | — |
| Promotions scope (S39) | No change: validated against the store's published catalog; an excluded item simply never matches. | — |
| Recipes (inventory) | No change: company-wide; an excluded item is never sold in that store. | — |
| Inactive item referenced anywhere | Unchanged (existing errors: `…_NOT_IN_RELEASE`). | — |

Warnings are stored on the release (`configuration_release.validation_warnings jsonb`) so the publish screen shows them too. Exclusion changes between validate and publish behave like any other working-copy edit between them (D31 as built).

**D148 — Migration and back-compat.** `V5006` creates the table only; no data migration. Existing releases are frozen snapshots and never change. `CatalogSnapshot.SCHEMA_VERSION` stays **2**: an excluded item is simply absent, so POS, orders and promotions parse every snapshot as before. With no exclusion rows, a new release's snapshot is identical, field for field, to what R4 would freeze (test `NoExclusionSnapshotUnchangedTest`). Excluding an item that has open drafts behaves as any republish: confirmation of a stale draft gets the existing catalog-version conflict.

---

## 5. Operational alerts (§12)

**D149 ★ — One alert contract, two outlets (ADR 0037).**
- `shared.ops.AlertSource { List<OperationalAlert> alerts(); }`, `OperationalAlert(String code, AlertSeverity severity, UUID storeId /* null = installation */, long count, Instant since, String summary)`, `AlertSeverity {WARNING, CRITICAL}`. Each module implements its source next to the data; sources read only their own module's tables or existing in-memory gauges.
- **Prometheus** (when deployed) scrapes the metrics of D150 and evaluates `deploy/monitoring/alert-rules.yml` (D153).
- **In-app**, for installations without Prometheus: `GET /api/v1/backoffice/alerts` in `config` aggregates every `AlertSource` (cached 30 s, so polling never loads the database), filtered by `AccessPolicy.scopeOf(SYSTEM_MONITOR)`: installation-wide alerts (backup, disk, events, exports) only for an installation-wide holder; store alerts for holders in that store. A health component `operationalAlerts` reports the custom status `ALERT` (outside the aggregation order, like `NON_CERTIFIED` of D54), so **an alert never marks the container unhealthy** (the Compose healthcheck greps `"status":"UP"`).
- Labels: only `store`, `status`, `state`, `module`, `kind`, `outcome`, `name`. **Never an order, payment, job or document id** (§12); `MetricLabelsTest` scans the registry.

**D150 — The seven alerts and their signals.** "New" marks what R5 adds.

| §12 alert | code | signal (module) | Prometheus metric | in-app condition | severity |
|---|---|---|---|---|---|
| backup em atraso | `BACKUP_MONITOR_STALE` | backup status file (config, D151) | **new** `backup_status_age_seconds` | file configured but missing, unreadable, or `checkedAt` older than 45 min | CRITICAL |
| | `BACKUP_LATE` | same | **new** `backup_last_success_age_seconds{kind=logical\|base\|offbox}` | logical or base backup older than 26 h, or off-box copy older than 30 min | CRITICAL |
| | `BACKUP_PROBLEM` | same | **new** `backup_problems` | the last check listed problems | CRITICAL |
| espaço em disco | `DISK_SPACE_LOW` | status file `filesystems` + the JVM's own data path (config) | **new** `filesystem_free_ratio{name=backups\|docker\|app}`; Spring's `disk_free_bytes` stays | free < 15 % WARNING, < 5 % CRITICAL | as stated |
| fila de impressão | `PRINT_JOBS_ATTENTION` | production | **new** `print_jobs_unresolved{store}` | FAILED or UNKNOWN jobs with no resolution, created in the last 24 h (drawer kicks excluded, D158) | WARNING |
| | `PRINT_QUEUE_STALLED` | production | **new** `print_jobs_oldest_waiting_seconds{store}` | a QUEUED or RETRY_WAIT job older than 5 min on a NORMAL printer | WARNING |
| eventos falhados | `EVENTS_FAILED` | shared.events | `event_publications{state="failed",module}` (exists) | > 0 | CRITICAL |
| | `EVENTS_BACKLOG` | shared.events | **new** `event_publications_oldest_incomplete_seconds` | oldest incomplete publication older than 30 min | WARNING |
| pagamentos desconhecidos | `PAYMENTS_IN_DOUBT` | settlement | `payment_attempts_in_doubt_overdue{store}` (exists, D81) | > 0 | CRITICAL |
| divergência de caixa | `CASH_DISCREPANCY` | cash | **new** `cash_closings_with_difference{store}` (closings submitted or approved in the last 24 h whose Σ\|difference\| exceeds `restaurantpro.alerts.cash-difference-threshold`, default 0); `cash_closing_difference_total` stays | > 0 | WARNING |
| falha de exportação | `EXPORT_FAILED` | shared.ops `ExportMonitor`, called by reporting, audit and inventory CSV exports (and by a future fiscal export adapter) | **new** `exports_total{kind,outcome=success\|failure}` | a failure in the last 24 h (in memory: a restart forgets it; the counter's history is in Prometheus) | WARNING |

Out of scope: an alert per printer being off (a printer off produces RETRY_WAIT then FAILED jobs, which the print-queue alerts catch).

**D151 — How the backup age reaches the application: a status file.**
- Backups run outside the JVM (`deploy/*.sh`, D98), and the backend container has no access to the backup directory by design. **`deploy/verify-backups.sh`** (already every 15 min, already computing ages and problems) writes, atomically (temporary file + `mv`), `deploy/status/backup-status.json`, mode 0644 in a 0755 directory that holds no secret. Compose mounts `./status` read-only into the backend at `/run/restaurantpro/status`. The backend reads it at most once a minute (`restaurantpro.alerts.backup-status-file`; unset = no backup alerts, e.g. developer stacks).
- Contract (frozen, schema version 1; ISO-8601 UTC instants; no path, host name, key or recipient in it):

```json
{ "schemaVersion": 1, "checkedAt": "…Z", "ok": true,
  "lastLogicalBackupAt": "…Z", "lastBaseBackupAt": "…Z", "lastOffboxSyncAt": "…Z",
  "problems": ["short sentence without secrets"],
  "filesystems": [ { "name": "backups", "totalBytes": 0, "freeBytes": 0 },
                   { "name": "docker",  "totalBytes": 0, "freeBytes": 0 } ] }
```
  `docker` is the filesystem of Docker's root directory (`docker info -f '{{.DockerRootDir}}'`), which holds the `db-data` and `pg-wal-archive` volumes. A missing instant is `null`.
- **Dead man's switch:** if the cron or timer stops, `checkedAt` ages and `BACKUP_MONITOR_STALE` fires.

| option | for | against |
|---|---|---|
| **Status file (recommended)** | No database write from a host script; no schema coupling; a restored database never carries a stale heartbeat; the script already knows every value. | One read-only bind mount. |
| Heartbeat table written by the script through `psql` | No mount. | A host script writes a migration-owned table; a restore brings back old heartbeats; the application user's privileges must allow it. |
| Prometheus textfile collector (node_exporter) | Standard. | Needs node_exporter, and installations without Prometheus would have no backup alert at all. |

**D152 — Metrics scrape authentication** (amends D33). `/actuator/prometheus` on the management port also accepts `Authorization: Bearer <token>` when `RESTAURANTPRO_METRICS_TOKEN_FILE` is set (a Compose secret, 32 random bytes, hex). The token is compared as SHA-256 digests in constant time, is valid **only** for that endpoint, and grants nothing else. Every other Actuator endpoint keeps `SYSTEM_MONITOR` (D33), `health` stays open. The management port stays off the LAN (Caddy proxies only the application port). Without the file, scraping needs a session as today.

**D153 — Prometheus rules and the opt-in profile (P58).** `deploy/monitoring/alert-rules.yml` holds one rule per row of D150 with the same thresholds, plus `RestaurantProBackendDown` (`up == 0` for 2 min). `deploy/monitoring/prometheus.yml` scrapes `backend:8081/actuator/prometheus` with `authorization.credentials_file`. The Compose profile `monitoring` adds a `prometheus` service (pinned image, internal network only, its own volume, no published port; reach it through an SSH tunnel). New "dependency" justification: a pinned upstream image, opt-in, because evaluating §12 alerts needs a rule engine. Alertmanager is the operator's choice; the README shows where to point `alerting.alertmanagers`.

---

## 6. Printing: a real ESC/POS driver (§4, §8.6, D57)

**D154 ★ — A printer profile per printer (ADR 0038).** Printer models are unknown (§15), so R5 targets the **ESC/POS common subset of Epson TM-compatible printers** and stores the variable parts on each printer (production schema, V7008):

| field | values (default for existing rows) | ESC/POS |
|---|---|---|
| `paper_width_mm` | 58, 80 (80) | — |
| `columns` | 58 mm: 24–35; 80 mm: 36–64 (48) | layout width in Font A characters |
| `code_page` | existing CP437, CP850, CP860, WPC1252, CP858 (unchanged) | `ESC t n` |
| `code_table` | NULL = the Epson number of `code_page`; else 0–255 for a vendor whose table numbers differ | `ESC t n` |
| `cut_mode` | `PARTIAL`, `FULL`, `NONE` (PARTIAL) | `GS V 66 0` / `GS V 65 0` / none |
| `feed_lines` | 0–10 (4) | `ESC d n` before the cut |
| `status_probe` | true/false (false) | `DLE EOT 2`, `DLE EOT 4` (D157) |
| `drawer_pin` | NULL (no drawer), 0 (pin 2), 1 (pin 5) (NULL) | `ESC p m t1 t2` |
| `drawer_pulse_ms` | 2–510, even (100) | t1 = ms ÷ 2; t2 = 250 (500 ms off) |

Presets returned by `GET /backoffice/printer-profiles`: `EPSON_TM_80` (80/48, PARTIAL, probe on), `GENERIC_80_42` (80/42, PARTIAL, probe off), `GENERIC_58` (58/32, NONE, probe off). A preset only fills the form; the stored fields are authoritative, so changing a preset never changes a configured printer. With the defaults, an R4 ticket whose lines fit in 48 columns renders **byte-identical** to R4 (`R4TicketBytesUnchangedTest`).

**D155 — Styled production tickets and per-printer wrap.**
- `production_ticket` gains `content_format ∈ {PLAIN, STYLED_V1}` (DDL default `PLAIN`; tickets are append-only, so no row is updated). R4 tickets and fiscal documents (fiscal sends `List<String>`, `DocumentPrintout`) stay `PLAIN`; production's own layouts (round, void, subtotal, delivery slip, receipt slip, test) become `STYLED_V1`: JSON lines `{"t": text, "s": NORMAL|BOLD|DOUBLE|TALL, "a": LEFT|CENTER|RIGHT}`, plus `{"rule": true}` and `{"l": left, "r": right}` (amount aligned right).
- The renderer lays a ticket out **at send time for the printer that prints it** (a redirected job uses the target's profile): `ESC @`, `ESC t n`, then per line `ESC a n`, `ESC E n` (bold), `GS ! 0x11` (double width and height) or `GS ! 0x01` (double height), text, `LF`, reset; a rule is `columns` dashes; a line longer than its width (`columns`, or `columns ÷ 2` in DOUBLE) wraps at the last space, continuation indented to the text after "qty x " (or cut hard when one word is longer); then the QR (unchanged, D57), `ESC d feed_lines`, and the cut of `cut_mode`. Style and alignment commands are emitted **only when they change** from the current state (NORMAL, LEFT after `ESC @`), so a PLAIN ticket produces no style bytes at all.
- Kitchen tickets: destination title DOUBLE + CENTER, table/order header BOLD, item lines TALL (readable at a distance), notes and components NORMAL. The copy mark (§4) stays BOLD + CENTER.
- Fiscal documents keep their adapter's layout (the market's certified adapter owns its printout, B4); production only wraps them to the width.

**D156 — Text encoding (P60).** Single-byte code pages only, per printer, through the existing `CodePage.encode` with the ASCII fallbacks of BUG-0001. **UTF-8 is not used:** `FS ( C` UTF-8 mode exists only on recent Epson models and is not in the common subset; a clone receiving UTF-8 bytes prints two wrong characters per accent. The test page prints a ruler of `columns` digits, `Acentos: ÁÉÍÓÚ ÂÊÔ ÃÕ Ç àç ãõ`, a BOLD, a DOUBLE and a TALL line, so the installer sees at once whether the table and width are right (the B5 checklist uses it).

**D157 — Status probe and acknowledgement.**
- With `status_probe = true`, the transport sends `DLE EOT 2` (offline cause) and `DLE EOT 4` (paper sensor) on the open connection **before** the payload and reads one byte each, 500 ms per read. A valid answer has `(b & 0x93) == 0x12`. Cover open (n=2 bit 2), paper-end stop (n=2 bit 5), error (n=2 bit 6) or paper end (n=4 bits 5–6) → **nothing is sent**; outcome `NOT_READY` with `last_error ∈ {COVER_OPEN, PAPER_OUT, PRINTER_ERROR}`, handled like `NOT_CONNECTED` (RETRY_WAIT, then FAILED after 5 attempts, D50 §7.2): safe, nothing printed. Paper near end (n=4 bits 2–3) → sent, and the printer's `last_status = PAPER_NEAR_END` is shown on the printer list. No answer within 500 ms → sent (a printer that does not implement `DLE EOT` must still print).
- **No post-send acknowledgement.** `DLE EOT` is answered in real time, before the buffered job is printed, and Epson's ASB and `GS ( H` are not in the common subset. `ACCEPTED` keeps meaning "every byte written and the connection closed" (D57); a failure after connecting stays `UNKNOWN`. §4 already states that the dedupe key does not guarantee one physical print.
- Lease: connect (2 s) + probe (≤ 1 s) + write (5 s) stays under the 60 s job lease; the transport constructor's check includes the probe.

**D158 — Cash-drawer kick (§4 "Caixa… numerário", P59).**
- Settlement publishes **`CashDrawerKickRequested(String sourceType /* PAYMENT | REFUND */, UUID sourceId, UUID storeId, UUID terminalId)`** in the transaction that records a payment or a refund whose method kind is `CASH` and that has a terminal (registry publication, D19). Production listens (`@ApplicationModuleListener`), creates a ticket `DRAWER_KICK` (dedupe on `(DRAWER_KICK, sourceId, 'DRAWER')`, so a redelivery kicks once) and a job on **the terminal's receipt printer**; the payload is only `ESC p m t1 t2` from the profile.
- A kick is time-critical and a late kick is a risk (a drawer opening a minute later, unattended): **one attempt, no retry, no hold, no redirect**. A printer with `drawer_pin` NULL → no job (logged at INFO). A printer not in mode NORMAL, or a kick claimed more than 30 s after its creation → FAILED at once (`PRINTER_NOT_NORMAL`, `KICK_EXPIRED`). The cashier then uses the key or the manual command. Kicks are excluded from `PRINT_JOBS_ATTENTION`.
- **Manual open:** `POST /api/v1/pos/drawer-kicks {reason}` (`CASH_OPERATE` on the session's terminal, Idempotency-Key, audited `DRAWER_OPENED_MANUALLY` with the reason), for entries, withdrawals and floats.
- Rejected: prefixing the kick to the receipt printout (the document is issued at close, often later than the cash payment); a synchronous socket call from settlement (an external call inside a transaction, forbidden by D48).

---

## 7. Permissions

**D159 — No new permission.** R5 reuses: `USER_MANAGE` (MFA reset, external identities), `CATALOG_MANAGE`/`CATALOG_VIEW` (exclusions), `PRODUCTION_MANAGE` (profiles), `CASH_OPERATE` (manual drawer), `SYSTEM_MONITOR` (alerts). Self-service MFA and OIDC linking need only an authenticated BackOffice session. So there is no `V3xxx` permission migration and no role seed change (`RoleSeedTest` unchanged).

---

## 8. Flyway blocks

**D160** — next free versions per module block (D5, D135):
- identity `V3013` (S56, MFA), `V3014` (S57, external identity)
- audit `V1105` (S56, the console audit function of D143)
- catalog `V5006` (S58)
- production `V7008` (S59), `V7009` (S60)
- cash `V8010` only if the S61 query plan needs an index on `cash_closing`
No cross-module FKs (D6). No migration updates an immutable row: new columns on `production_ticket` and `configuration_release` are added with a default or NULL; changed CHECKs are DDL only.

---

## 9. Consistency in R5 (re-checking §7 and §8.4)

**D161 — Named races and their proofs** ("IT" = integration test on real PostgreSQL).

| race / failure | prevention | proof |
|---|---|---|
| One TOTP code submitted by two sessions at once (replay) | CAS `UPDATE mfa_factor SET last_used_step = :s WHERE id = :id AND status = 'ACTIVE' AND last_used_step < :s` | IT `TotpReplayRaceTest` (latch; one 200, one 401) |
| One recovery code used twice at once | CAS on `used_at IS NULL` | IT `RecoveryCodeOnceTest` |
| Distributed guessing across many pending sessions | Counter and lock on the factor row, not the session | IT `MfaLockoutTest` |
| An admin reset while the user verifies | Verification CAS requires `status = 'ACTIVE'`; the reset ends sessions | IT `MfaResetDuringVerifyTest` |
| Two enrolments started at once | Partial unique: one PENDING per account | IT `ConcurrentEnrolmentTest` |
| An enrolment code redeemed twice | CAS on `used_at IS NULL` | IT `EnrolmentCodeOnceTest` |
| One IdP subject linked to two accounts at once | `UNIQUE (issuer, subject)` | IT `ExternalIdentityLinkRaceTest` |
| A sensitive request retried after step-up runs twice | 403 is never stored; the retry with the same key runs once (D47) | IT `StepUpRetryIdempotentTest` |
| Two terminals record cash payments → two kicks on one printer | Each payment kicks once (dedupe per source); two kicks are correct | IT `DrawerKickOncePerPaymentTest` |
| A kick sent late after a restart | Claim refuses a kick older than 30 s (FAILED `KICK_EXPIRED`) | IT `DrawerKickExpiresTest` (fixed `Clock`) |
| A status probe answers "paper out" | Nothing is written; RETRY_WAIT | IT `StatusProbeNotReadyTest` (fake printer answers `0x72` to `DLE EOT 4`) |
| Polling the alert endpoint loads the database | 30 s cache in config | unit `AlertsCacheTest` |

**Every §7 item still holds:** the IdP call happens in the browser redirect and in one back-channel token call outside any transaction (D48); the printer probe is part of the existing external send with no transaction; the kick is a registry publication with an idempotent consumer (§7, D19).

---

## 10. Failure modes (R5 additions)

| failure | behaviour |
|---|---|
| Internet down with OIDC enabled | OIDC routes answer 503 `OIDC_UNAVAILABLE`; password login and the POS keep working (P56). |
| IdP down at startup | The backend starts (no discovery at boot). |
| MFA key file missing while MFA is not `OFF` | The backend refuses to start and logs why (fail fast). |
| A user's phone clock drifts | ±1 step accepted; beyond that the code fails and the user uses a recovery code. The server's own clock comes from NTP (README). |
| The last administrator lost the phone and the codes | `deploy/mfa-break-glass.sh` from the server console, audited. |
| `verify-backups.sh` stops running | `backup-status.json` ages; `BACKUP_MONITOR_STALE` (in-app and Prometheus). |
| The status file is malformed | Treated as missing (CRITICAL `BACKUP_MONITOR_STALE`); WARN log once. |
| Prometheus not deployed | The in-app list `GET /backoffice/alerts` shows the same alerts. |
| Printer out of paper, with the probe on | Nothing sent; RETRY_WAIT with `PAPER_OUT`, then FAILED; visible on the POS and the alert list; retry after reloading paper. |
| Printer out of paper, probe off or unsupported | As R4: the bytes are accepted into the printer's buffer and may be lost; the job is SENT. A reprint is the remedy (§4). |
| Wrong code page or width configured | Visible on the test page (D156); the B5 checklist starts with it. |
| Drawer kick fails | FAILED at once, never retried; the cashier opens with the key or the manual command. |

---

## 11. R5 acceptance criteria (observable)

| # | criterion | spec |
|---|---|---|
| A57 | Mode `ALL_BACKOFFICE`: an enrolled account logs in → `mfa.state = PENDING`, every non-`/auth` endpoint 403; a valid code → VERIFIED with its authorities; the same code again → 401; 5 wrong codes → locked 15 min (429), audited once; a recovery code works once. | §10 |
| A58 | Mode `SENSITIVE`, `lan-cidrs = 192.168.1.0/24`: a login from 192.168.1.10 needs no code; from 203.0.113.5 it needs one; a remote account with no factor gets 403 `MFA_NOT_ENROLLED`, and enrols with an enrolment code; a LAN session used from a remote address → 403 `MFA_REQUIRED`. A support account always needs a code. | §10, §5 |
| A59 | Mode `SENSITIVE`: publishing a catalog release without a fresh second factor → 403 `MFA_STEP_UP_REQUIRED`; after `step-up`, the same request with the same Idempotency-Key → 201 once; 16 min later → 403 again. Every D142 permission is refused without step-up (parameterized test); POS endpoints and approver PINs never ask for a code. Mode `OFF`: the full R4 suite is green unchanged. | §10, §14 #9 |
| A60 | An administrator resets a user's MFA with a reason: the factor and codes are revoked, the user's sessions end, an enrolment code works once within 24 h; resetting oneself → 422; the break-glass function revokes and writes a system audit row. No secret, code or hash appears in audit rows, logs or `shared.idempotency_record`. | §10 |
| A61 | OIDC disabled: the OIDC routes 404 and the backend makes no outbound call at startup. Enabled against a stub IdP: a linked subject logs in with exactly its local RBAC authorities (IdP groups ignored); an unlinked subject → refused, audited, and no account created; an inactive or support account → refused; the IdP down → the backend starts and password login works. | §2, §10, §11 |
| A62 | Store B excludes item X: B's release validates without a price for X and lists X under `excludedItems`; a company-wide macro containing X is omitted from B's snapshot with `MACRO_OMITTED`; a quick page of store B naming X → error; store A's release still contains X; without the exclusion, B's validation fails with `ITEM_WITHOUT_PRICE`; with no exclusion rows, a new snapshot equals the R4 one; adding X on a POS of B → 422 `ITEM_NOT_IN_CATALOG` (existing); a delivery in B whose zone fee item is X → 422 `FEE_ITEM_NOT_PUBLISHED`. | §5, §8.3, §14 #7 |
| A63 | Each of the seven §12 conditions, provoked in an IT (a stale status file, a full filesystem in the file, a FAILED print job, a failed publication, an overdue UNKNOWN attempt, a closing with a difference, a failing export), appears in `GET /backoffice/alerts` with its code and store, and its metric moves; the alert clears when the condition does. No metric label is an order, payment, job or document id. `/actuator/prometheus` without a token or session → 401; with the token → 200; the token opens no other endpoint. | §12 |
| A64 | `deploy/test-monitoring.sh --self-test`: `verify-backups.sh` writes a schema-valid status file atomically; `promtool check rules` and `check config` pass; every metric named in `alert-rules.yml` exists in a scrape of the R5 backend (S63). | §11, §12 |
| A65 | Byte-level tests against `TcpFakePrinter`: an 80/48 and a 58/32 profile render the same styled ticket with the exact expected bytes (init, `ESC t`, `ESC a`, `ESC E`, `GS !`, wrap, rule width, feed, each cut mode); Portuguese text encodes to the CP850 and CP860 bytes; an R4 PLAIN ticket under the default profile is byte-identical to R4; with the probe on, "paper out" sends nothing and leaves RETRY_WAIT `PAPER_OUT`, "no answer" sends. | §4, §8.6, §12 |
| A66 | A cash payment on terminal T → exactly one `ESC p` job on T's receipt printer (pin and pulse from its profile), also after a forced redelivery; a card payment → none; a cash refund → one; a printer in HOLD → the kick is FAILED at once, never held or retried; a manual open needs `CASH_OPERATE`, a reason and is audited. | §4 |
| A67 | **Human checkpoint (B5):** the printer checklist (`docs/qa/test-plans/printer-hardware-b5.md`) is run on each purchased model and its results recorded. Not closable by a slice. | §12 |
| A68 | `verify()` passes with D137; an injected `org.springframework.security.oauth2` import outside identity/config, or a module dependency from `shared.ops`, fails the build. | §6 |

---

## 12. Process and what I need from you

The same gates as R1–R4. G1 is this document, G2/G3 are the [PRD](../prd/r5-stage5.md) slices S55–S63. Each slice is built with TDD in its own worktree (G4: `rtk mvn clean test` green, 0 skipped; deploy slices: their `--self-test`), reviewed by a fresh code reviewer (G5); S63 integrates; an architecture review (G6) follows; you merge (G7). **Every slice PR targets `main`; none is stacked.**

1. Approve the R5 scope (§1) and D137–D161, or override by id. Check **D140**, **D142**, **D144**, **D146** and **D151** in particular.
2. Answer P52–P60, or accept the recommendations. **P52, P54 and P57 shape the most code.**
3. Confirm that P49's items move to R6, and that approving this also counts as G2/G3 for the PRD.
