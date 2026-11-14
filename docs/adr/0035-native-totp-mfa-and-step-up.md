# 0035. Native TOTP second factor, per-installation policy, and step-up in AccessPolicy

Status: Accepted 2026-11-12 (R5 approval, D139–D143, D145; P52–P54); built in S55 and S56, integrated in S63 (2026-11-13). Source: [r5-decisions.md](../design/r5-decisions.md) §3; spec §5, §10. Supersedes the "MFA (TOTP) is deferred" part of [0008](0008-two-login-flows.md) (D26) and amends D83 (support access).

## Context

§10: "Administração usa credenciais fortes; MFA para acesso remoto e funções sensíveis, conforme implantação" and "autorização adicional para anulação, descontos, crédito, reabertura e exportações". §14 #9: an operator without permission cannot change fiscal data, prices or closings through the API. D26 deferred MFA until remote administration was planned; the user decided on 2026-11-12 to build native TOTP (no Keycloak, ADR 0034). The POS PIN flow must stay local and offline.

## Decision

- **TOTP (RFC 6238)** for BackOffice accounts: HMAC-SHA1, 6 digits, 30 s, ±1 step, written over `javax.crypto` (no dependency). Secret encrypted with AES-256-GCM, key from a secret file; replay refused through `last_used_step` (compare-and-set); 5 wrong codes lock second-factor checks for 15 min; 10 single-use recovery codes (SHA-256); admin reset with a reason, issuing a one-time enrolment code; a console break-glass function for the last administrator.
- **Policy per installation**, a deployment property: `OFF` (code default, R4 behaviour), `SENSITIVE` (second factor for remote logins and support accounts; step-up for sensitive permissions everywhere), `ALL_BACKOFFICE` (second factor at every BackOffice login; step-up as well). **Remote** = the client address is outside the configured LAN CIDRs; checked at login and on every request of a session that never proved a factor.
- **Step-up in `AccessPolicy`:** `Permission.sensitive()` marks 15 permissions (company, users, roles, employees, terminals, support access, fiscal, catalog publication, promotions, closing approval, cash adjustments, credit limits, payment methods, report and audit exports). For an `ACCOUNT` actor, a sensitive grant needs a second factor proved within `step-up-max-age` (15 min) or the call gets 403 `MFA_STEP_UP_REQUIRED`. `identity` reports the session's assurance through `access.AuthenticationAssurance`; the rule itself stays in `access`.
- POS sessions (`EMPLOYEE`) and approver PINs are never asked for a second factor.
- Responses carrying a secret, recovery codes or an enrolment code take no `Idempotency-Key`, so no secret is stored in `shared.idempotency_record`.

## Alternatives considered

- **A per-use-case `requireSensitive` call:** finer than a permission, but about 40 call sites in 9 modules and easy to forget; the permission is already the unit of authorization (ADR 0009).
- **Policy as editable data:** an administrator could switch it in the BackOffice, but so could a stolen administrator session; §10 says "conforme implantação".
- **WebAuthn/passkeys:** phishing-resistant, but needs HTTPS origins per browser and a JS ceremony; a later addition next to TOTP.
- **Plaintext TOTP secrets** (backups are encrypted anyway): one secret fewer to manage, but a database read would allow generating codes.

## Consequences

- Tables `identity.mfa_factor`, `mfa_recovery_code`, `mfa_enrolment_code` (V3013); functions `identity.mfa_break_glass_reset` and `audit.record_console_event` (V1105) for the console.
- New secret `mfa_key`; the backend refuses to start when MFA is on and the key is missing.
- Clients repeat a request refused with `MFA_STEP_UP_REQUIRED` after `POST /api/v1/auth/mfa/step-up`, with the same Idempotency-Key (a 403 is never stored, ADR 0015).
- Proofs: `AccessPolicyStepUpTest`, `StepUpTest` (all 15 permissions), `TotpReplayRaceTest`, `NoSecretLeakTest`, `MfaModeOffUnchangedTest`.
