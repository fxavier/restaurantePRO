# 0034. Keycloak is not the core identity provider; optional OIDC federation is the integration path

Status: Accepted 2026-11-12 (R5 approval, D138, D144; P55, P56); built in S55 and S57, integrated in S63 (2026-11-13). Source: [r5-decisions.md](../design/r5-decisions.md) §3; spec §2, §3, §10, §11.

## Context

The user decided (2026-11-12) not to adopt Keycloak as the core identity provider, and asked for the reasons to be recorded, together with an integration path for companies that already run an IdP. Spec §2 gives each company its own installation (application, PostgreSQL, credentials, identity). §11 requires local operation without Internet and a clean refusal without the server. §10 requires server-side sessions, CSRF protection, a per-employee PIN with an adaptive hash and lockout, and MFA "para acesso remoto e funções sensíveis, conforme implantação". §3 requires RBAC scoped by store and terminal, enforced in the backend.

## Decision

- **No Keycloak (or any external IdP) as the core identity provider.** Reasons: (1) one more stateful service, schema, upgrade, backup and restore drill per company installation (§2); (2) a second process on the login path of an offline-first local server (§11); (3) the POS flow (PIN on an enrolled terminal, store-scoped authority, approver code + PIN, lockout and manager unlock; ADR 0008, 0013, 0014) is not an OIDC flow and would stay local anyway; (4) authority is local and per store (ADR 0009), which IdP groups cannot express without a second authority; (5) sessions, throttling, support grants, revocation and audit are built and tested.
- **Optional OIDC federation** with `spring-boot-starter-oauth2-client`, **off by default**, one OpenID Provider per installation (Keycloak, Entra ID, Google…). A BackOffice account is **pre-provisioned** and **linked** by its owner (`(iss, sub)` → account, `UNIQUE (issuer, subject)`); no account is ever created from a login. The IdP **only authenticates**: no role or group mapping; local RBAC stays the authority. The session is the same server-side session as a password login (ADR 0007).
- The discovery document is fetched **lazily** (own `ClientRegistrationRepository`), never at startup, so an installation without Internet starts; the OIDC routes answer 503 while the IdP is unreachable.
- Password login stays available for linked accounts (offline fallback, P56). An IdP's own MFA does not replace the local second factor unless the operator sets `trust-idp-mfa` (P55, default false).

## Alternatives considered

- **Keycloak as core IdP:** standard protocols and admin UI, but the per-company operational cost and the offline path above, and the POS would still need its own PIN flow.
- **Role mapping from IdP claims:** convenient onboarding, but it splits authority between two systems and cannot express per-store roles; changes in the IdP would bypass audited role administration.
- **Spring Boot's issuer-based registration:** less code, but it calls the IdP at startup and would stop an offline installation from starting.

## Consequences

- One new Maven dependency (`spring-boot-starter-oauth2-client`, which brings Nimbus for ID-token validation); only `identity` and `config` may import `org.springframework.security.oauth2..` (architecture rule).
- New table `identity.external_identity` (V3014). Audit actions `OIDC_LOGIN`, `OIDC_LOGIN_REFUSED`, `OIDC_LINKED`, `OIDC_UNLINKED`.
- Multi-provider federation and RP-initiated logout are not supported; each would be a new decision.
