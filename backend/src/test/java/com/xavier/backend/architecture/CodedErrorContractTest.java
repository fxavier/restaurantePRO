package com.xavier.backend.architecture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.ClosingStaleException;
import com.xavier.backend.cash.exception.RefundMismatchException;
import com.xavier.backend.catalog.exception.ReleaseValidationFailedException;
import com.xavier.backend.catalog.model.dto.ReleaseValidationError;
import com.xavier.backend.dining.exception.IllegalOccupationStateException;
import com.xavier.backend.dining.exception.StillOccupiedException;
import com.xavier.backend.dining.exception.TableOccupiedException;
import com.xavier.backend.dining.exception.TableUnavailableException;
import com.xavier.backend.fiscal.FiscalIssueFailedException;
import com.xavier.backend.fiscal.exception.DraftMismatchException;
import com.xavier.backend.identity.exception.LoginThrottledException;
import com.xavier.backend.identity.exception.PinChangeNotRequiredException;
import com.xavier.backend.identity.exception.PinUnchangedException;
import com.xavier.backend.orders.exception.OrderConflictException;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.production.exception.ProductionRule;
import com.xavier.backend.production.exception.ProductionRuleViolationException;
import com.xavier.backend.production.model.Printer;
import com.xavier.backend.reporting.exception.ExportParamsException;
import com.xavier.backend.reporting.exception.RebuildRunningException;
import com.xavier.backend.reporting.exception.ReportRangeException;
import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationConflictException;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationRuleException;
import com.xavier.backend.settlement.reconciliation.model.dto.StatementError;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The exact client contract (status, {@code code}, {@code detail}, extra properties, headers) of every module error
 * that carries a stable code. Written against the module-local ErrorResponseException workarounds and kept green
 * across their move onto the shared {@code CodedException} types (S7b), so a client sees no difference. Strict:
 * no property may appear or disappear.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CodedErrorContractTest.Probe.class})
@WithMockUser
class CodedErrorContractTest {

    static final java.util.Currency MZN = java.util.Currency.getInstance("MZN");
    static final UUID ID = UUID.fromString("0190a000-0000-7000-8000-0000000000c1");
    static final UUID OTHER = UUID.fromString("0190a000-0000-7000-8000-0000000000c2");
    static final String PATH = "/api/v1/backoffice/error-contract/";

