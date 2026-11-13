# PRD R5 — Hardening: identity, store availability, alerts, printers (backend)

Status: **APPROVED** 2026-11-12 (all recommendations P52–P60), together with [r5-decisions.md](../design/r5-decisions.md) (cited as Dn, Pn, An). Earlier decisions: [backend-decisions.md](../design/backend-decisions.md), [r2-decisions.md](../design/r2-decisions.md), [r3-decisions.md](../design/r3-decisions.md), [r4-decisions.md](../design/r4-decisions.md). Glossary: [CONTEXT.md](../../CONTEXT.md). Spec: [product_description.md](../product_description.md). Conventions: [backend-conventions.md](../architecture/backend-conventions.md).

Base package `com.xavier.backend`, per-module layout D12, Flyway blocks D160. Every slice is built with TDD, **red first**: the slice's named tests are written and fail before the code, and they are deterministic (an injected `Clock`, fixed ids where order matters, no sleeps; concurrency tests use latches and real PostgreSQL). No `@Disabled`, and `cd backend && rtk mvn clean test` must be green at exit with **0 skipped**.

- **Every slice PR targets `main`, and none is stacked.** A slice branches from `main` once every slice it is blocked by has merged.
- Slices merge one at a time, in ascending slice number within a group, with the full suite after each merge.
- Slices **do not commit `docs/architecture/modules/`** (D44). Only S63 does.
- **Only S55 edits `pom.xml`** (one dependency, `spring-boot-starter-oauth2-client`, D144).
- **Only S62 edits `deploy/compose.yaml`, `deploy/.env.example` and `deploy/README.md`**, with the names frozen below. Backend slices give every new property a default in code (`@ConfigurationProperties` defaults), so **no slice except S61 edits `application.yaml`**.
- The names below are binding. Internals are the implementer's call.

Conventions for every endpoint below, as in R2–R4:
- **POS:** `requirePos(...)`, store and terminal from the session (D39).
- **Idem:** `Idempotency-Key` through `access.IdempotentRequests`. **Exception (D143):** a response that carries a TOTP secret, recovery codes or an enrolment code takes no key, so no secret reaches `shared.idempotency_record`.
- **Errors:** a stale version gives 409. Business rules give 422 `ProblemDetail` with a stable `code`; the new 403 codes (`MFA_*`) are ProblemDetails with a `code` too. Every new code is added to `CodedErrorContractTest`.
- **Step-up:** "sensitive" below means the D142 rule applies through `AccessPolicy`; it is not repeated per endpoint.
- **Audit:** every write calls `AuditLog.recordAsCurrentActor` (D41) or `recordAsSystem`. Never a secret, code or hash in `details`.

Frozen configuration names (S62 wires them in Compose; backend slices read them with these defaults):

| property | env (Compose) | default | slice |
|---|---|---|---|
| `restaurantpro.security.mfa.mode` | `RESTAURANTPRO_MFA_MODE` | `OFF` (`.env.example`: `SENSITIVE`, P52) | S56 |
| `restaurantpro.security.mfa.step-up-max-age` | `RESTAURANTPRO_MFA_STEP_UP_MAX_AGE` | `PT15M` (P53) | S56 |
| `restaurantpro.security.mfa.key-file` | `RESTAURANTPRO_MFA_KEY_FILE` → secret `mfa_key` | unset (required unless mode `OFF`) | S56 |
| `restaurantpro.security.lan-cidrs` | `RESTAURANTPRO_LAN_CIDRS` | D140 list | S56 |
| `restaurantpro.security.oidc.enabled`, `.issuer-uri`, `.client-id`, `.client-secret-file`, `.display-name`, `.scopes`, `.trust-idp-mfa`, `.post-login-uri` | `RESTAURANTPRO_OIDC_*`; secret `oidc_client_secret` (may be empty) | `false`, —, —, —, `"SSO"`, `openid`, `false` (P55), `/backoffice/` | S57 |
| `restaurantpro.alerts.backup-status-file` | `RESTAURANTPRO_ALERTS_BACKUP_STATUS_FILE` = `/run/restaurantpro/status/backup-status.json` | unset = no backup alerts | S61 |
| `restaurantpro.alerts.cash-difference-threshold` | `RESTAURANTPRO_ALERTS_CASH_DIFFERENCE_THRESHOLD` | `0` | S61 |
| `restaurantpro.metrics.token-file` | `RESTAURANTPRO_METRICS_TOKEN_FILE` → secret `metrics_token` | unset = session only | S61 |

---

## S55 — Platform prefactor (runs alone, first)

**Write scope:**
- `pom.xml` (add `org.springframework.boot:spring-boot-starter-oauth2-client`, version from the Boot parent)
- `access/Permission.java`, `access/AccessPolicy.java`, new `access/AuthenticationAssurance.java`, `access/AssuranceState.java`, `access/StepUpRequiredException.java`
- new `shared/ops/**` (`AlertSource`, `OperationalAlert`, `AlertSeverity`, `ExportMonitor`, `package-info.java`)
- new `settlement/model/event/CashDrawerKickRequested.java` (record only; nothing publishes it yet)
- the error mapping of `StepUpRequiredException` (where `AccessDeniedException` is mapped today, `config/ProblemDetailResponder.java`)
- `src/test/.../architecture`, `docs/architecture/backend-conventions.md` (rule rows)

