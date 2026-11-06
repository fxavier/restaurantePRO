# BUG-0002: a provider statement's period uses calendar midnights, not the store's business days

- **Status:** fixed in 8c1bd64. `StatementImportService.target` computes the period as `[BusinessDays.startOf(from), BusinessDays.startOf(to + 1))` in the store's zone and with its cutoff; fiscal's private copy is deleted in favour of the now-public `organization.BusinessDays.startOf`. The repro is now `StatementPeriodIsBusinessDaysTest` and runs in the suite; `R3AcceptanceTest` no longer widens its period.

- **Severity:** Medium. Reconciliation (D78) of any store whose business day crosses midnight (the normal case: the cutoff is e.g. 05:00) refuses, or misclassifies, the payments taken between midnight and the cutoff: importing "day D" rejects the provider's lines of the night of D (422), and a period that includes D+1 lists D's late payments under the wrong day (MISSING_PROVIDER noise). No money fact changes (reconciliation never writes money), so it is not High.
- **Breaks:** r3-decisions D78 (a batch per statement: store, method, **period**), P37/D85 ("the cash day for money": a payment belongs to its business day), spec §8.3 business day "que pode atravessar a meia-noite". The code's own contract: `StatementImportService.Target` — "The statement's store, method and business days, and those days as instants [start, end)".
- **Environment:** branch `s33-integrate` at `511e434`, macOS 15.3 x86_64, Java 25.0.4.1, PostgreSQL 18.6 in Docker; store zone Africa/Maputo.
- **Owner:** `developer` (settlement reconciliation). The S22 backlog item "make `BusinessDays.startOf` public and delete fiscal's copy" is the natural shared helper for the fix.

## Steps to reproduce

1. A store in Africa/Maputo with business-day cutoff 05:00.
2. At 00:30 local time on calendar date D+1 (business day D), take a card payment (EXTERNAL_TERMINAL) with authorization code `A-BD1`; its `settlement.payment.business_date` is D.
3. `POST /api/v1/backoffice/reconciliation/batches?storeId=…&methodCode=CARD&periodFrom=D&periodTo=D` with the canonical CSV line of that payment (`occurred_at` = its confirmation instant, amount 25.00, authorization `A-BD1`).

Or: `rtk mvn test -Dtest=StatementPeriodIsBusinessDaysRepro -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition` (the clock is moved to 00:30, never slept).

## Expected

201 with one MATCHED entry: the period D..D is business day D, which runs from D 05:00 to D+1 05:00 in the store's zone, and the payment and its provider line both belong to it.

## Actual

422 `STATEMENT_INVALID`, nothing imported:

```json
{"detail":"The statement has 2 error(s); nothing was imported.","status":422,"code":"STATEMENT_INVALID",
 "errors":[{"line":2,"field":"occurred_at","message":"The line is not of the statement's period"}, ...]}
```

(The body above is from `R3AcceptanceTest`'s first run, where day 1's card payment at about 05:40 local fell before the store's cutoff of about 17:00.) Repro run:

```
[ERROR]   StatementPeriodIsBusinessDaysRepro.aPaymentAfterMidnightBeforeTheCutoffIsMatchedByItsBusinessDaysStatement:77 Status expected:<201> but was:<422>
```

Cause: `StatementImportService.target(...)` builds the instants as `from.atStartOfDay(zone)` and `to.plusDays(1).atStartOfDay(zone)`, ignoring `StoreSummary.businessDayCutoff()`. The existing `StatementImportTest` imports with periods of yesterday..tomorrow, which hides it.

## Reproducing test

`backend/src/test/java/com/xavier/backend/settlement/reconciliation/StatementPeriodIsBusinessDaysTest.java` (was `StatementPeriodIsBusinessDaysRepro`, disabled; enabled and renamed with the fix). `R3AcceptanceTest` imports day 1..day 1.

## Open question (still open; the window and business_date now agree)

Should the candidate payments for MISSING_PROVIDER be selected by `payment.business_date` (P37) or by the same instant window? They should agree once the window follows the cutoff; confirm which is authoritative.