    static final Map<String, Supplier<RuntimeException>> ERRORS = Map.ofEntries(
            Map.entry("release-invalid", () -> new ReleaseValidationFailedException(List.of(
                    new ReleaseValidationError("ITEM_WITHOUT_PRICE", ID, "BURGER", "Item BURGER has no price")))),
            Map.entry("table-occupied", () -> TableOccupiedException.table(ID)),
            Map.entry("order-seated", () -> TableOccupiedException.order(ID)),
            Map.entry("store-mismatch", () -> TableUnavailableException.storeMismatch(ID)),
            Map.entry("table-inactive", () -> TableUnavailableException.inactive(ID)),
            Map.entry("table-locked", () -> TableUnavailableException.locked(ID)),
            Map.entry("occupation-closed", () -> IllegalOccupationStateException.closed(ID)),
            Map.entry("no-open-occupation", () -> IllegalOccupationStateException.noOpenOccupation(ID)),
            Map.entry("still-occupied", () -> new StillOccupiedException("Table", ID)),
            Map.entry("pin-unchanged", PinUnchangedException::new),
            Map.entry("pin-change-not-required", PinChangeNotRequiredException::new),
            Map.entry("login-throttled", () -> new LoginThrottledException(Duration.ofSeconds(90))),
            Map.entry("cash-rule", () -> new CashRuleViolationException(CashRule.DAY_NOT_OPEN,
                    "The cash day is not open.")),
            Map.entry("fiscal-pending", () -> new FiscalIssueFailedException(ID,
                    new IllegalStateException("adapter offline"))),
            Map.entry("draft-mismatch-lines", () -> DraftMismatchException.lines(ID)),
            Map.entry("draft-mismatch-on-account", () -> DraftMismatchException.onAccount(ID)),
            Map.entry("order-not-open", () -> OrderConflictException.notOpen(ID)),
            Map.entry("line-not-draft", () -> OrderConflictException.lineNotDraft(ID)),
            Map.entry("catalog-changed", () -> OrderConflictException.catalogChanged(3, 4)),
            Map.entry("order-in-settlement", () -> OrderRuleException.inSettlement(ID)),
            Map.entry("quantity-exceeds-net", () -> OrderRuleException.quantityExceedsNet(ID, new BigDecimal("1.500"))),
            Map.entry("payment-in-doubt", () -> SettlementConflictException.inDoubt("order", ID, OTHER)),
            Map.entry("document-already-issued", () -> SettlementRuleException.documentAlreadyIssued(ID)),
            Map.entry("charge-posted", () -> SettlementRuleException.chargePosted(ID)),
            Map.entry("debit-not-open", () -> SettlementRuleException.debitNotOpen(ID)),
            Map.entry("settlement-amount-exceeds-debt", () -> SettlementRuleException.amountExceedsDebt(
                    com.xavier.backend.shared.Money.of(new BigDecimal("40.00"), MZN),
                    com.xavier.backend.shared.Money.of(new BigDecimal("37.50"), MZN))),
            Map.entry("credit-charge-exists", () -> SettlementConflictException.chargeExists("order", ID, OTHER)),
            Map.entry("illegal-charge-state", () -> SettlementConflictException.illegalChargeState(ID, "POSTED",
                    "be cancelled")),
            Map.entry("illegal-attempt-state", () -> SettlementConflictException.illegalState(ID,
                    AttemptStatus.FAILED, "confirm")),
            // The messages of these two are built inline by PaymentAttemptService and OrderSettlementSteps; the same
            // format is repeated here so the pinned body is the one clients see.
            Map.entry("amount-exceeds-balance", () -> new SettlementRuleException(
                    SettlementRule.AMOUNT_EXCEEDS_BALANCE,
                    "The amount 60.0000 is more than the balance 40.0000 of the order.",
                    Map.of("balance", new BigDecimal("40.0000")))),
            Map.entry("not-settled", () -> new SettlementRuleException(SettlementRule.NOT_SETTLED,
                    "Order " + ID + " is not settled: 60.0000 paid of 100.0000 due.",
                    Map.of("balance", new BigDecimal("40.0000")))),
            Map.entry("session-not-open", () -> new CashRuleViolationException(CashRule.SESSION_NOT_OPEN,
                    "The cash session " + ID + " is closed.")),
            Map.entry("printer-ignored", () -> new ProductionRuleViolationException(ProductionRule.PRINTER_IGNORED,
                    "Printer K1 is in IGNORE: change its mode to print again")),
            Map.entry("order-frozen", () -> OrderRuleException.frozen(ID)),
            Map.entry("order-self-approval", OrderRuleException::selfApproval),
            Map.entry("order-document-issued", () -> OrderRuleException.documentIssued(ID)),
            Map.entry("printer-redirect-invalid", () -> Printer.invalidRedirect("A printer cannot redirect to itself")),
            // As above, these details are built inline (AttemptResolutionService, OrderSettlementSteps,
            // CashCloseService.refused, CashCoordinationService, RoutingService.requireUnique) and repeated here.
            Map.entry("settlement-self-approval", () -> new SettlementRuleException(SettlementRule.SELF_APPROVAL,
                    "The approver of a reclassification cannot be the operator.")),
            Map.entry("nothing-to-bill", () -> new SettlementRuleException(SettlementRule.NOTHING_TO_BILL,
                    "Order " + ID + " has nothing to bill: cancel it instead.")),
            Map.entry("sale-fiscal-pending", () -> new SettlementRuleException(SettlementRule.FISCAL_PENDING,
                    "The sale document of order " + ID + " is not issued yet.")),
            Map.entry("sale-fiscal-pending-document", () -> new SettlementRuleException(SettlementRule.FISCAL_PENDING,
                    "The sale document of order " + ID + " is not issued yet.", Map.of("documentId", OTHER))),
            Map.entry("day-open-orders", () -> new SettlementRuleException(SettlementRule.OPEN_ORDERS,
                    "The cash day cannot close operationally: an order of the day is still open.")),
            Map.entry("day-open-sessions", () -> new SettlementRuleException(SettlementRule.OPEN_SESSIONS,
                    "The cash day cannot close operationally: a cash session of the day is still open.")),
            Map.entry("day-fiscal-pending", () -> new SettlementRuleException(SettlementRule.FISCAL_PENDING,
                    "The cash day cannot close operationally: a fiscal document is still pending.")),
            Map.entry("cash-open-sessions", () -> new CashRuleViolationException(CashRule.OPEN_SESSIONS,
                    "The cash day 2026-11-05 still has open sessions.")),
            Map.entry("cash-closing-stale", () -> new ClosingStaleException(ID, "CASH")),
            Map.entry("cash-closing-earlier-round", () -> ClosingStaleException.earlierRound(ID, 0, 1)),
            Map.entry("cash-refund-mismatch", () -> new RefundMismatchException(ID)),
            Map.entry("cash-day-closing-only", () -> new CashRuleViolationException(CashRule.DAY_CLOSING_ONLY,
                    "The cash day 2026-11-04 was reopened: it takes corrections only, not new sessions.")),
            Map.entry("cash-self-approval", () -> new CashRuleViolationException(CashRule.SELF_APPROVAL,
                    "The approver must be another employee than the operator.")),
            Map.entry("cash-closing-already-approved", () -> new CashRuleViolationException(
                    CashRule.CLOSING_ALREADY_APPROVED, "The closing of this register for 2026-11-04 is approved: "
                    + "close the day financially, reopen it, and adjust in the next round.")),
            Map.entry("duplicate-route", () -> new ProductionRuleViolationException(ProductionRule.DUPLICATE_ROUTE,
                    "The destination KITCHEN appears twice")),
            // R3 settlement (S28): partitions, the residual and the settled void
            Map.entry("quantity-already-partitioned", () -> SettlementRuleException.quantityAlreadyPartitioned(ID,
                    new BigDecimal("1.500"))),
            Map.entry("settlement-quantity-exceeds-net", () -> SettlementRuleException.quantityExceedsNet(ID,
                    new BigDecimal("2.000"), new BigDecimal("3"))),
            Map.entry("quantity-partitioned", () -> SettlementRuleException.quantityPartitioned(ID, BigDecimal.ONE)),
            Map.entry("split-rounding-limit", () -> SettlementRuleException.splitRoundingLimit(ID,
                    new BigDecimal("0.843"), Money.of("3.55", MZN), Money.of("0.49", MZN))),
            Map.entry("settlement-order-frozen", () -> SettlementRuleException.orderFrozen(ID)),
            Map.entry("amount-exceeds-residual", () -> SettlementRuleException.amountExceedsResidual(ID,
                    Money.of("20.01", MZN), Money.of("20.00", MZN))),
            Map.entry("nothing-to-split", () -> SettlementRuleException.nothingToSplit(ID, Money.of("0.01", MZN), 2)),
            Map.entry("partition-in-use", () -> SettlementRuleException.partitionInUse(ID)),
            Map.entry("partition-not-invoiceable", () -> SettlementRuleException.partitionNotInvoiceable(ID, "LIVE",
                    "AMOUNT")),
            Map.entry("void-exceeds-unpaid", () -> SettlementRuleException.voidExceedsUnpaid(ID,
                    Money.of("12.50", MZN), Money.of("20.00", MZN), Money.of("5.00", MZN))),
            Map.entry("correction-requires-fiscal-adapter", () -> SettlementRuleException
                    .correctionRequiresFiscalAdapter("Order " + ID + " has its document")),
            Map.entry("void-self-approval", SettlementRuleException::voidSelfApproval),
            Map.entry("illegal-partition-state", () -> SettlementConflictException.illegalPartitionState(ID,
                    "CANCELLED", "be cancelled")),
            Map.entry("partition-in-doubt", () -> SettlementConflictException.inDoubt("partition", ID, OTHER)),
            // R3 settlement (S30): payments in doubt, refunds
            Map.entry("reclassify-too-early", () -> SettlementRuleException.reclassifyTooEarly(ID,
                    Duration.ofMinutes(15), true, 0)),
            Map.entry("reclassify-too-early-no-provider", () -> SettlementRuleException.reclassifyTooEarly(ID,
                    Duration.ofHours(24), false, 0)),
            Map.entry("refund-exceeds-refundable", () -> SettlementRuleException.refundExceedsRefundable(ID,
                    Money.of("5.01", MZN), Money.of("5.00", MZN))),
            Map.entry("external-reference-required", () -> SettlementRuleException.externalReferenceRequired("CARD")),
            Map.entry("refund-self-approval", SettlementRuleException::refundSelfApproval),
            Map.entry("statement-invalid", () -> ReconciliationRuleException.statementInvalid(List.of(
                    new StatementError(2, "amount", "More than zero")))),
            Map.entry("no-statement-source", () -> ReconciliationRuleException.noStatementSource("CARD")),
            Map.entry("statement-already-imported", () -> ReconciliationConflictException.alreadyImported(ID)),
            Map.entry("entry-resolved", () -> ReconciliationConflictException.entryResolved(ID)),
            Map.entry("statement-imported-elsewhere", ReconciliationConflictException::alreadyImportedElsewhere),
            Map.entry("statement-source-timeout", () -> com.xavier.backend.settlement.reconciliation.exception
                    .ReconciliationUnavailableException.sourceTimeout("WALLET")),
            Map.entry("range-reversed", () -> ReportRangeException.reversed(LocalDate.of(2026, 11, 5),
                    LocalDate.of(2026, 11, 4))),
            Map.entry("range-too-large", () -> ReportRangeException.tooLarge(366)),
            Map.entry("rebuild-running", () -> new RebuildRunningException(ID)),
            Map.entry("invalid-export-params", () -> new ExportParamsException("groupBy")));