| contract | detail |
|---|---|
| `Permission.sensitive()` | `true` for exactly the 15 constants of D142 (one `EnumSet`). Append-only rule of D27 unchanged. |
| `access.AuthenticationAssurance` | `AssuranceState current()` for the current request. `AssuranceState(boolean enforced, boolean enrolled, Instant secondFactorAt /* null = never */, Duration maxAge)`; `AssuranceState.NOT_ENFORCED`. Implemented by identity in S56. |
| `AccessPolicy` hook | After a successful permission check, for an `ACCOUNT` actor whose granting permission is sensitive (`requireAny`: all granting ones), and `assurance.current().enforced()`: no factor → `StepUpRequiredException.enrolmentRequired()` (403 `MFA_ENROLMENT_REQUIRED`); `secondFactorAt` null or older than `maxAge` → `StepUpRequiredException.stepUpRequired()` (403 `MFA_STEP_UP_REQUIRED`). Looked up through `ObjectProvider<AuthenticationAssurance>`, cached per request; absent bean → not enforced. `requirePos*` never calls it. Applies to `require`, `requireAny`, `requireInAnyStore`, `scopeOf`. |
| `StepUpRequiredException extends AccessDeniedException` | `String code()`; ProblemDetail 403 with `code` and `stepUpUri = /api/v1/auth/mfa/step-up`. |
| `shared.ops.AlertSource` | `List<OperationalAlert> alerts()` — fast (reads a gauge snapshot or one indexed query). |
| `shared.ops.OperationalAlert` | `OperationalAlert(String code, AlertSeverity severity, UUID storeId, long count, Instant since, String summary)`; `storeId` null = installation-wide. |
| `shared.ops.ExportMonitor` (`@Component`) | `<T> T monitor(String kind, Supplier<T> export)`: runs it, increments `exports_total{kind, outcome}`, remembers the last failure per kind (instant) in memory; rethrows. `List<OperationalAlert> recentFailures(Instant now)` (24 h). Also an `AlertSource` itself (`EXPORT_FAILED`). Kinds: `report_csv`, `audit_csv`, `inventory_csv`. |
| `settlement.model.event.CashDrawerKickRequested` | `CashDrawerKickRequested(String sourceType, UUID sourceId, UUID storeId, UUID terminalId)`, `@NamedInterface("event")` package as the other settlement events. `sourceType ∈ {PAYMENT, REFUND}`. |
| Architecture tests | `onlyIdentityAndConfigUseOAuth2` (no class outside `identity..`/`config..` depends on `org.springframework.security.oauth2..`); `sharedOpsDependsOnNoModule`. Each has an `ArchitectureRulesFireTest` fixture. |

**Tests (red first):** `PermissionSensitiveSetTest` (exactly the D142 set), `AccessPolicyStepUpTest` (unit: account × sensitive × fresh/stale/never/not-enrolled/not-enforced; `requireAny` with a non-sensitive alternative passes; EMPLOYEE never asked; a missing permission still plain 403 first), `ExportMonitorTest`, `ArchitectureRulesFireTest`, `CodedErrorContractTest` (`MFA_STEP_UP_REQUIRED`, `MFA_ENROLMENT_REQUIRED`), `ModularityTests` green. The full R4 suite stays green (no `AuthenticationAssurance` bean yet).

**Acceptance:** the rule half of A59 and A68.

---

## Group 1 (after S55, in parallel): S56 ∥ S58 ∥ S59 ∥ S61 ∥ S62

### S56 — Identity: TOTP MFA, step-up, recovery, reset, break-glass
**Write scope:** `identity/` except `identity/oidc/**`; `db/migration/identity/V3013`; `db/migration/audit/V1105`; `deploy/mfa-break-glass.sh` (+ its `--self-test`).

**Migrations:** `V3013__identity_mfa.sql`:
- `mfa_factor(id, account_id FK user_account, kind CHECK = 'TOTP', status CHECK IN ('PENDING','ACTIVE','REVOKED'), secret_ciphertext BYTEA NOT NULL, secret_nonce BYTEA NOT NULL, key_id VARCHAR(20) NOT NULL, last_used_step BIGINT NOT NULL DEFAULT 0, failed_attempts INT NOT NULL DEFAULT 0 CHECK ≥ 0, locked_until, pending_expires_at, created_at, activated_at, revoked_at, revoked_by_account_id, revoke_reason, version)`; CHECK `status = 'PENDING' ⇔ pending_expires_at IS NOT NULL`; CHECK `status = 'ACTIVE' ⇒ activated_at IS NOT NULL`; CHECK `status = 'REVOKED' ⇔ revoked_at IS NOT NULL`; partial unique `(account_id) WHERE status = 'ACTIVE'` and `(account_id) WHERE status = 'PENDING'`.
- `mfa_recovery_code(id, factor_id FK, code_hash CHAR(64) NOT NULL, used_at)`, `UNIQUE(factor_id, code_hash)`.
- `mfa_enrolment_code(id, account_id FK, code_hash CHAR(64) NOT NULL UNIQUE, expires_at, used_at, issued_by_account_id, reason NOT NULL, created_at)`; partial unique `(account_id) WHERE used_at IS NULL`.
- Function `identity.mfa_break_glass_reset(p_username text, p_reason text) RETURNS uuid`: revokes the ACTIVE factor of the normalized username (exception when none or unknown) and returns the account id. It touches only identity's tables.