    @Autowired
    MockMvc mvc;

    @Test
    void releaseValidationFailed() throws Exception {
        expect("release-invalid", 422, null, "The catalog cannot be published: 1 problem(s).",
                ",\"errors\":[{\"code\":\"ITEM_WITHOUT_PRICE\",\"itemId\":\"" + ID
                        + "\",\"itemCode\":\"BURGER\",\"message\":\"Item BURGER has no price\"}]");
    }

    @Test
    void diningConflicts() throws Exception {
        expect("table-occupied", 409, "TABLE_OCCUPIED", "Table " + ID + " is occupied.", "");
        expect("order-seated", 409, "ORDER_SEATED", "Order " + ID + " already occupies a table.", "");
        expect("occupation-closed", 409, "OCCUPATION_CLOSED", "Table occupation " + ID + " is already closed.", "");
        expect("no-open-occupation", 409, "NO_OPEN_OCCUPATION", "Order " + ID + " has no open table occupation.",
                "");
    }

    @Test
    void diningRules() throws Exception {
        expect("store-mismatch", 422, "STORE_MISMATCH", "Table " + ID + " is not in this store.", "");
        expect("table-inactive", 422, "TABLE_INACTIVE", "Table " + ID + " or its area is inactive.", "");
        expect("table-locked", 422, "TABLE_LOCKED", "Table " + ID + " is locked.", "");
        expect("still-occupied", 422, "OCCUPIED",
                "Table " + ID + " has an open table occupation and cannot be deactivated.", "");
    }

    @Test
    void identityRules() throws Exception {
        expect("pin-unchanged", 422, "PIN_UNCHANGED", "The new PIN must differ from the one the manager set.", "");
        expect("pin-change-not-required", 422, "PIN_CHANGE_NOT_REQUIRED",
                "Only a POS session whose PIN was reset by a manager sets a new PIN here.", "");
    }

    @Test
    void loginThrottledIs429WithRetryAfter() throws Exception {
        expect("login-throttled", 429, "LOGIN_THROTTLED", "Too many login attempts. Try again later.", "");
        mvc.perform(get(PATH + "login-throttled")).andExpect(header().string("Retry-After", "90"));
    }

    @Test
    void cashRule() throws Exception {
        expect("cash-rule", 422, "DAY_NOT_OPEN", "The cash day is not open.", "");
    }

    @Test
    void cashR3Codes() throws Exception {
        expect("cash-closing-stale", 409, "CLOSING_STALE", "The expected CASH amount of cash closing " + ID
                + " changed since it was drafted: reject it and draft it again.", "");
        expect("cash-closing-earlier-round", 409, "CLOSING_STALE", "Cash closing " + ID
                + " is of round 0, but the day is in round 1: draft a closing of the current round.", "");
        expect("cash-refund-mismatch", 409, "REFUND_MISMATCH", "Refund " + ID
                + " was already recorded with another session, method or amount.", "");
        expect("cash-day-closing-only", 422, "DAY_CLOSING_ONLY",
                "The cash day 2026-11-04 was reopened: it takes corrections only, not new sessions.", "");
        expect("cash-self-approval", 422, "SELF_APPROVAL",
                "The approver must be another employee than the operator.", "");
        expect("cash-closing-already-approved", 422, "CLOSING_ALREADY_APPROVED",
                "The closing of this register for 2026-11-04 is approved: close the day financially, reopen it, "
                        + "and adjust in the next round.", "");
    }