`V1105__audit_console_event.sql` (audit's own block, written by this slice because only it needs it): function `audit.record_console_event(p_action text, p_entity_type text, p_entity_id text, p_reason text) RETURNS uuid` inserts an `audit_event` with `actor_id NULL` (the system) and `details = {"via": "console"}`. `deploy/mfa-break-glass.sh` calls both functions in **one** `psql` transaction (`BEGIN; SELECT identity.mfa_break_glass_reset(…); SELECT audit.record_console_event('MFA_BREAK_GLASS_RESET', 'UserAccount', <id>, <reason>); COMMIT;`), so each module's schema is written only by its own function (D6).

**Contracts:**
- `identity.mfa.Totp` (pure): `boolean verify(byte[] secret, int code, long step)`, `long stepAt(Instant)`, `int codeAt(byte[] secret, long step)`; `identity.mfa.Base32` (RFC 4648).
- `identity.mfa.SecretCipher`: AES-256-GCM, key from `mfa.key-file`; startup fails when mode ≠ `OFF` and the key is missing or not 32 bytes.
- `identity.service.SessionAssurance implements access.AuthenticationAssurance`: from the session attributes `MFA_STATE`, `MFA_SECOND_FACTOR_AT` and the account's factor; `enforced = mode ≠ OFF`.
- `identity.service.RemoteAddresses`: `boolean isRemote(HttpServletRequest)` against `lan-cidrs` (IPv4 and IPv6 CIDR parsing, our own; no dependency).
- Login (D141): `LoginService.passwordLogin` returns a `SessionView` with `mfa.state`; `LoginSessions` stores the pending state; `MustChangeCredentialFilter` also refuses `MFA_PENDING` and `MFA_ENROLMENT_REQUIRED` sessions outside `/api/v1/auth/**`; a new `MfaRemoteFilter` refuses a remote request on a session that never proved a factor (403 `MFA_REQUIRED`) when mode ≠ `OFF`.
- Support accounts (D145): second factor at every login when mode ≠ `OFF`.
- `UserSummary` gains `mfaEnrolled`; `SessionView` gains `mfa {state, stepUpValidUntil}` (additive JSON).

**Endpoints:**

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `POST /api/v1/auth/mfa/verify {code \| recoveryCode}` | a session in `MFA_PENDING` | — | Restarts the session as VERIFIED. 401 `MFA_INVALID_CODE`; 429 `MFA_LOCKED` (`Retry-After`); 409 `MFA_NOT_PENDING`; pending older than 5 min → 401 `MFA_PENDING_EXPIRED` |
| `POST /api/v1/auth/mfa/step-up {code \| recoveryCode}` | a VERIFIED or plain ACCOUNT session with an ACTIVE factor | — | Sets `secondFactorAt`. 401 `MFA_INVALID_CODE`; 429 `MFA_LOCKED`; 409 `MFA_NOT_ENROLLED` |
| `GET /api/v1/auth/mfa` | ACCOUNT session | — | `{mode, enrolled, activatedAt, recoveryCodesLeft, stepUpValidUntil}` |
| `POST /api/v1/auth/mfa/enrolment {currentPassword, enrolmentCode?}` | ACCOUNT session (also `MFA_ENROLMENT_REQUIRED`) | **no** (secret) | `{secret, otpauthUri, expiresAt}`; replaces a PENDING one. 401 `WRONG_CURRENT_PASSWORD`; 409 `MFA_ALREADY_ENROLLED`; remote without a valid code → 403 `MFA_ENROLMENT_CODE_REQUIRED`; 422 `MFA_ENROLMENT_CODE_INVALID` |
| `POST /api/v1/auth/mfa/enrolment/confirm {code}` | same | **no** (codes) | `{recoveryCodes[10]}`; session restarted as VERIFIED. 401 `MFA_INVALID_CODE`; 409 `MFA_NO_PENDING_ENROLMENT` (or expired) |
| `POST /api/v1/auth/mfa/recovery-codes` | ACCOUNT with ACTIVE factor and a fresh step-up | **no** (codes) | Replaces all; 403 `MFA_STEP_UP_REQUIRED` |
| `POST /api/v1/backoffice/users/{id}/mfa/reset {reason}` | `USER_MANAGE` (sensitive) | **no** (code) | `{enrolmentCode, expiresAt}`; revokes, ends the user's sessions. 404; 422 `MFA_SELF_RESET`, `REASON_REQUIRED` |
| `GET /api/v1/backoffice/users/{id}/mfa` | `USER_MANAGE` | — | Status only, never a secret |

**Tests:** `TotpTest` (RFC 6238 Appendix B SHA-1 vectors truncated to 6 digits; ±1 step; fixed `Clock`), `Base32Test` (RFC 4648 vectors), `SecretCipherTest` (round trip; a tampered ciphertext fails; missing key fails startup when mode ≠ OFF), `RemoteAddressesTest` (IPv4/IPv6 CIDRs, forwarded address), `MfaLoginFlowTest` (A57: pending → verify; pending holds no authority; expiry), `MfaRemotePolicyTest` (A58, three modes × LAN/remote × enrolled/not × support account), `StepUpTest` (A59: parameterized over the 15 permissions through real endpoints; 16 min later refused; POS and approver PIN untouched), `StepUpRetryIdempotentTest`, `TotpReplayRaceTest`, `RecoveryCodeOnceTest`, `MfaLockoutTest`, `MfaResetDuringVerifyTest`, `ConcurrentEnrolmentTest`, `EnrolmentCodeOnceTest`, `MfaResetTest` (A60: sessions ended, audited with reason), `BreakGlassFunctionTest` (runs the script's two statements in one transaction over JDBC: factor revoked, one system audit row; an unknown username rolls both back), `NoSecretLeakTest` (after every flow: no secret, code or code hash in `audit.audit_event.details`, in `shared.idempotency_record`, or in captured logs), `MfaModeOffUnchangedTest` (mode OFF: R4 login and a sensitive call unchanged). `deploy/mfa-break-glass.sh --self-test` (shellcheck-clean; refuses without a reason; never echoes the username's secrets).

**Acceptance:** A57, A58, A59, A60.

### S58 — Catalog: per-store exclusions
**Write scope:** `catalog/`; `db/migration/catalog/V5006`.

**Migrations:** `V5006__catalog_store_item_exclusion.sql`: `store_item_exclusion(store_id UUID, item_id UUID FK item, reason VARCHAR(200), excluded_at, excluded_by_account_id)`, PK `(store_id, item_id)`, index `(item_id)`; `configuration_release.validation_warnings JSONB NULL` (DDL only; the snapshot immutability trigger is unaffected because the column is set at validation, before the snapshot).

**Contracts:**
- `WorkingCopy.of(storeId, at)` leaves the store's excluded items out of `activeItems` and applies the D147 table; `View.errors()` keeps its codes; a new `View.warnings()` and `View.excluded()`.
- `ReleaseValidationError` stays; new `ReleaseValidationWarning(code, itemId, itemCode, message)` with codes `MENU_OPTION_EXCLUDED`, `MACRO_OMITTED`, `QUICK_PAGE_ENTRY_OMITTED`, `AUTOMATIC_ITEM_OMITTED`; new error codes `QUICK_PAGE_ENTRY_EXCLUDED`, `AUTOMATIC_ITEM_EXCLUDED`. `ITEM_WITHOUT_PRICE`'s message names both remedies.
- The release view gains `excludedItems[{itemId, code, name}]` (computed) and `validationWarnings[]` (stored). **(amended 2026-11-13)** `excludedItems` is computed by `validate` only and is `null` (not computed) in every other release response, because an empty list would read as "excludes nothing"; publish recomputes the warnings and stores them on the release in the same update. `validationWarnings` is `null` before the first validation (as on promotion releases), and the column is frozen with the rest of a PUBLISHED or RETIRED release (V5006 replaces the V5002 trigger function; the promotions side does the same in V12022).
- `CatalogApi.dailyDishes(storeId, date)` and `GET /pos/catalog/daily-dishes` drop ids absent from the current published snapshot.
- `CatalogSnapshot.SCHEMA_VERSION` stays 2.

**Endpoints:**

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `GET /api/v1/backoffice/catalog/stores/{storeId}/item-exclusions` | `CATALOG_VIEW` in the store | — | Paged (D23) |
| `PUT /api/v1/backoffice/catalog/stores/{storeId}/item-exclusions/{itemId} {reason?}` | `CATALOG_MANAGE` in the store | — (PUT) | 404 `STORE_NOT_FOUND` (through `OrganizationApi`), `ITEM_NOT_FOUND`; audited `ITEM_EXCLUDED_FROM_STORE` |
| `DELETE /api/v1/backoffice/catalog/stores/{storeId}/item-exclusions/{itemId}` | `CATALOG_MANAGE` in the store | — | 404 when not excluded; audited `ITEM_INCLUDED_IN_STORE` |
| `POST /api/v1/backoffice/catalog/releases/{id}/validate` | unchanged | unchanged | 200 adds `excludedItems`, `validationWarnings`; 422 lists errors as before |

**Tests:** `StoreExclusionReleaseTest` (A62: B without price for X validates, X listed; A unaffected; without exclusion `ITEM_WITHOUT_PRICE`), `ExclusionReferenceRulesTest` (each D147 row: menu option dropped and bounds error, macro omitted, quick page of all stores vs of the store, automatic item of all stores vs of the store), `NoExclusionSnapshotUnchangedTest` (same working copy, no rows: snapshot JSON equal to the R4 builder's), `DailyDishFilteredTest`, `ExclusionPermissionTest` (other store → 403), `ExclusionAuditTest`.

**Acceptance:** A62 (catalog part; the POS and delivery checks run in S63's journey).

### S59 — Production: ESC/POS driver, profiles, styled tickets, probe, print-queue alerts
**Write scope:** `production/` except the drawer-kick files of S60 (`production/service/DrawerKick*.java`, `production/controller/DrawerKickController.java`); `db/migration/production/V7008`; test `production/TcpFakePrinter.java`.

**Migrations:** `V7008__production_printer_profile_and_styled_tickets.sql`:
- `printer` + `paper_width_mm SMALLINT NOT NULL DEFAULT 80 CHECK IN (58, 80)`, `columns SMALLINT NOT NULL DEFAULT 48`, CHECK `(paper_width_mm = 58 AND columns BETWEEN 24 AND 35) OR (paper_width_mm = 80 AND columns BETWEEN 36 AND 64)`, `code_table SMALLINT NULL CHECK 0–255`, `cut_mode VARCHAR(8) NOT NULL DEFAULT 'PARTIAL' CHECK IN ('PARTIAL','FULL','NONE')`, `feed_lines SMALLINT NOT NULL DEFAULT 4 CHECK 0–10`, `status_probe BOOLEAN NOT NULL DEFAULT false`, `drawer_pin SMALLINT NULL CHECK IN (0, 1)`, `drawer_pulse_ms SMALLINT NOT NULL DEFAULT 100 CHECK BETWEEN 2 AND 510 AND drawer_pulse_ms % 2 = 0`, `last_status VARCHAR(20) NULL`, `last_status_at TIMESTAMPTZ NULL`.
- `production_ticket` + `content_format VARCHAR(10) NOT NULL DEFAULT 'PLAIN' CHECK IN ('PLAIN','STYLED_V1','KICK')` (`KICK` used by S60).
- `print_job.last_error` values gain `COVER_OPEN`, `PAPER_OUT`, `PRINTER_ERROR` (no CHECK exists today; none added).
- Index `print_job (store_id, created_at) WHERE status IN ('FAILED','UNKNOWN') AND resolution IS NULL` for the alert.

**Contracts:**
- `production.model.PrinterProfile` (value object on `Printer`): the D154 fields; `PrinterProfile.presets()` → `EPSON_TM_80`, `GENERIC_80_42`, `GENERIC_58`.
- `production.model.PrintLine` (sealed): `Text(String text, Style style, Align align)`, `Rule()`, `LeftRight(String left, String right, Style style)`; `Style {NORMAL, BOLD, DOUBLE, TALL}`, `Align {LEFT, CENTER, RIGHT}`; `StyledContent.toJson/fromJson` (`STYLED_V1`).
- `EscPosRenderer.render(Printout printout, PrinterProfile profile, CodePage codePage, boolean copyMark)` where `Printout(ContentFormat format, List<PrintLine> lines, String qrPayload)`; PLAIN content becomes `Text(line, NORMAL, LEFT)` per line. Wrap rules of D155; style and alignment bytes only on change, so PLAIN emits none. The old `render(List<String>, …)` is removed (its callers are in production).
- `TicketLayouts` returns `List<PrintLine>` for production's own tickets (styles of D155); `TicketService` stores them as `STYLED_V1`. Fiscal `DocumentPrintout` stays PLAIN.
- `PrinterTransport.send(PrinterAddress address, byte[] payload, boolean probeStatus)`; `SendOutcome` gains `NOT_READY_COVER_OPEN`, `NOT_READY_PAPER_OUT`, `NOT_READY_ERROR` (method `boolean nothingWritten()`), and a `paperNearEnd` flag is reported through `SendResult(SendOutcome outcome, boolean paperNearEnd)`. `PrintQueue.record` maps every `nothingWritten()` outcome like `NOT_CONNECTED` with its `last_error`, and updates `printer.last_status`.
- `EscPosTcpTransport` constructor: `connect + 1 s probe + write < PrintJob.LEASE`.
- `ProductionMetrics`: gauges `print_jobs_unresolved{store}` and `print_jobs_oldest_waiting_seconds{store}`; `production.service.PrintQueueAlertSource implements AlertSource` (`PRINT_JOBS_ATTENTION`, `PRINT_QUEUE_STALLED`, D150).
- Test page (D156): ruler, accents line, BOLD, DOUBLE, TALL lines.
- `TcpFakePrinter` (test): `answering(byte offlineStatus, byte paperStatus)` replies to `10 04 02` / `10 04 04` while reading; `silent()` never replies; `received()` keeps the raw bytes, including the probes.

**Endpoints** (BackOffice, `PRODUCTION_MANAGE`):

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `GET /api/v1/backoffice/printer-profiles` | `PRODUCTION_MANAGE` | — | The three presets |
| `POST /api/v1/backoffice/printers`, `PUT /printers/{id}` (existing) gain `paperWidthMm, columns, codeTable?, cutMode, feedLines, statusProbe, drawerPin?, drawerPulseMs` | unchanged | unchanged | 422 `PRINTER_PROFILE_INVALID` (width × columns, pulse); omitted fields keep the defaults |
| `GET /api/v1/backoffice/printers` | unchanged | — | + profile, `lastStatus`, `lastStatusAt` |

**Tests:** `EscPosRendererGoldenTest` (A65: one styled ticket × {80/48, 58/32} × {PARTIAL, FULL, NONE}, exact byte arrays in the test source; wrap at a space with indentation; a word longer than the width; DOUBLE halves the width; rule length), `CodePageBytesTest` (`ãõçÃÕÇ` in CP850 and CP860; `code_table` override in `ESC t`), `R4TicketBytesUnchangedTest` (an R4 PLAIN ticket under the default profile is byte-identical to `render` at R4), `StatusProbeTest` (fake answers: cover open → nothing sent, `COVER_OPEN`; paper end `0x72` → `PAPER_OUT`; near end `0x1E` → sent and `last_status`; silent → sent), `StatusProbeNotReadyTest` (through the worker: RETRY_WAIT then FAILED after 5), `RedirectUsesTargetProfileTest`, `PrintQueueAlertSourceTest` (unresolved in 24 h; a resolved one not counted; oldest waiting), `PrinterProfileValidationTest`, all R2–R4 production tests green (their payload assertions updated only where styles change bytes; the text content is unchanged).

**Acceptance:** A65; the print-queue half of A63.

### S61 — Alerts: sources, in-app list, metrics, scrape token
**Write scope:** new `config/alerts/**`, new `config/MetricsScrapeSecurity.java`; new `shared/events/EventPublicationAlertSource.java` (+ one query method in `shared/events/EventPublicationStats.java`); new `settlement/service/InDoubtAlertSource.java` (+ a read accessor in `InDoubtGauge`); new `cash/service/CashDiscrepancyAlertSource.java` and its repository method; the export call sites `reporting/service/ReportExportService.java`, `audit/service/AuditExportService.java`, `inventory/service/InventoryReportService.java` (wrap in `ExportMonitor.monitor`); `application.yaml` (alert defaults, comments); `db/migration/cash/V8010` only if needed.

**Contracts:**
- `config.alerts.BackupStatusReader`: reads `alerts.backup-status-file` at most once a minute; parses schema 1 (D151); unknown `schemaVersion` or malformed → treated as missing (WARN once). Gauges `backup_status_age_seconds`, `backup_last_success_age_seconds{kind}`, `backup_problems`, `filesystem_free_ratio{name}` (`backups`, `docker` from the file; `app` from the JVM's working directory). `config.alerts.BackupAlertSource`, `DiskAlertSource` (`BACKUP_MONITOR_STALE`, `BACKUP_LATE`, `BACKUP_PROBLEM`, `DISK_SPACE_LOW`).
- `EventPublicationStats.oldestIncomplete()` → `Optional<Instant>` (V1006 partial index); gauge `event_publications_oldest_incomplete_seconds`; `EventPublicationAlertSource` (`EVENTS_FAILED`, `EVENTS_BACKLOG`).
- `InDoubtAlertSource` (`PAYMENTS_IN_DOUBT` per store, from the gauge's last refresh; no new query).
- `CashDiscrepancyAlertSource`: closings SUBMITTED or APPROVED in the last 24 h with Σ|difference| > threshold, per store; gauge `cash_closings_with_difference{store}`, refreshed every minute.
- `config.alerts.AlertsService`: collects `List<AlertSource>`, 30 s cache, a failing source becomes one alert `ALERT_SOURCE_FAILED` (WARNING) rather than a 500; `scopeOf(SYSTEM_MONITOR)` filter. Health component `operationalAlerts` with custom status `ALERT` (not in the aggregation order).
- `config.MetricsScrapeSecurity`: an `@Order(0)` filter chain matching only the Prometheus endpoint: a valid `Bearer` token (SHA-256, `MessageDigest.isEqual`) or `SYSTEM_MONITOR`; 401 otherwise.

**Endpoints:**

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `GET /api/v1/backoffice/alerts` | `SYSTEM_MONITOR` (scope filters stores) | — | `{generatedAt, alerts[{code, severity, storeId, count, since, summary}]}`, ordered CRITICAL first |
| `GET /actuator/prometheus` (management port) | token (D152) or `SYSTEM_MONITOR` | — | 401 without either |

**Tests:** `BackupStatusReaderTest` (fresh, stale, missing, malformed, unknown schema; fixed `Clock`), `DiskAlertSourceTest` (15 % / 5 % thresholds), `EventPublicationAlertSourceTest` (a publication exhausted → `EVENTS_FAILED`; an old incomplete one → `EVENTS_BACKLOG`), `InDoubtAlertSourceTest`, `CashDiscrepancyAlertSourceTest` (a closing with a difference → alert; threshold; older than 24 h → none), `ExportFailureAlertTest` (a forced failure in each of the three exports → counter and alert), `AlertsEndpointTest` (A63: store scope, installation-wide alerts hidden from a store-scoped holder, cache, a failing source), `AlertHealthNeverDownTest` (with alerts, `/actuator/health` stays `UP`), `MetricsScrapeTokenTest` (no token 401, token 200, token on `/actuator/metrics` 401), `MetricLabelsTest` (every meter's tag keys ⊆ {store, status, state, module, kind, outcome, name, …existing Micrometer/JVM keys}; no tag value is an id of an order, payment, job or document — checked by seeding one of each and asserting none of their ids appears in the scrape).

**Acceptance:** A63 (all but the print queue half).

### S62 — Deploy: backup status file, monitoring, R5 wiring
**Write scope:** `deploy/verify-backups.sh`, `deploy/compose.yaml`, `deploy/.env.example`, `deploy/README.md`, new `deploy/monitoring/**`, new `deploy/test-monitoring.sh`, `deploy/.gitignore` (`status/`).

**Deliverables:**
- `verify-backups.sh` writes `deploy/status/backup-status.json` per D151 (schema 1, atomic, 0644 in 0755) on **every** run, also when it exits non-zero (then `ok: false` and `problems`). It adds `lastLogicalBackupAt` (newest `backup.sh` archive) and the two `filesystems` (`df -Pk` of the backup directory and of Docker's root directory).
- `compose.yaml`: backend mounts `./status:/run/restaurantpro/status:ro`; secrets `mfa_key`, `metrics_token`, `oidc_client_secret`; the env names of the frozen table; profile `monitoring` with a pinned `prometheus` service (internal network, volume `prometheus-data`, `metrics_token` secret, `./monitoring` read-only).
- `deploy/monitoring/prometheus.yml` (scrape `backend:8081/actuator/prometheus` with `authorization.credentials_file`, 30 s; `rule_files`) and `deploy/monitoring/alert-rules.yml` (one rule per D150 row with the same thresholds, plus `RestaurantProBackendDown`; every rule has `severity` and a `summary` without ids).
- `.env.example`: `RESTAURANTPRO_MFA_MODE=SENSITIVE` (P52), `RESTAURANTPRO_LAN_CIDRS` with a comment on VPN pools, the OIDC block commented out, the alert settings.
- `README.md`: "Second factor (MFA)" (modes, LAN CIDRs and VPN, enrolment, recovery, break-glass), "Single sign-on (optional)" (one provider, link flow, password fallback, redirect URI `https://<host>/api/v1/auth/oidc/callback`), "Alerts" (in-app list, status file, Prometheus profile, Alertmanager pointer), "Printers" (profiles, presets, test page, B5 checklist link), and the **upgrade note**: create `secrets/mfa_key` (`openssl rand -base64 32`), `secrets/metrics_token` (`openssl rand -hex 32`) and an empty `secrets/oidc_client_secret` before `docker compose up` (mode 0600, as the other secrets).

**Tests:** `deploy/test-monitoring.sh --self-test`: shellcheck-clean scripts; `promtool check config` and `promtool check rules` through the pinned Prometheus image; `verify-backups.sh` against a test-mode fixture writes a status file that `jq` validates against schema 1 (keys, types, ISO instants, no `/` path in any value); a forced failure still writes `ok: false`; `MONITORING_FAULT=<step>` makes each step fail. `deploy/test-backup-encryption.sh` and `deploy/test-pitr.sh` stay green.

**Acceptance:** A64 (except the rules-vs-scrape check, which S63 runs).

---

## Group 2: S57 (after S56) ∥ S60 (after S59)

### S57 — Identity: optional OIDC federation
**Write scope:** new `identity/oidc/**`; `db/migration/identity/V3014`; `config/SecurityConfig.java` (the conditional `oauth2Login` on the API chain).

**Migrations:** `V3014__identity_external_identity.sql`: `external_identity(id, account_id FK user_account, issuer VARCHAR(255) NOT NULL, subject VARCHAR(255) NOT NULL, linked_at, linked_by_account_id, last_login_at)`, `UNIQUE(issuer, subject)`, `UNIQUE(account_id)` (one provider per installation, one link per account).

**Contracts:**
- `identity.oidc.OidcProperties` (`restaurantpro.security.oidc.*`); when `enabled = false` no OIDC bean is created and the routes 404.
- `identity.oidc.LazyClientRegistrationRepository implements ClientRegistrationRepository`: discovery on first use, cached; failure → `OidcUnavailableException` → 503 `OIDC_UNAVAILABLE`, retried on the next request.
- `SecurityConfig.apiSecurity`: when enabled, `oauth2Login` with authorization base URI `/api/v1/auth/oidc/authorize`, redirection URI `/api/v1/auth/oidc/callback`, PKCE, a no-op `OAuth2AuthorizedClientRepository` (tokens discarded), success and failure handlers from identity.
- `identity.oidc.OidcLoginService.complete(String issuer, String subject, Set<String> amr, HttpServletRequest, HttpServletResponse)`: a pending link in the session → link to the session's account; else resolve the link → `LoginService.sessionOf(account)` → the D141 MFA decision (`amr` counted only when `trust-idp-mfa`) → `LoginSessions.start` → redirect to `post-login-uri`. Unlinked, inactive or support → redirect to `/backoffice/login?error=oidc`, audited `OIDC_LOGIN_REFUSED` (issuer only).

**Endpoints:**

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `GET /api/v1/auth/oidc` | open | — | `{enabled, displayName}` |
| `GET /api/v1/auth/oidc/authorize` | open | — | 302 to the IdP; 404 when disabled; 503 `OIDC_UNAVAILABLE` |
| `GET /api/v1/auth/oidc/callback` | open (`state`-checked by Spring) | — | 302 to `post-login-uri` or the error page |
| `POST /api/v1/auth/oidc/link` | ACCOUNT session; fresh second factor when mode ≠ OFF | — | `{authorizeUri}`; 403 `MFA_STEP_UP_REQUIRED`; 409 `EXTERNAL_IDENTITY_EXISTS`; 422 `SUPPORT_ACCOUNT_CANNOT_LINK` |
| `GET /api/v1/auth/oidc/link`, `DELETE /api/v1/auth/oidc/link` | ACCOUNT session | — | Own link; audited `OIDC_UNLINKED` |
| `GET /api/v1/backoffice/users/{id}/external-identity`, `DELETE …` | `USER_MANAGE` | — | 404 when none |

**Tests** (a stub IdP in the test: a JDK `HttpServer` serving discovery, JWKS and a token endpoint, signing ID tokens with Nimbus, which the starter brings): `OidcDisabledTest` (404; no outbound connection at startup: the stub records none), `OidcLoginTest` (A61: linked → session with exactly the local authorities; IdP `groups`/`roles` claims ignored; `last_login_at` set), `OidcUnlinkedRefusedTest` (no account created; audited), `OidcInactiveAndSupportRefusedTest`, `OidcIdpDownTest` (stub stopped: backend context starts, `/authorize` 503, password login 200), `OidcLinkFlowTest` (link needs a step-up when mode ≠ OFF; links the session's account), `ExternalIdentityLinkRaceTest`, `OidcMfaPolicyTest` (mode `ALL_BACKOFFICE`: a federated login lands in `MFA_PENDING`; with `trust-idp-mfa` and `amr = [mfa]` it is VERIFIED), `OidcStateTest` (a callback with a foreign `state` is refused).

**Acceptance:** A61.

### S60 — Cash-drawer kick
**Write scope:** `settlement/service/PaymentRecorder.java`, `settlement/service/RefundService.java` (publish only); new `production/service/DrawerKickListener.java`, `production/service/DrawerKickService.java`, `production/controller/DrawerKickController.java`; the `DRAWER_KICK` handling in `production/model/TicketSource.java`, `production/service/PrintQueue.java` (claim rules) and `production/service/PrintQueueAlertSource.java` (exclusion); `db/migration/production/V7009`.

**Migrations:** `V7009__production_drawer_kick.sql`: `production_ticket.source_type` CHECK adds `'DRAWER_KICK'`; CHECK `(source_type = 'DRAWER_KICK') = (content_format = 'KICK')`.

**Contracts:**
- Settlement: after the payment (or refund) with a `CASH`-kind method and a non-null terminal is recorded, `CashDrawerKickRequested(PAYMENT|REFUND, id, storeId, terminalId)` is published in the same transaction.
- `DrawerKickListener` (`@ApplicationModuleListener`) → `DrawerKickService.kick(sourceType, sourceId, storeId, terminalId)`: the terminal's receipt printer; `drawer_pin` NULL → nothing (INFO); else ticket `DRAWER_KICK` (dedupe `(DRAWER_KICK, sourceId, 'DRAWER')`) and one job.
- `PrintQueue.claimDue`: a `DRAWER_KICK` job is never redirected or held; printer not NORMAL → FAILED `PRINTER_NOT_NORMAL`; `created_at` older than 30 s → FAILED `KICK_EXPIRED`; `NOT_CONNECTED` → FAILED at once (no retry). Payload: `ESC p m t1 t2` only.
- `PrintQueueAlertSource` excludes `DRAWER_KICK`.

**Endpoints:**

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `POST /api/v1/pos/drawer-kicks {reason}` | `CASH_OPERATE` (POS, session terminal) | yes | 201 with the job; 422 `REASON_REQUIRED`, `NO_RECEIPT_PRINTER`, `NO_CASH_DRAWER`; audited `DRAWER_OPENED_MANUALLY` |

**Tests:** `DrawerKickBytesTest` (pin 2 and 5, pulse → exact `1B 70 m t1 FA`), `DrawerKickOncePerPaymentTest` (A66: cash payment → one kick; forced redelivery → still one; card → none; cash refund → one), `DrawerKickNoRetryTest` (printer down → FAILED, attempts 1), `DrawerKickHoldTest` (HOLD → FAILED at once, never held), `DrawerKickExpiresTest` (fixed `Clock`), `ManualDrawerKickTest` (permission, reason, audit, idempotent), `SettlementPublishesKickTest` (settlement module test: event published once per cash payment, none for credit or card).

**Acceptance:** A66.

---

## S63 — Integrate (alone, after every slice has merged)

**Write scope:**
- `src/test/.../e2e`
- `docs/architecture/modules/` (`-Dmodulith.docs=true`)
- `CONTEXT.md`, `docs/architecture/backend-conventions.md`, `docs/adr/0034…0038` (status *Proposed* → *Accepted*), `docs/adr/0008` (status line: "MFA part superseded by 0035"), `docs/adr/README.md`
- `docs/qa/test-plans/printer-hardware-b5.md`, `docs/qa/acceptance-matrix.md`
- `deploy/load/**` (step-up-free BackOffice reads in the load plan only if needed)

**Deliverables:**
- **End-to-end journey** over HTTP with real security and `TcpFakePrinter`:
  1. Mode `SENSITIVE`, `lan-cidrs` = the test's LAN: the bootstrap admin enrols from the LAN, publishes a catalog for store A after a step-up, and resets the MFA of a second admin; that admin enrols remotely with the enrolment code.
  2. Store B excludes the coffee and an all-stores macro containing it; B's release lists both, A's keeps them; a POS in B gets `ITEM_NOT_IN_CATALOG` for the coffee; a delivery zone in B whose fee item is excluded → 422 `FEE_ITEM_NOT_PUBLISHED` at delivery opening.
  3. Printers with an 80/48 EPSON_TM_80 profile (kitchen) and a 58/32 GENERIC_58 profile with a drawer (cashier): a round prints styled, wrapped bytes; a cash payment kicks the drawer once; a card payment does not; paper out on the kitchen fake → RETRY_WAIT `PAPER_OUT`, then the alert list shows `PRINT_QUEUE_STALLED` or `PRINT_JOBS_ATTENTION`.
  4. The seven alerts are provoked and seen in `GET /backoffice/alerts`; Prometheus text scraped with the token.
  5. OIDC against the stub IdP: an administrator links, logs in federated, lands in `MFA_PENDING` (mode `ALL_BACKOFFICE` for this step), verifies; the stub is stopped and password login still works.
- **Rules versus metrics** (A64): `AlertRulesReferenceExistingMetricsTest` parses `deploy/monitoring/alert-rules.yml` and checks every metric name it uses appears in a scrape of the running test backend after the journey.
- **Restart additions** (D90 pattern): (l) a crash after a cash payment commit, before the kick listener → one kick after restart, or none if older than 30 s (`KICK_EXPIRED`), never two.
- **B5 checklist** `docs/qa/test-plans/printer-hardware-b5.md`: per model: test page (code page, width ruler, styles), a kitchen round, a 58 mm receipt, each cut mode, paper out with the probe on and off, cover open, power off mid-job (UNKNOWN), drawer kick pulse, network unplugged; a results table. **Running it is the human checkpoint A67.**
- ADRs 0034–0038 accepted; `CONTEXT.md` adds: second factor / TOTP ("segundo fator"), recovery code ("código de recuperação"), enrolment code ("código de ativação"), step-up ("reautenticação"), remote access ("acesso remoto"), external identity ("identidade externa"), StoreItemExclusion ("artigo não disponível na loja"), operational alert ("alerta operacional"), backup status file, printer profile ("perfil de impressora"), drawer kick ("abertura de gaveta"). `backend-conventions.md`: the step-up rule, `shared.ops`, the no-secret-in-idempotency rule, the alert label rule, the console audit function.
- **Acceptance:** A57–A68 end to end (A67 as a recorded human checkpoint).

---

## Blocked-by graph and parallel groups

```
S55 Platform prefactor (alone)
 ├─► S56 Identity MFA ──────────────► S57 OIDC federation ──────┐
 ├─► S58 Catalog store exclusions ──────────────────────────────┤
 ├─► S59 Production ESC/POS driver ─► S60 Cash-drawer kick ─────┤
 ├─► S61 Alerts (backend) ──────────────────────────────────────┤
 └─► S62 Deploy monitoring and wiring ──────────────────────────┴─► S63 Integrate
```

| group | slices (parallel) | why the write sets are disjoint |
|---|---|---|
| 0 | S55 alone | `pom.xml`, `access/`, new `shared/ops/**`, one settlement event record, the error mapping, architecture tests |
| 1 | S56 ∥ S58 ∥ S59 ∥ S61 ∥ S62 | `identity/` (no `oidc/`)+V3013+audit V1105+`deploy/mfa-break-glass.sh`; `catalog/`+V5006; `production/` (no drawer-kick files)+V7008; new files in `config/alerts/**`, `shared/events/`, `settlement/service/InDoubtAlertSource.java`, `cash/service/CashDiscrepancyAlertSource.java`, three export call sites, `application.yaml`; `deploy/` scripts, Compose, README, monitoring |
| 2 | S57 ∥ S60 | `identity/oidc/**`+V3014+`config/SecurityConfig.java`; settlement `PaymentRecorder`/`RefundService` + the production drawer-kick files+V7009 |
| 3 | S63 alone | tests, docs, ADR statuses, QA checklist |

S57 is serial after S56 because both change the login path (`LoginService`, `LoginSessions`, MFA states). S60 is serial after S59 because both change `PrintQueue` and `TicketSource`. S61 touches `InDoubtGauge` (a read accessor) and S60 touches other settlement files only.

Rules, as in R1–R4:
- Only S55 edits `pom.xml`; only S62 edits Compose, `.env.example` and the deploy README; only S61 edits `application.yaml`.
- Each slice has its own `rtk mvn test -Dtest=…` that can go red on its own (deploy slices: their script's `--self-test`).
- Slices code against the contracts above and never read another module's tables.
- Within a group, merge in ascending slice number, so Flyway versions apply in order on persisted dev databases.
- No slice is interactive. Each PR targets `main`; none is stacked.

## Acceptance criteria → slices

| criterion (r5-decisions §11) | slices |
|---|---|
| A57, A58, A60 | S56, S63 |
| A59 | S55, S56, S63 |
| A61 | S57, S63 |
| A62 | S58, S63 |
| A63 | S59, S61, S63 |
| A64 | S62, S63 |
| A65 | S59, S63 |
| A66 | S55, S60, S63 |
| A67 | S63 (checklist), then a person (B5) |
| A68 | S55, S63 |