    @Test
    void cashSessionNotOpen() throws Exception {
        expect("session-not-open", 422, "SESSION_NOT_OPEN", "The cash session " + ID + " is closed.", "");
    }

    @Test
    void orderConflicts() throws Exception {
        expect("order-not-open", 409, "ORDER_NOT_OPEN", "Order " + ID + " is not open.", "");
        expect("line-not-draft", 409, "LINE_NOT_DRAFT", "Line " + ID + " is not a draft line of this order.", "");
        expect("catalog-changed", 409, "CATALOG_CHANGED",
                "The catalog changed (version 3 → 4): review the prices and confirm again.", ",\"catalogVersion\":4");
    }

    @Test
    void orderRules() throws Exception {
        expect("order-in-settlement", 422, "ORDER_IN_SETTLEMENT",
                "Order " + ID + " has a payment under way: nothing may leave its bill.", "");
        expect("quantity-exceeds-net", 422, "QUANTITY_EXCEEDS_NET", "Line " + ID + " holds only 1.500.", "");
    }

    @Test
    void orderFrozenAndSelfApproval() throws Exception {
        expect("order-frozen", 422, "ORDER_FROZEN", "Order " + ID + " is frozen for its invoice.", "");
        expect("order-self-approval", 422, "SELF_APPROVAL",
                "The approver must be another employee than the operator.", "");
    }

    /** P27 (R3, S23): a subtotal once the order's document exists. */
    @Test
    void orderDocumentIssued() throws Exception {
        expect("order-document-issued", 422, "DOCUMENT_ISSUED",
                "Order " + ID + " has its document: reprint it as a copy instead of a subtotal.", "");
    }

    @Test
    void settlementSelfApprovalAndNothingToBill() throws Exception {
        expect("settlement-self-approval", 422, "SELF_APPROVAL",
                "The approver of a reclassification cannot be the operator.", "");
        expect("nothing-to-bill", 422, "NOTHING_TO_BILL", "Order " + ID + " has nothing to bill: cancel it instead.",
                "");
    }

    /** OrderSettlementSteps.fiscalPending: {@code documentId} only when a SALE draft exists. */
    @Test
    void saleFiscalPendingHasAnOptionalDocumentId() throws Exception {
        expect("sale-fiscal-pending", 422, "FISCAL_PENDING",
                "The sale document of order " + ID + " is not issued yet.", "");
        expect("sale-fiscal-pending-document", 422, "FISCAL_PENDING",
                "The sale document of order " + ID + " is not issued yet.", ",\"documentId\":\"" + OTHER + "\"");
    }

    @Test
    void operationalDayCloseRefusals() throws Exception {
        expect("day-open-orders", 422, "OPEN_ORDERS",
                "The cash day cannot close operationally: an order of the day is still open.", "");
        expect("day-open-sessions", 422, "OPEN_SESSIONS",
                "The cash day cannot close operationally: a cash session of the day is still open.", "");
        expect("day-fiscal-pending", 422, "FISCAL_PENDING",
                "The cash day cannot close operationally: a fiscal document is still pending.", "");
        expect("cash-open-sessions", 422, "OPEN_SESSIONS", "The cash day 2026-11-05 still has open sessions.", "");
    }

    @Test
    void productionRoutingRules() throws Exception {
        expect("printer-redirect-invalid", 422, "PRINTER_REDIRECT_INVALID", "A printer cannot redirect to itself", "");
        expect("duplicate-route", 422, "DUPLICATE_ROUTE", "The destination KITCHEN appears twice", "");
    }

    @Test
    void settlementConflicts() throws Exception {
        expect("payment-in-doubt", 409, "PAYMENT_IN_DOUBT", "A payment attempt of order " + ID
                + " is still open or in doubt; resolve it first.", ",\"attemptId\":\"" + OTHER + "\"");
        expect("illegal-attempt-state", 409, "ILLEGAL_ATTEMPT_STATE",
                "Payment attempt " + ID + " is FAILED and cannot confirm.", "");
    }

    @Test
    void settlementRules() throws Exception {
        expect("amount-exceeds-balance", 422, "AMOUNT_EXCEEDS_BALANCE",
                "The amount 60.0000 is more than the balance 40.0000 of the order.", ",\"balance\":40.0000");
        expect("not-settled", 422, "NOT_SETTLED", "Order " + ID + " is not settled: 60.0000 paid of 100.0000 due.",
                ",\"balance\":40.0000");
    }

    @Test
    void settlementPartitionAndVoidRules() throws Exception {
        expect("quantity-already-partitioned", 422, "QUANTITY_ALREADY_PARTITIONED",
                "Only 1.5 of line " + ID + " is not in a partition yet.",
                ",\"lineId\":\"" + ID + "\",\"available\":1.500");
        expect("settlement-quantity-exceeds-net", 422, "QUANTITY_EXCEEDS_NET",
                "Line " + ID + " has 2 billable, less than 3.", ",\"lineId\":\"" + ID + "\"");
        expect("quantity-partitioned", 422, "QUANTITY_PARTITIONED", "Only 1 of line " + ID
                + " is in no partition: cancel the partition before voiding more.",
                ",\"lineId\":\"" + ID + "\",\"available\":1");
        expect("split-rounding-limit", 422, "SPLIT_ROUNDING_LIMIT", "Line " + ID + " cannot be split this way: a "
                + "share of 0.843 would carry 3.55 (tax 0.49), beyond the rounding a document allows. Cancel or "
                + "split it differently.", ",\"lineId\":\"" + ID + "\"");
        expect("settlement-order-frozen", 422, "ORDER_FROZEN",
                "Order " + ID + " has its document draft: its split can no longer change.", "");
        expect("amount-exceeds-residual", 422, "AMOUNT_EXCEEDS_RESIDUAL",
                "The partition of 20.01 is more than the residual 20.00 of order " + ID + ".",
                ",\"residual\":20.00");
        expect("nothing-to-split", 422, "NOTHING_TO_SPLIT",
                "The residual 0.01 of order " + ID + " cannot be split in 2 positive parts.", "");
        expect("partition-in-use", 422, "PARTITION_IN_USE", "Bill partition " + ID
                + " has a payment, an open attempt or a document: it cannot be cancelled.", "");
        expect("partition-not-invoiceable", 422, "PARTITION_NOT_INVOICEABLE",
                "Only a LIVE ITEMS partition is invoiced on its own; " + ID + " is LIVE AMOUNT.", "");
        expect("void-exceeds-unpaid", 422, "VOID_EXCEEDS_UNPAID", "The void would leave 12.50 due on order " + ID
                + ", less than the 20.00 paid or being paid: refund first.", ",\"unpaid\":5.00");
        expect("correction-requires-fiscal-adapter", 422, "CORRECTION_REQUIRES_FISCAL_ADAPTER", "Order " + ID
                + " has its document: correcting invoiced quantities needs the market's fiscal adapter.", "");
        expect("void-self-approval", 422, "SELF_APPROVAL", "The approver of a void cannot be the operator.", "");
        expect("illegal-partition-state", 409, "ILLEGAL_PARTITION_STATE",
                "Bill partition " + ID + " is CANCELLED and cannot be cancelled.", "");
        expect("partition-in-doubt", 409, "PAYMENT_IN_DOUBT", "A payment attempt of partition " + ID
                + " is still open or in doubt; resolve it first.", ",\"attemptId\":\"" + OTHER + "\"");
    }

    @Test
    void settlementCreditRules() throws Exception {
        expect("document-already-issued", 422, "DOCUMENT_ALREADY_ISSUED", "The sale document " + ID
                + " is issued without this customer on account: it can no longer be a credit sale.",
                ",\"documentId\":\"" + ID + "\"");
        expect("charge-posted", 422, "CHARGE_POSTED", "Credit charge " + ID
                + " is posted or its document is issued: it is on the customer's account.", "");
        expect("settlement-amount-exceeds-debt", 422, "AMOUNT_EXCEEDS_DEBT",
                "The amount 40.00 is more than the open debt 37.50.", ",\"debt\":37.50");
        expect("debit-not-open", 422, "DEBIT_NOT_OPEN", "The debit " + ID
                + " is not an open debit of this customer.", ",\"debitEntryId\":\"" + ID + "\"");
        expect("credit-charge-exists", 409, "CREDIT_CHARGE_EXISTS", "The order " + ID
                + " already has a credit charge; cancel it first.", ",\"chargeId\":\"" + OTHER + "\"");
        expect("illegal-charge-state", 409, "ILLEGAL_CHARGE_STATE",
                "Credit charge " + ID + " is POSTED and cannot be cancelled.", "");
    }

    @Test
    void settlementInDoubtAndRefundRules() throws Exception {
        expect("reclassify-too-early", 422, "RECLASSIFY_TOO_EARLY", "Payment attempt " + ID
                + " may be reclassified as FAILED only once it is older than PT15M and the provider answered a status "
                + "query about it since it became UNKNOWN.", ",\"answeredCount\":0");
        expect("reclassify-too-early-no-provider", 422, "RECLASSIFY_TOO_EARLY", "Payment attempt " + ID
                + " may be reclassified as FAILED only once it is older than PT24H.", ",\"answeredCount\":0");
        expect("refund-exceeds-refundable", 422, "REFUND_EXCEEDS_REFUNDABLE", "The refund 5.01 is more than the "
                + "5.00 still refundable of payment " + ID + ".", ",\"refundable\":5.00");
        expect("external-reference-required", 422, "EXTERNAL_REFERENCE_REQUIRED", "A refund to CARD is made on the "
                + "terminal or the provider's portal first: give its reference.", "");
        expect("refund-self-approval", 422, "SELF_APPROVAL", "The approver of a refund cannot be the refunder.", "");
    }

    @Test
    void reconciliationCodes() throws Exception {
        expect("statement-invalid", 422, "STATEMENT_INVALID", "The statement has 1 error(s); nothing was imported.",
                ",\"errors\":[{\"line\":2,\"field\":\"amount\",\"message\":\"More than zero\"}]");
        expect("no-statement-source", 422, "NO_STATEMENT_SOURCE", "The provider of CARD cannot fetch statements: "
                + "import its statement file instead.", "");
        expect("statement-already-imported", 409, "STATEMENT_ALREADY_IMPORTED", "This statement was already "
                + "imported as batch " + ID + ".", ",\"batchId\":\"" + ID + "\"");
        expect("entry-resolved", 409, "ENTRY_RESOLVED", "Reconciliation entry " + ID + " is already resolved.", "");
        expect("statement-imported-elsewhere", 409, "STATEMENT_ALREADY_IMPORTED",
                "This statement was already imported.", "");
        expect("statement-source-timeout", 503, "STATEMENT_SOURCE_TIMEOUT", "The provider of WALLET did not send its "
                + "statement in time; nothing was imported. Try again later.", "");
    }

    @Test
    void productionRule() throws Exception {
        expect("printer-ignored", 422, "PRINTER_IGNORED", "Printer K1 is in IGNORE: change its mode to print again",
                "");
    }

    @Test
    void fiscalPending() throws Exception {
        expect("fiscal-pending", 422, "FISCAL_PENDING",
                "The fiscal document could not be issued and stays pending; payments are kept. Try again later.",
                ",\"documentId\":\"" + ID + "\"");
    }

    @Test
    void fiscalDraftMismatch() throws Exception {
        expect("draft-mismatch-lines", 409, "DRAFT_MISMATCH",
                "The draft document " + ID + " has other lines or totals than this command.",
                ",\"documentId\":\"" + ID + "\"");
        expect("draft-mismatch-on-account", 409, "DRAFT_MISMATCH",
                "The draft document " + ID + " is on account: it stays on account for the same customer.",
                ",\"documentId\":\"" + ID + "\"");
    }

    @Test
    void reportingCodes() throws Exception {
        expect("range-reversed", 422, "RANGE_REVERSED", "The range starts on 2026-11-05, after its end 2026-11-04.",
                "");
        expect("range-too-large", 422, "RANGE_TOO_LARGE", "The range is longer than 366 days: narrow it.",
                ",\"maxDays\":366");
        expect("rebuild-running", 409, "REBUILD_RUNNING", "A rebuild of store " + ID + " is already running.", "");
        expect("invalid-export-params", 422, "INVALID_EXPORT_PARAMS",
                "The export parameter 'groupBy' is missing or not valid for the report.", ",\"param\":\"groupBy\"");
    }

    private void expect(String name, int status, String code, String detail, String extra) throws Exception {
        String title = switch (status) {
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Content";
            case 429 -> "Too Many Requests";
            case 503 -> "Service Unavailable";
            default -> throw new IllegalArgumentException("status " + status);
        };
        String body = "{\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"instance\":\"" + PATH + name + "\""
                + (code == null ? "" : ",\"code\":\"" + code + "\"") + extra + "}";
        mvc.perform(get(PATH + name))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json(body, JsonCompareMode.STRICT));
    }

    @RestController
    static class Probe {

        @GetMapping(PATH + "{name}")
        void raise(@PathVariable String name) {
            throw ERRORS.get(name).get();
        }
    }
}
