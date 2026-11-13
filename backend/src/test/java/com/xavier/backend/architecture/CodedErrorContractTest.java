package com.xavier.backend.architecture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.StepUpRequiredException;
import com.xavier.backend.audit.exception.AuditQueryException;
import com.xavier.backend.customers.exception.CustomerRuleException;
import com.xavier.backend.customers.exception.CustomersConflictException;
import com.xavier.backend.identity.exception.IllegalSupportGrantStateException;
import com.xavier.backend.identity.exception.SupportAccountLinkedException;
import com.xavier.backend.identity.exception.SupportAccountRequiredException;
import com.xavier.backend.identity.exception.SupportGrantActiveException;
import com.xavier.backend.identity.exception.SupportGrantTooLongException;
import com.xavier.backend.production.exception.ProductionConflictException;
import com.xavier.backend.promotions.exception.PromotionConflictException;
import com.xavier.backend.promotions.exception.PromotionRuleException;
import com.xavier.backend.promotions.exception.PromotionsNotFoundException;
import com.xavier.backend.promotions.model.dto.PromotionReleaseError;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.ClosingStaleException;
import com.xavier.backend.cash.exception.RefundMismatchException;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.CatalogRuleException;
import com.xavier.backend.catalog.exception.ReleaseValidationFailedException;
import com.xavier.backend.catalog.model.dto.ReleaseValidationError;
import com.xavier.backend.dining.exception.IllegalOccupationStateException;
import com.xavier.backend.dining.exception.IllegalReservationStateException;
import com.xavier.backend.dining.exception.ReservationRuleException;
import com.xavier.backend.dining.exception.StillOccupiedException;
import com.xavier.backend.dining.exception.TableOccupiedException;
import com.xavier.backend.dining.exception.TableUnavailableException;
import com.xavier.backend.fiscal.FiscalIssueFailedException;
import com.xavier.backend.fiscal.exception.DraftMismatchException;
import com.xavier.backend.fiscal.exception.PaymentSnapshotMismatchException;
import com.xavier.backend.identity.exception.LoginThrottledException;
import com.xavier.backend.identity.exception.MfaConflictException;
import com.xavier.backend.identity.exception.MfaRefusedException;
import com.xavier.backend.identity.exception.MfaRuleException;
import com.xavier.backend.inventory.count.exception.CountConflictException;
import com.xavier.backend.inventory.count.exception.CountRuleException;
import com.xavier.backend.inventory.consumption.exception.ConsumptionConflictException;
import com.xavier.backend.inventory.consumption.exception.ConsumptionRuleException;
import com.xavier.backend.inventory.exception.InventoryConflictException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.preparation.exception.PreparationConflictException;
import com.xavier.backend.inventory.recipe.exception.RecipeConflictException;
import com.xavier.backend.inventory.recipe.exception.RecipeRuleException;
import com.xavier.backend.inventory.purchasing.exception.PurchasingConflictException;
import com.xavier.backend.inventory.purchasing.exception.PurchasingRuleException;
import com.xavier.backend.identity.exception.PinChangeNotRequiredException;
import com.xavier.backend.identity.exception.PinUnchangedException;
import com.xavier.backend.orders.DeliveryStatus;
import com.xavier.backend.orders.delivery.exception.DeliveryConflictException;
import com.xavier.backend.orders.delivery.exception.DeliveryRuleException;
import com.xavier.backend.orders.exception.OrderConflictException;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.production.exception.ProductionRule;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyConflictException;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyRuleException;
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
import java.time.Instant;
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
            Map.entry("reservation-not-booked", () -> IllegalReservationStateException.notBooked(ID)),
            Map.entry("reservation-not-started", () -> ReservationRuleException.notStarted(ID)),
            Map.entry("reservation-in-the-past", () -> ReservationRuleException.inThePast(
                    java.time.Instant.parse("2027-01-01T12:00:00Z"))),
            Map.entry("reservation-other-store", () -> ReservationRuleException.otherStore(ID)),
            Map.entry("reservation-table-other-store", () -> ReservationRuleException.tableOtherStore(ID)),
            Map.entry("reservation-customer-not-found", () -> ReservationRuleException.customerNotFound(ID)),
            Map.entry("reservation-range-reversed", () -> ReservationRuleException.reversed(
                    LocalDate.parse("2027-02-01"), LocalDate.parse("2027-01-01"))),
            Map.entry("reservation-range-too-large", () -> ReservationRuleException.tooLarge(92)),
            Map.entry("pin-unchanged", PinUnchangedException::new),
            Map.entry("pin-change-not-required", PinChangeNotRequiredException::new),
            Map.entry("login-throttled", () -> new LoginThrottledException(Duration.ofSeconds(90))),
            Map.entry("mfa-step-up-required", StepUpRequiredException::stepUpRequired),
            Map.entry("mfa-enrolment-required", StepUpRequiredException::enrolmentRequired),
            Map.entry("mfa-account-required", StepUpRequiredException::accountRequired),
            // R5 identity (S56): second factors
            Map.entry("mfa-invalid-code", MfaRefusedException::invalidCode),
            Map.entry("mfa-pending-expired", MfaRefusedException::pendingExpired),
            Map.entry("mfa-wrong-current-password", MfaRefusedException::wrongCurrentPassword),
            Map.entry("mfa-locked", () -> MfaRefusedException.locked(Duration.ofMinutes(15))),
            Map.entry("mfa-required", MfaRefusedException::required),
            Map.entry("mfa-not-enrolled-at-login", MfaRefusedException::notEnrolledAtLogin),
            Map.entry("mfa-enrolment-code-required", MfaRefusedException::enrolmentCodeRequired),
            Map.entry("mfa-not-pending", MfaConflictException::notPending),
            Map.entry("mfa-not-enrolled", MfaConflictException::notEnrolled),
            Map.entry("mfa-already-enrolled", MfaConflictException::alreadyEnrolled),
            Map.entry("mfa-no-pending-enrolment", MfaConflictException::noPendingEnrolment),
            Map.entry("mfa-not-configured", MfaConflictException::notConfigured),
            Map.entry("mfa-enrolment-code-invalid", MfaRuleException::enrolmentCodeInvalid),
            Map.entry("mfa-self-reset", MfaRuleException::selfReset),
            Map.entry("mfa-reason-required", MfaRuleException::reasonRequired),
            Map.entry("cash-rule", () -> new CashRuleViolationException(CashRule.DAY_NOT_OPEN,
                    "The cash day is not open.")),
            Map.entry("fiscal-pending", () -> new FiscalIssueFailedException(ID,
                    new IllegalStateException("adapter offline"))),
            Map.entry("draft-mismatch-lines", () -> DraftMismatchException.lines(ID)),
            Map.entry("draft-mismatch-on-account", () -> DraftMismatchException.onAccount(ID)),
            Map.entry("payment-snapshot-mismatch", () -> new PaymentSnapshotMismatchException(ID,
                    "the document is on account but has no credit")),
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
            Map.entry("discount-exceeds-unpaid", () -> SettlementRuleException.discountExceedsUnpaid(ID,
                    Money.of("12.50", MZN), Money.of("20.00", MZN), Money.of("5.00", MZN))),
            Map.entry("discounted-line-partitioned", () -> SettlementRuleException.discountedLinePartitioned(ID,
                    new BigDecimal("1.000"))),
            Map.entry("settlement-delivery-not-delivered", () -> SettlementRuleException.deliveryNotDelivered(ID)),
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
            Map.entry("tax-correction-resolved", () -> ReconciliationConflictException.taxCorrectionResolved(ID)),
            Map.entry("not-a-pending-correction", () -> ReconciliationRuleException.notAPendingCorrection(ID)),
            Map.entry("statement-imported-elsewhere", ReconciliationConflictException::alreadyImportedElsewhere),
            Map.entry("statement-source-timeout", () -> com.xavier.backend.settlement.reconciliation.exception
                    .ReconciliationUnavailableException.sourceTimeout("WALLET")),
            Map.entry("range-reversed", () -> ReportRangeException.reversed(LocalDate.of(2026, 11, 5),
                    LocalDate.of(2026, 11, 4))),
            Map.entry("range-too-large", () -> ReportRangeException.tooLarge(366)),
            Map.entry("rebuild-running", () -> new RebuildRunningException(ID)),
            Map.entry("invalid-export-params", () -> new ExportParamsException("groupBy")),
            // R3 production (S24): routing a ticket later
            Map.entry("still-unrouted", () -> new ProductionRuleViolationException(ProductionRule.STILL_UNROUTED,
                    "Production ticket " + ID + " still has no printer to go to: add its route or its terminal's "
                            + "printer first")),
            Map.entry("ticket-already-routed", () -> ProductionConflictException.ticketAlreadyRouted(ID)),
            // R5 production (S59): a printer profile outside D154's ranges
            Map.entry("printer-profile-invalid", () -> new ProductionRuleViolationException(
                    ProductionRule.PRINTER_PROFILE_INVALID, "A 58 mm printer has 24-35 columns")),
            // R5 production (S60): the manual drawer kick
            Map.entry("drawer-reason-required", () -> new ProductionRuleViolationException(
                    ProductionRule.REASON_REQUIRED, "Opening the drawer by hand needs a reason.")),
            Map.entry("no-receipt-printer", () -> new ProductionRuleViolationException(
                    ProductionRule.NO_RECEIPT_PRINTER, "This terminal has no receipt printer, so no cash drawer to "
                            + "open.")),
            Map.entry("no-cash-drawer", () -> new ProductionRuleViolationException(ProductionRule.NO_CASH_DRAWER,
                    "The receipt printer CX1 has no cash drawer.")),
            // R3 audit (S26): the query and export guards
            Map.entry("audit-range-too-large", () -> AuditQueryException.rangeTooLarge(92)),
            Map.entry("audit-export-too-large", () -> AuditQueryException.exportTooLarge(150_001, 150_000)),
            // R3 identity (S25): support access
            Map.entry("support-account-required", SupportAccountRequiredException::new),
            Map.entry("support-grant-active", SupportGrantActiveException::new),
            Map.entry("support-grant-duration", () -> new SupportGrantTooLongException(1440)),
            Map.entry("support-grant-ended", IllegalSupportGrantStateException::new),
            Map.entry("support-account-linked", SupportAccountLinkedException::new),
            // R3 customers (S20), moved here from CustomersErrorContractTest
            Map.entry("customer-inactive", () -> CustomerRuleException.inactive(ID)),
            Map.entry("credit-account-missing", () -> CustomerRuleException.accountMissing(ID)),
            Map.entry("credit-blocked", () -> CustomerRuleException.blocked(ID)),
            Map.entry("credit-limit-exceeded", () -> CustomerRuleException.limitExceeded(Money.of("60.00", MZN),
                    Money.of("40.00", MZN))),
            Map.entry("customer-exceeds-debt", () -> CustomerRuleException.exceedsDebt(Money.of("50.01", MZN),
                    Money.of("50.00", MZN))),
            Map.entry("customer-exceeds-credit", () -> CustomerRuleException.exceedsCredit(Money.of("20.01", MZN),
                    Money.of("20.00", MZN))),
            Map.entry("customer-invalid-amount", () -> CustomerRuleException.invalidAmount(
                    "The amount 1.001 has more decimals than MZN allows.")),
            Map.entry("customer-invalid-period", () -> CustomerRuleException.invalidPeriod(
                    java.time.Instant.parse("2026-11-05T00:00:00Z"), java.time.Instant.parse("2026-11-04T00:00:00Z"))),
            Map.entry("reservation-not-active", () -> CustomersConflictException.reservationClosed(ID, "CONSUMED",
                    "be released")),
            Map.entry("phone-exists", () -> CustomersConflictException.phoneExists(ID, "258841234567")),
            Map.entry("credit-account-exists", () -> CustomersConflictException.accountExists(ID)),
            Map.entry("credit-source-mismatch", () -> CustomersConflictException.sourceMismatch("debt payment", ID)),
            // R4 promotions (S39)
            Map.entry("promotion-invalid", () -> PromotionRuleException.invalid(
                    "A promotion's scope names at least one item, variant or family.")),
            Map.entry("discount-limits-invalid", () -> PromotionRuleException.limitsInvalid(new BigDecimal("20"),
                    new BigDecimal("10"))),
            Map.entry("promotion-release-invalid", () -> PromotionRuleException.releaseInvalid(List.of(
                    new PromotionReleaseError("SCOPE_NOT_IN_CATALOG", ID, "HH", "Promotion HH names what the "
                            + "published catalog of the store does not sell: [" + OTHER + "].")))),
            Map.entry("promotion-code-taken", () -> PromotionConflictException.codeTaken("HH")),
            Map.entry("promotion-release-state", () -> PromotionConflictException.releaseState(ID,
                    "cannot publish a DRAFT release, needs [VALIDATED]")),
            Map.entry("no-published-promotions", () -> PromotionsNotFoundException.noPublished(ID)),
            Map.entry("unit-dimension-mismatch", () -> InventoryRuleException.dimensionMismatch("kg", "l")),
            Map.entry("unknown-unit", () -> InventoryRuleException.unknownUnit("lb")),
            Map.entry("warehouse-store-mismatch", () -> InventoryRuleException.warehouseStoreMismatch(ID, OTHER)),
            Map.entry("reason-required", InventoryRuleException::reasonRequired),
            Map.entry("negative-stock-refused", () -> InventoryRuleException.negativeStockRefused(ID, OTHER,
                    new BigDecimal("2.000000"))),
            Map.entry("unit-cost-required", () -> InventoryRuleException.unitCostRequired(ID)),
            Map.entry("inventory-invalid-quantity", () -> InventoryRuleException.invalidQuantity(
                    "A WASTE line moves a positive quantity.")),
            Map.entry("duplicate-stock-item", () -> InventoryRuleException.duplicateStockItem(ID)),
            Map.entry("same-warehouse", () -> InventoryRuleException.sameWarehouse(ID)),
            Map.entry("received-exceeds-shipped", () -> InventoryRuleException.receivedExceedsShipped(ID,
                    new BigDecimal("4.000000"))),
            Map.entry("inventory-invalid-period", () -> InventoryRuleException.invalidPeriod(
                    LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-04"))),
            Map.entry("inventory-period-required", InventoryRuleException::periodRequired),
            Map.entry("inventory-range-too-large", () -> InventoryRuleException.rangeTooLarge(366)),
            Map.entry("inventory-not-active", () -> InventoryRuleException.notActive(ID)),
            Map.entry("activation-in-past", () -> InventoryRuleException.activationInPast(
                    java.time.Instant.parse("2026-11-06T10:00:00Z"))),
            Map.entry("transfer-state", () -> InventoryConflictException.transferState(ID, "SHIPPED", "be shipped")),
            Map.entry("inventory-already-active", () -> InventoryConflictException.alreadyActive(ID)),
            Map.entry("movement-source-mismatch", () -> InventoryConflictException.sourceMismatch("ORDER_LINE", ID)),
            Map.entry("stock-item-code-taken", () -> InventoryConflictException.codeTaken("STOCK_ITEM", "FLOUR")),
            Map.entry("warehouse-code-taken", () -> InventoryConflictException.codeTaken("WAREHOUSE", "BAR")),
            Map.entry("pack-code-taken", () -> InventoryConflictException.codeTaken("PACK", "BOX12")),
            Map.entry("route-exists", () -> InventoryConflictException.routeExists(ID, "BAR")),
            Map.entry("default-route-exists", () -> InventoryConflictException.routeExists(ID, null)),
            Map.entry("stock-level-exists", () -> InventoryConflictException.levelExists(ID, OTHER)),
            Map.entry("opening-not-first", () -> CountRuleException.openingNotFirst(ID)),
            Map.entry("count-incomplete", () -> CountRuleException.incomplete(ID)),
            Map.entry("counted-at-out-of-range", () -> CountRuleException.countedAtOutOfRange(
                    Instant.parse("2026-11-07T09:00:00Z"), Instant.parse("2026-11-07T10:00:00Z"),
                    Instant.parse("2026-11-07T12:00:00Z"))),
            Map.entry("count-self-approval", () -> CountRuleException.selfApproval(ID)),
            Map.entry("unit-cost-opening-only", CountRuleException::unitCostOpeningOnly),
            Map.entry("count-scope-invalid", () -> CountRuleException.scopeInvalid(
                    "A LIST count names the stock items to count.")),
            Map.entry("count-state", () -> CountConflictException.state(ID, "SUBMITTED", "be cancelled")),
            Map.entry("consumption-backlog", () -> CountConflictException.consumptionBacklog(ID, 3)),
            Map.entry("consumption-exceptions-open", () -> CountConflictException.consumptionExceptionsOpen(ID, 2)),
            Map.entry("live-count-exists", () -> CountConflictException.liveCountExists(ID)),
            // R4 recipes, consumption, preparations (S47)
            Map.entry("recipe-effective-from-invalid", () -> RecipeRuleException.effectiveFromInvalid(
                    java.time.Instant.parse("2026-11-06T10:00:00Z"))),
            Map.entry("recipe-effective-from-in-past", () -> RecipeRuleException.effectiveFromInPast(
                    java.time.Instant.parse("2026-11-06T10:00:00Z"))),
            Map.entry("recipe-target-invalid", RecipeRuleException::targetInvalid),
            Map.entry("item-not-found", () -> RecipeRuleException.itemNotFound(ID, OTHER)),
            Map.entry("recipe-output-not-prepared", () -> RecipeRuleException.outputNotPrepared(ID)),
            Map.entry("recipe-cycle", () -> RecipeRuleException.cycle(ID, OTHER)),
            Map.entry("no-active-recipe", () -> RecipeRuleException.noActiveRecipe(ID)),
            Map.entry("policy-rule-duplicate", () -> RecipeRuleException.policyRuleDuplicate(ID)),
            Map.entry("consumption-unresolved", () -> ConsumptionRuleException.unresolved(ID, "NO_RECIPE")),
            Map.entry("recipe-version-immutable", () -> RecipeConflictException.immutable(ID, "ACTIVE", "be edited")),
            Map.entry("recipe-exists", () -> RecipeConflictException.exists("item " + ID)),
            Map.entry("policy-version-taken", RecipeConflictException::policyVersionTaken),
            Map.entry("consumption-exception-resolved", () -> ConsumptionConflictException.alreadyResolved(ID)),
            Map.entry("preparation-state", () -> PreparationConflictException.state(ID, "CONFIRMED", "be confirmed")),
            Map.entry("loyalty-not-member", () -> LoyaltyRuleException.notMember(ID)),
            Map.entry("loyalty-suspended", () -> LoyaltyRuleException.suspended(ID)),
            Map.entry("loyalty-insufficient-points", () -> LoyaltyRuleException.insufficientPoints(10, 40)),
            Map.entry("loyalty-reward-not-applicable", () -> LoyaltyRuleException.rewardNotApplicable("COFFEE",
                    "the item is outside its scope")),
            Map.entry("loyalty-program-invalid", () -> LoyaltyRuleException.programInvalid(
                    "A reward is scoped to an item or a family, not both.")),
            Map.entry("loyalty-reason-required", () -> LoyaltyRuleException.reasonRequired("loyalty adjustment")),
            Map.entry("loyalty-invalid-period", () -> LoyaltyRuleException.invalidPeriod(
                    java.time.Instant.parse("2026-11-05T00:00:00Z"), java.time.Instant.parse("2026-11-04T00:00:00Z"))),
            Map.entry("loyalty-program-not-draft", () -> LoyaltyConflictException.programNotDraft(ID, "PUBLISHED",
                    "be published")),
            Map.entry("loyalty-program-concurrent", LoyaltyConflictException::programConcurrent),
            Map.entry("loyalty-reward-code-taken", () -> LoyaltyConflictException.rewardCodeTaken("COFFEE")),
            Map.entry("loyalty-already-enrolled", () -> LoyaltyConflictException.alreadyEnrolled(ID)),
            Map.entry("loyalty-account-state", () -> LoyaltyConflictException.accountState(ID, "SUSPENDED",
                    "be suspended")),
            Map.entry("loyalty-source-mismatch", () -> LoyaltyConflictException.sourceMismatch("order line", ID)),
            Map.entry("loyalty-source-concurrent", () -> LoyaltyConflictException.sourceConcurrent("order", ID)),
            // R4 catalog (S38): menus and daily dishes
            Map.entry("menu-group-invalid", () -> CatalogRuleException.menuGroupInvalid(
                    "Item BURGER is STANDARD: only a MENU item has menu groups.")),
            Map.entry("menu-not-allowed", () -> CatalogRuleException.menuNotAllowed("LUNCH", "a macro")),
            Map.entry("item-not-published", () -> CatalogRuleException.itemNotPublished(OTHER, List.of(ID))),
            // R5 catalog (S58): store exclusions
            Map.entry("catalog-store-not-found", () -> CatalogEntryNotFoundException.store(ID)),
            Map.entry("catalog-item-not-found", () -> CatalogEntryNotFoundException.item(ID)),
            Map.entry("release-invalid-exclusions", () -> new ReleaseValidationFailedException(List.of(
                    new ReleaseValidationError("QUICK_PAGE_ENTRY_EXCLUDED", ID, "X", "Quick page P names X"),
                    new ReleaseValidationError("AUTOMATIC_ITEM_EXCLUDED", ID, "X", "An automatic item names X")))),
            // R4 inventory purchasing (S48)
            Map.entry("po-has-receipts", () -> PurchasingRuleException.hasReceipts(ID)),
            Map.entry("po-not-submitted", () -> PurchasingRuleException.notSubmitted(ID, "CLOSED")),
            Map.entry("return-exceeds-received", () -> PurchasingRuleException.returnExceedsReceived(ID,
                    new BigDecimal("4.000000"))),
            Map.entry("receipt-not-confirmed", () -> PurchasingRuleException.receiptNotConfirmed(ID, "DRAFT")),
            Map.entry("purchase-reference-mismatch", () -> PurchasingRuleException.referenceMismatch(
                    "Pack " + ID + " is not a pack of stock item " + OTHER + ".")),
            Map.entry("purchase-unit-cost-required", () -> PurchasingRuleException.unitCostRequired(ID)),
            Map.entry("purchase-reason-required", () -> PurchasingRuleException.reasonRequired("cost correction")),
            Map.entry("line-value-too-large", () -> PurchasingRuleException.lineValueTooLarge(ID)),
            Map.entry("po-state", () -> PurchasingConflictException.orderState(ID, "SUBMITTED", "be edited")),
            Map.entry("receipt-state", () -> PurchasingConflictException.receiptState(ID, "CONFIRMED",
                    "be confirmed")),
            Map.entry("return-state", () -> PurchasingConflictException.returnState(ID, "CANCELLED", "be confirmed")),
            Map.entry("po-number-taken", () -> PurchasingConflictException.orderNumberTaken(ID, "PO-7")),
            Map.entry("supplier-item-exists", () -> PurchasingConflictException.supplierItemExists(ID, OTHER)),
            Map.entry("cost-correction-concurrent", () -> PurchasingConflictException.correctionConcurrent(ID)),
            Map.entry("supplier-code-taken", () -> InventoryConflictException.codeTaken("SUPPLIER", "MOAGEM")),
            // R4 orders (S46): pricing, menus, discounts, loyalty
            Map.entry("promotions-changed", () -> OrderConflictException.promotionsChanged(null, 4L)),
            Map.entry("prices-changed", () -> OrderConflictException.pricesChanged(new BigDecimal("20.00"),
                    new BigDecimal("25.00"), List.of())),
            Map.entry("item-sold-out", () -> OrderRuleException.itemSoldOut(ID, null)),
            Map.entry("menu-selection-invalid", () -> OrderRuleException.menuSelectionInvalid("LUNCH",
                    "group STARTER takes 1 to 1 choices, not 0")),
            Map.entry("macro-not-published", () -> OrderRuleException.macroNotPublished("DRINKS")),
            Map.entry("discount-limit-exceeded", () -> OrderRuleException.discountLimitExceeded(ID,
                    new BigDecimal("52.00"), new BigDecimal("50"))),
            Map.entry("price-increase-not-allowed", () -> OrderRuleException.priceIncreaseNotAllowed(ID)),
            Map.entry("loyalty-customer-required", () -> OrderRuleException.loyaltyCustomerRequired(ID)),
            Map.entry("reward-needs-single-unit", () -> OrderRuleException.rewardNeedsSingleUnit(ID)),
            Map.entry("loyalty-redeemed", () -> OrderRuleException.loyaltyRedeemed(ID)),
            Map.entry("reward-already-redeemed", () -> OrderRuleException.rewardAlreadyRedeemed(ID)),
            // R4 orders (S50): delivery
            Map.entry("delivery-state", () -> DeliveryConflictException.state(ID, DeliveryStatus.PENDING,
                    "delivered")),
            Map.entry("delivery-zone-code-taken", () -> DeliveryConflictException.zoneCodeTaken("CENTRO")),
            Map.entry("fee-item-not-published", () -> DeliveryRuleException.feeItemNotPublished("CENTRO")),
            Map.entry("zone-inactive", () -> DeliveryRuleException.zoneInactive("CENTRO")),
            Map.entry("zone-other-store", () -> DeliveryRuleException.zoneOtherStore(ID)),
            Map.entry("customer-required", DeliveryRuleException::customerRequired),
            Map.entry("delivery-has-drafts", () -> DeliveryRuleException.hasDrafts(ID)),
            Map.entry("delivery-nothing-confirmed", () -> DeliveryRuleException.nothingConfirmed(ID)),
            Map.entry("courier-not-in-store", () -> DeliveryRuleException.courierNotInStore(ID)),
            Map.entry("delivery-not-delivered", () -> DeliveryRuleException.notDelivered(ID)),
            Map.entry("delivery-in-progress", () -> DeliveryRuleException.inProgress(ID)),
            Map.entry("address-frozen", () -> DeliveryRuleException.addressFrozen(ID)),
            Map.entry("fee-line-locked", () -> DeliveryRuleException.feeLineLocked(ID)),
            Map.entry("zone-fee-confirmed", () -> DeliveryRuleException.zoneFeeConfirmed(ID)));

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
    void reservationErrors() throws Exception {
        expect("reservation-not-booked", 409, "RESERVATION_NOT_BOOKED", "Reservation " + ID + " is not booked.", "");
        expect("reservation-not-started", 422, "RESERVATION_NOT_STARTED",
                "Reservation " + ID + " has not started: it cannot be a no-show yet.", "");
        expect("reservation-in-the-past", 422, "RESERVATION_IN_THE_PAST",
                "A reservation starting at 2027-01-01T12:00:00Z is already past its no-show grace.", "");
        expect("reservation-other-store", 422, "RESERVATION_OTHER_STORE",
                "Reservation " + ID + " is not in this store.", "");
        expect("reservation-table-other-store", 422, "TABLE_OTHER_STORE", "Table " + ID + " is not in this store.",
                "");
        expect("reservation-customer-not-found", 422, "CUSTOMER_NOT_FOUND", "Customer " + ID + " was not found.", "");
        expect("reservation-range-reversed", 422, "RANGE_REVERSED",
                "The range starts on 2027-02-01, after its end 2027-01-01.", "");
        expect("reservation-range-too-large", 422, "RANGE_TOO_LARGE", "The range is longer than 92 days: narrow it.",
                ",\"maxDays\":92");
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

    /** D142: a step-up refusal is a coded 403 that tells the client where to prove the second factor. */
    @Test
    void stepUpRefusalsAreCoded403sWithTheStepUpUri() throws Exception {
        expect("mfa-step-up-required", 403, "MFA_STEP_UP_REQUIRED", "Confirm your second factor to continue.",
                ",\"stepUpUri\":\"/api/v1/auth/mfa/step-up\"");
        expect("mfa-enrolment-required", 403, "MFA_ENROLMENT_REQUIRED", "Set up a second factor to continue.",
                ",\"stepUpUri\":\"/api/v1/auth/mfa/step-up\"");
    }

    /** R5 S56 (D139-D143): the second-factor refusals that are not 404/409/422. */
    @Test
    void secondFactorRefusals() throws Exception {
        expect("mfa-invalid-code", 401, "MFA_INVALID_CODE", "The code is not valid.", "");
        expect("mfa-pending-expired", 401, "MFA_PENDING_EXPIRED",
                "The login waited too long for its second factor. Log in again.", "");
        expect("mfa-wrong-current-password", 401, "WRONG_CURRENT_PASSWORD", "The current password is not correct.",
                "");
        expect("mfa-locked", 429, "MFA_LOCKED", "Too many wrong codes. Try again later.", "");
        mvc.perform(get(PATH + "mfa-locked")).andExpect(header().string("Retry-After", "900"));
        expect("mfa-required", 403, "MFA_REQUIRED",
                "Confirm your second factor before using this session from outside the store network.", "");
        expect("mfa-not-enrolled-at-login", 403, "MFA_NOT_ENROLLED",
                "This account has no second factor. Ask an administrator for an enrolment code.", "");
        expect("mfa-enrolment-code-required", 403, "MFA_ENROLMENT_CODE_REQUIRED",
                "Enrolling from here needs an enrolment code from an administrator.", "");
    }

    /** R5 S56: second-factor conflicts (409) and rules (422). */
    @Test
    void secondFactorConflictsAndRules() throws Exception {
        expect("mfa-not-pending", 409, "MFA_NOT_PENDING", "This session is not waiting for a second factor.", "");
        expect("mfa-not-enrolled", 409, "MFA_NOT_ENROLLED", "This account has no second factor.", "");
        expect("mfa-already-enrolled", 409, "MFA_ALREADY_ENROLLED", "This account already has a second factor.", "");
        expect("mfa-no-pending-enrolment", 409, "MFA_NO_PENDING_ENROLMENT",
                "There is no enrolment to confirm. Start the enrolment again.", "");
        expect("mfa-not-configured", 409, "MFA_NOT_CONFIGURED",
                "Second factors are not configured on this installation.", "");
        expect("mfa-enrolment-code-invalid", 422, "MFA_ENROLMENT_CODE_INVALID", "The enrolment code is not valid.",
                "");
        expect("mfa-self-reset", 422, "MFA_SELF_RESET",
                "You cannot reset your own second factor. Use a recovery code instead.", "");
        expect("mfa-reason-required", 422, "REASON_REQUIRED", "A reason is required.", "");
    }

    /** D142 (amended, S55 review): a PIN session cannot step up, so its refusal names no step-up URI. */
    @Test
    void aPosSessionOnASensitivePermissionIsACoded403WithoutStepUpUri() throws Exception {
        expect("mfa-account-required", 403, "MFA_ACCOUNT_REQUIRED",
                "Use a BackOffice account with a second factor for this action.", "");
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
        expect("discount-exceeds-unpaid", 422, "DISCOUNT_EXCEEDS_UNPAID", "The discount would leave 12.50 due on "
                + "order " + ID + ", less than the 20.00 paid or being paid: refund first.", ",\"unpaid\":5.00");
        expect("discounted-line-partitioned", 422, "QUANTITY_PARTITIONED", "Only 1 of line " + ID
                + " is in no partition: cancel the partition before discounting it.",
                ",\"lineId\":\"" + ID + "\",\"available\":1.000");
        expect("settlement-delivery-not-delivered", 422, "DELIVERY_NOT_DELIVERED",
                "Order " + ID + " is closed only once its delivery is DELIVERED.", "");
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
        expect("tax-correction-resolved", 409, "TAX_CORRECTION_RESOLVED", "The pending fiscal correction " + ID
                + " is already resolved.", "");
        expect("not-a-pending-correction", 422, "NOT_A_PENDING_CORRECTION", "Tax rounding adjustment " + ID
                + " was absorbed by the residual document: there is nothing to correct.", "");
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
    void fiscalPaymentSnapshotMismatch() throws Exception {
        expect("payment-snapshot-mismatch", 422, "PAYMENT_SNAPSHOT_MISMATCH",
                "The payment snapshot does not fit the draft document " + ID
                        + ": the document is on account but has no credit.",
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

    @Test
    void productionRoutingLater() throws Exception {
        expect("still-unrouted", 422, "STILL_UNROUTED", "Production ticket " + ID + " still has no printer to go "
                + "to: add its route or its terminal's printer first", "");
        expect("ticket-already-routed", 409, "TICKET_ALREADY_ROUTED", "Production ticket " + ID
                + " is already routed", "");
    }

    @Test
    void productionPrinterProfile() throws Exception {
        expect("printer-profile-invalid", 422, "PRINTER_PROFILE_INVALID", "A 58 mm printer has 24-35 columns", "");
    }

    @Test
    void productionManualDrawerKick() throws Exception {
        expect("drawer-reason-required", 422, "REASON_REQUIRED", "Opening the drawer by hand needs a reason.", "");
        expect("no-receipt-printer", 422, "NO_RECEIPT_PRINTER", "This terminal has no receipt printer, so no cash "
                + "drawer to open.", "");
        expect("no-cash-drawer", 422, "NO_CASH_DRAWER", "The receipt printer CX1 has no cash drawer.", "");
    }

    @Test
    void auditQueryGuards() throws Exception {
        expect("audit-range-too-large", 422, "RANGE_TOO_LARGE", "The range is longer than 92 days: narrow it.",
                ",\"maxDays\":92");
        expect("audit-export-too-large", 422, "EXPORT_TOO_LARGE", "The export would have 150001 rows, more than "
                + "150000: narrow the filters.", ",\"rows\":150001,\"maxRows\":150000");
    }

    @Test
    void supportAccessCodes() throws Exception {
        expect("support-account-required", 422, "SUPPORT_ACCOUNT_REQUIRED",
                "Support access is granted only to a support account.", "");
        expect("support-grant-active", 409, "SUPPORT_GRANT_ACTIVE",
                "The account already holds an active support grant.", "");
        expect("support-grant-duration", 422, "SUPPORT_GRANT_DURATION",
                "A support grant lasts between 1 minute and 24 hours.", ",\"maxMinutes\":1440");
        expect("support-grant-ended", 409, "SUPPORT_GRANT_ENDED", "The support grant has already ended.", "");
        expect("support-account-linked", 422, "SUPPORT_ACCOUNT_LINKED",
                "A support account cannot be linked to an employee.", "");
    }

    @Test
    void customerAndCreditRules() throws Exception {
        expect("customer-inactive", 422, "CUSTOMER_INACTIVE", "Customer " + ID + " is inactive.", "");
        expect("credit-account-missing", 422, "CREDIT_ACCOUNT_MISSING", "Customer " + ID
                + " has no credit account.", "");
        expect("credit-blocked", 422, "CREDIT_BLOCKED", "The credit of customer " + ID + " is blocked.", "");
        expect("customer-invalid-amount", 422, "INVALID_AMOUNT", "The amount 1.001 has more decimals than MZN "
                + "allows.", "");
        expect("customer-invalid-period", 422, "INVALID_PERIOD",
                "The period starts (2026-11-05T00:00:00Z) after it ends (2026-11-04T00:00:00Z).", "");
    }

    @Test
    void customerAmountRulesCarryTheAmountThatWasAvailable() throws Exception {
        expect("credit-limit-exceeded", 422, "CREDIT_LIMIT_EXCEEDED",
                "The amount 60.00 is more than the available credit 40.00.", ",\"available\":40.00");
        expect("customer-exceeds-debt", 422, "AMOUNT_EXCEEDS_DEBT",
                "The amount 50.01 is more than the open debt 50.00.", ",\"debt\":50.00");
        expect("customer-exceeds-credit", 422, "AMOUNT_EXCEEDS_CREDIT",
                "The amount 20.01 is more than the 20.00 of the debt payment still to reverse.",
                ",\"refundable\":20.00");
    }

    @Test
    void customerConflicts() throws Exception {
        expect("reservation-not-active", 409, "RESERVATION_NOT_ACTIVE",
                "The credit reservation of charge " + ID + " is CONSUMED and cannot be released.", "");
        expect("phone-exists", 409, "PHONE_EXISTS", "Customer " + ID + " already has the phone 258841234567.", "");
        expect("credit-account-exists", 409, "CREDIT_ACCOUNT_EXISTS",
                "The credit account of customer " + ID + " was opened at the same time; retry.", "");
        expect("credit-source-mismatch", 409, "CREDIT_SOURCE_MISMATCH",
                "The debt payment " + ID + " is already recorded with other values.", "");
    }

    @Test
    void promotionsRules() throws Exception {
        expect("promotion-invalid", 422, "PROMOTION_INVALID",
                "A promotion's scope names at least one item, variant or family.", "");
        expect("discount-limits-invalid", 422, "DISCOUNT_LIMITS_INVALID",
                "The discount limits 20 % free and 10 % maximum need 0 <= free <= maximum <= 100.", "");
        expect("promotion-release-invalid", 422, "PROMOTION_RELEASE_INVALID",
                "The promotions cannot be published: 1 problem(s).",
                ",\"errors\":[{\"code\":\"SCOPE_NOT_IN_CATALOG\",\"promotionId\":\"" + ID
                        + "\",\"promotionCode\":\"HH\",\"message\":\"Promotion HH names what the published "
                        + "catalog of the store does not sell: [" + OTHER + "].\"}]");
    }

    @Test
    void promotionsConflictsAndAbsence() throws Exception {
        expect("promotion-code-taken", 409, "PROMOTION_CODE_TAKEN", "The promotion code HH is taken.", "");
        expect("promotion-release-state", 409, "PROMOTION_RELEASE_STATE", "Promotion release " + ID
                + ": cannot publish a DRAFT release, needs [VALIDATED]", "");
        expect("no-published-promotions", 404, "NO_PUBLISHED_PROMOTIONS",
                "Store " + ID + " has no published promotions.", "");
    }

    @Test
    void inventoryRules() throws Exception {
        expect("unit-dimension-mismatch", 422, "UNIT_DIMENSION_MISMATCH", "The unit kg cannot be converted to l.", "");
        expect("unknown-unit", 422, "UNKNOWN_UNIT", "There is no stock unit lb.", "");
        expect("warehouse-store-mismatch", 422, "WAREHOUSE_STORE_MISMATCH", "Warehouse " + ID
                + " does not belong to store " + OTHER + ".", "");
        expect("reason-required", 422, "REASON_REQUIRED", "A manual stock movement needs a reason.", "");
        expect("unit-cost-required", 422, "UNIT_COST_REQUIRED", "An incoming adjustment of stock item " + ID
                + " needs a unit cost.", "");
        expect("inventory-invalid-quantity", 422, "INVALID_QUANTITY", "A WASTE line moves a positive quantity.", "");
        expect("duplicate-stock-item", 422, "DUPLICATE_STOCK_ITEM", "Stock item " + ID
                + " appears on more than one line.", "");
        expect("same-warehouse", 422, "SAME_WAREHOUSE", "A transfer goes to another warehouse than " + ID + ".", "");
        expect("inventory-invalid-period", 422, "INVALID_PERIOD",
                "The period starts (2026-11-05) after it ends (2026-11-04).", "");
        expect("inventory-period-required", 422, "INVALID_PERIOD", "This report needs a period (from, to).", "");
        expect("inventory-range-too-large", 422, "RANGE_TOO_LARGE", "The range is longer than 366 days: narrow it.",
                ",\"maxDays\":366");
        expect("inventory-not-active", 422, "INVENTORY_NOT_ACTIVE", "Inventory is not active in store " + ID
                + ": activate it and count the opening stock first.", "");
        expect("activation-in-past", 422, "ACTIVATION_IN_PAST",
                "Inventory goes live now or later, not before 2026-11-06T10:00:00Z.",
                ",\"notBefore\":\"2026-11-06T10:00:00Z\"");
    }

    @Test
    void inventoryQuantityRulesCarryTheItemAndTheQuantity() throws Exception {
        expect("negative-stock-refused", 422, "NEGATIVE_STOCK_REFUSED", "Warehouse " + ID
                        + " refuses manual movements below zero: it holds 2.000000 of stock item " + OTHER + ".",
                ",\"stockItemId\":\"" + OTHER + "\",\"available\":2.000000");
        expect("received-exceeds-shipped", 422, "RECEIVED_EXCEEDS_SHIPPED", "More of stock item " + ID
                        + " was received than the 4.000000 shipped.",
                ",\"stockItemId\":\"" + ID + "\",\"shipped\":4.000000");
    }

    @Test
    void inventoryConflicts() throws Exception {
        expect("transfer-state", 409, "TRANSFER_STATE", "Transfer " + ID + " is SHIPPED and cannot be shipped.", "");
        expect("inventory-already-active", 409, "INVENTORY_ALREADY_ACTIVE",
                "Inventory is already active in store " + ID + ".", "");
        expect("movement-source-mismatch", 409, "MOVEMENT_SOURCE_MISMATCH", "The ORDER_LINE " + ID
                + " already has stock movements with other values.", "");
        expect("stock-item-code-taken", 409, "STOCK_ITEM_CODE_TAKEN", "The stock item code FLOUR is taken.", "");
        expect("warehouse-code-taken", 409, "WAREHOUSE_CODE_TAKEN", "The warehouse code BAR is taken.", "");
        expect("pack-code-taken", 409, "PACK_CODE_TAKEN", "The pack code BOX12 is taken.", "");
        expect("route-exists", 409, "ROUTE_EXISTS", "Store " + ID + " already has a route for destination BAR.", "");
        expect("default-route-exists", 409, "ROUTE_EXISTS", "Store " + ID + " already has a default route.", "");
        expect("stock-level-exists", 409, "STOCK_LEVEL_EXISTS", "Warehouse " + ID
                + " already has a minimum for stock item " + OTHER + ".", "");
    }

    @Test
    void inventoryCountRules() throws Exception {
        expect("opening-not-first", 422, "OPENING_NOT_FIRST", "Warehouse " + ID
                + " already has stock movements or an opening count before this opening's cut-off.", "");
        expect("count-incomplete", 422, "COUNT_INCOMPLETE",
                "Count " + ID + " has no line, or a line not counted yet.", "");
        expect("counted-at-out-of-range", 422, "COUNTED_AT_OUT_OF_RANGE", "A line is counted between the count's "
                + "start (2026-11-07T10:00:00Z) and now (2026-11-07T12:00:00Z), not at 2026-11-07T09:00:00Z.", "");
        expect("count-self-approval", 422, "SELF_APPROVAL", "Count " + ID
                + " was counted (in part) by its approver: another person approves it.", "");
        expect("unit-cost-opening-only", 422, "UNIT_COST_OPENING_ONLY",
                "A unit cost is entered on an opening count only: a regular count is valued at the average.", "");
        expect("count-scope-invalid", 422, "COUNT_SCOPE_INVALID", "A LIST count names the stock items to count.", "");
    }

    @Test
    void inventoryCountConflicts() throws Exception {
        expect("count-state", 409, "COUNT_STATE", "Count " + ID + " is SUBMITTED and cannot be cancelled.", "");
        expect("consumption-backlog", 409, "CONSUMPTION_BACKLOG", "Stock consumption of store " + ID + " has 3 "
                + "publication(s) still pending up to the count's cut-off: approve the count once they are processed.",
                "");
        expect("consumption-exceptions-open", 409, "CONSUMPTION_EXCEPTIONS_OPEN", "Store " + ID + " has 2 open "
                + "consumption exception(s) up to the count's cut-off: resolve them, then approve.", "");
        expect("live-count-exists", 409, "LIVE_COUNT_EXISTS", "Warehouse " + ID
                + " already has a count that is not decided or cancelled.", "");
    }

    @Test
    void recipeAndConsumptionRules() throws Exception {
        expect("recipe-effective-from-invalid", 422, "RECIPE_EFFECTIVE_FROM_INVALID",
                "A new version must take effect after 2026-11-06T10:00:00Z, when the current one did.",
                ",\"notAfter\":\"2026-11-06T10:00:00Z\"");
        expect("recipe-effective-from-in-past", 422, "RECIPE_EFFECTIVE_FROM_INVALID",
                "A version takes effect now or later, not before 2026-11-06T10:00:00Z.",
                ",\"notBefore\":\"2026-11-06T10:00:00Z\"");
        expect("recipe-target-invalid", 422, "RECIPE_TARGET_INVALID", "A recipe is for a catalog item (itemId, "
                + "optionally variantId) or a prepared stock item (outputStockItemId): exactly one of them.", "");
        expect("item-not-found", 422, "ITEM_NOT_FOUND", "Catalog item " + ID + " with variant " + OTHER
                + " was not found.", "");
        expect("recipe-output-not-prepared", 422, "RECIPE_OUTPUT_NOT_PREPARED", "Stock item " + ID
                + " is not PREPARED: only a prepared item has a preparation recipe.", "");
        expect("recipe-cycle", 422, "RECIPE_CYCLE", "The recipe of stock item " + ID
                + " would need itself through stock item " + OTHER + ".", ",\"stockItemId\":\"" + OTHER + "\"");
        expect("no-active-recipe", 422, "NO_ACTIVE_RECIPE", "Stock item " + ID
                + " has no active preparation recipe.", "");
        expect("policy-rule-duplicate", 422, "POLICY_RULE_DUPLICATE",
                "The consumption policy has two rules for family " + ID + ".", "");
        expect("consumption-unresolved", 422, "CONSUMPTION_UNRESOLVED", "Consumption exception " + ID
                + " still cannot be consumed: NO_RECIPE. Fix it and reprocess again.", ",\"kind\":\"NO_RECIPE\"");
    }

    @Test
    void recipeAndConsumptionConflicts() throws Exception {
        expect("recipe-version-immutable", 409, "RECIPE_VERSION_IMMUTABLE", "Version " + ID
                + " is ACTIVE and cannot be edited: create a new version.", "");
        expect("recipe-exists", 409, "RECIPE_EXISTS", "There is already a recipe for item " + ID + ".", "");
        expect("policy-version-taken", 409, "POLICY_VERSION_TAKEN",
                "Another consumption policy version was created or activated at the same time: retry.", "");
        expect("consumption-exception-resolved", 409, "CONSUMPTION_EXCEPTION_RESOLVED", "Consumption exception "
                + ID + " is already resolved.", "");
        expect("preparation-state", 409, "PREPARATION_STATE", "Preparation " + ID
                + " is CONFIRMED and cannot be confirmed.", "");
    }

    @Test
    void loyaltyRules() throws Exception {
        expect("loyalty-not-member", 422, "LOYALTY_NOT_MEMBER", "Customer " + ID + " is not a loyalty member.", "");
        expect("loyalty-suspended", 422, "LOYALTY_ACCOUNT_SUSPENDED",
                "The loyalty account of customer " + ID + " is suspended.", "");
        expect("loyalty-insufficient-points", 422, "LOYALTY_INSUFFICIENT_POINTS",
                "The balance of 10 points is less than the 40 needed.", ",\"balance\":10,\"required\":40");
        expect("loyalty-reward-not-applicable", 422, "LOYALTY_REWARD_NOT_APPLICABLE",
                "The reward COFFEE cannot be redeemed here: the item is outside its scope.", "");
        expect("loyalty-program-invalid", 422, "LOYALTY_PROGRAM_INVALID",
                "A reward is scoped to an item or a family, not both.", "");
        expect("loyalty-reason-required", 422, "REASON_REQUIRED", "A loyalty adjustment needs a reason.", "");
        expect("loyalty-invalid-period", 422, "INVALID_PERIOD",
                "The period starts (2026-11-05T00:00:00Z) after it ends (2026-11-04T00:00:00Z).", "");
    }

    @Test
    void loyaltyConflicts() throws Exception {
        expect("loyalty-program-not-draft", 409, "LOYALTY_PROGRAM_NOT_DRAFT",
                "The loyalty program version " + ID + " is PUBLISHED and cannot be published.", "");
        expect("loyalty-program-concurrent", 409, "LOYALTY_PROGRAM_CONCURRENT",
                "Another loyalty program version was created or published at the same time; retry.", "");
        expect("loyalty-reward-code-taken", 409, "LOYALTY_REWARD_CODE_TAKEN",
                "The program version already has a reward COFFEE.", "");
        expect("loyalty-already-enrolled", 409, "LOYALTY_ALREADY_ENROLLED",
                "Customer " + ID + " is already a loyalty member.", "");
        expect("loyalty-account-state", 409, "LOYALTY_ACCOUNT_STATE",
                "The loyalty account of customer " + ID + " is SUSPENDED and cannot be suspended.", "");
        expect("loyalty-source-mismatch", 409, "LOYALTY_SOURCE_MISMATCH",
                "The order line " + ID + " is already recorded in the loyalty ledger with other values.", "");
        expect("loyalty-source-concurrent", 409, "LOYALTY_SOURCE_CONCURRENT",
                "The order " + ID + " was recorded in the loyalty ledger at the same time; retry.", "");
    }

    @Test
    void catalogR4Rules() throws Exception {
        expect("menu-group-invalid", 422, "MENU_GROUP_INVALID",
                "Item BURGER is STANDARD: only a MENU item has menu groups.", "");
        expect("menu-not-allowed", 422, "MENU_NOT_ALLOWED",
                "Item LUNCH is a MENU: a macro cannot name it, because a menu needs its component choices.", "");
        expect("item-not-published", 422, "ITEM_NOT_PUBLISHED", "Item(s) [" + ID
                + "] are not in the published catalog of store " + OTHER + ".", ",\"itemIds\":[\"" + ID + "\"]");
    }

    @Test
    void catalogR5StoreExclusions() throws Exception {
        expect("catalog-store-not-found", 404, "STORE_NOT_FOUND", "Store " + ID + " does not exist.", "");
        expect("catalog-item-not-found", 404, "ITEM_NOT_FOUND", "Item " + ID + " does not exist.", "");
        expect("release-invalid-exclusions", 422, null, "The catalog cannot be published: 2 problem(s).",
                ",\"errors\":[{\"code\":\"QUICK_PAGE_ENTRY_EXCLUDED\",\"itemId\":\"" + ID
                        + "\",\"itemCode\":\"X\",\"message\":\"Quick page P names X\"},"
                        + "{\"code\":\"AUTOMATIC_ITEM_EXCLUDED\",\"itemId\":\"" + ID
                        + "\",\"itemCode\":\"X\",\"message\":\"An automatic item names X\"}]");
    }

    @Test
    void inventoryPurchasingRules() throws Exception {
        expect("po-has-receipts", 422, "PO_HAS_RECEIPTS", "Purchase order " + ID
                + " has confirmed receipts: close it instead of cancelling it.", "");
        expect("po-not-submitted", 422, "PO_NOT_SUBMITTED", "Purchase order " + ID
                + " is CLOSED: goods are received only against a submitted order.", "");
        expect("return-exceeds-received", 422, "RETURN_EXCEEDS_RECEIVED", "Only 4.000000 of receipt line " + ID
                + " can still be returned.", ",\"receiptLineId\":\"" + ID + "\",\"available\":4.000000");
        expect("receipt-not-confirmed", 422, "RECEIPT_NOT_CONFIRMED", "Goods receipt " + ID
                + " is DRAFT: only a confirmed receipt has costs to correct or goods to return.", "");
        expect("purchase-reference-mismatch", 422, "PURCHASE_REFERENCE_MISMATCH",
                "Pack " + ID + " is not a pack of stock item " + OTHER + ".", "");
        expect("purchase-unit-cost-required", 422, "UNIT_COST_REQUIRED", "Stock item " + ID
                + " needs a unit cost: this supplier has no last cost for it.", "");
        expect("purchase-reason-required", 422, "REASON_REQUIRED", "A cost correction needs a reason.", "");
        expect("line-value-too-large", 422, "LINE_VALUE_TOO_LARGE", "The line of stock item " + ID
                + " is worth more than a stock value can hold: split it or check its cost.", "");
    }

    @Test
    void inventoryPurchasingConflicts() throws Exception {
        expect("po-state", 409, "PO_STATE", "Purchase order " + ID + " is SUBMITTED and cannot be edited.", "");
        expect("receipt-state", 409, "RECEIPT_STATE", "Goods receipt " + ID
                + " is CONFIRMED and cannot be confirmed.", "");
        expect("return-state", 409, "RETURN_STATE", "Supplier return " + ID + " is CANCELLED and cannot be confirmed.",
                "");
        expect("po-number-taken", 409, "PO_NUMBER_TAKEN", "Store " + ID + " already has a purchase order PO-7.", "");
        expect("supplier-item-exists", 409, "SUPPLIER_ITEM_EXISTS", "Supplier " + ID + " already has stock item "
                + OTHER + " in that pack.", "");
        expect("cost-correction-concurrent", 409, "COST_CORRECTION_CONCURRENT", "Receipt line " + ID
                + " was corrected at the same time; retry on its new cost.", "");
        expect("supplier-code-taken", 409, "SUPPLIER_CODE_TAKEN", "The supplier code MOAGEM is taken.", "");
    }

    @Test
    void ordersR4Codes() throws Exception {
        expect("promotions-changed", 409, "PROMOTIONS_CHANGED",
                "The promotions changed (version null → 4): review the prices and confirm again.",
                ",\"promotionVersion\":4");
        expect("prices-changed", 409, "PRICES_CHANGED",
                "The prices changed (20.00 → 25.00): review them and confirm again.",
                ",\"expectedGross\":20.00,\"gross\":25.00,\"lines\":[]");
        expect("item-sold-out", 422, "ITEM_SOLD_OUT", "Item " + ID + " is sold out.", "");
        expect("menu-selection-invalid", 422, "MENU_SELECTION_INVALID",
                "Menu LUNCH: group STARTER takes 1 to 1 choices, not 0.", "");
        expect("macro-not-published", 422, "MACRO_NOT_PUBLISHED",
                "Macro DRINKS is not in the published catalog.", "");
        expect("discount-limit-exceeded", 422, "DISCOUNT_LIMIT_EXCEEDED", "Line " + ID
                + " would be 52.00 % off its list price; the store allows at most 50 %.", "");
        expect("price-increase-not-allowed", 422, "PRICE_INCREASE_NOT_ALLOWED", "Line " + ID
                + ": a discount only lowers the price, and a reversal only restores it up to the list price of a "
                + "line without a reward.", "");
        expect("loyalty-customer-required", 422, "LOYALTY_CUSTOMER_REQUIRED",
                "Order " + ID + " has no customer to redeem a reward for.", "");
        expect("reward-needs-single-unit", 422, "REWARD_NEEDS_SINGLE_UNIT",
                "A reward is redeemed on one unit of line " + ID + ".", "");
        expect("loyalty-redeemed", 422, "LOYALTY_REDEEMED",
                "Order " + ID + " has a redeemed reward: its customer cannot change.", "");
        expect("reward-already-redeemed", 422, "REWARD_ALREADY_REDEEMED",
                "Line " + ID + " already carries a loyalty reward.", "");
    }

    @Test
    void ordersDeliveryCodes() throws Exception {
        expect("delivery-state", 409, "DELIVERY_STATE",
                "The delivery of order " + ID + " is PENDING: it cannot be delivered.", "");
        expect("delivery-zone-code-taken", 409, "DELIVERY_ZONE_CODE_TAKEN",
                "The store already has a delivery zone CENTRO.", "");
        expect("fee-item-not-published", 422, "FEE_ITEM_NOT_PUBLISHED",
                "The fee item of delivery zone CENTRO is not a fee in the published catalog.", "");
        expect("zone-inactive", 422, "ZONE_INACTIVE", "Delivery zone CENTRO is inactive.", "");
        expect("zone-other-store", 422, "ZONE_OTHER_STORE", "Delivery zone " + ID + " is not in this store.", "");
        expect("customer-required", 422, "CUSTOMER_REQUIRED",
                "A DELIVERY order is opened with its customer, address and zone.", "");
        expect("delivery-has-drafts", 422, "DELIVERY_HAS_DRAFTS",
                "Order " + ID + " has draft lines: confirm or delete them before the dispatch.", "");
        expect("delivery-nothing-confirmed", 422, "DELIVERY_NOTHING_CONFIRMED",
                "Order " + ID + " has no confirmed round to deliver.", "");
        expect("courier-not-in-store", 422, "COURIER_NOT_IN_STORE",
                "Employee " + ID + " is not an active employee of this store.", "");
        expect("delivery-not-delivered", 422, "DELIVERY_NOT_DELIVERED",
                "Order " + ID + " is closed only once its delivery is DELIVERED.", "");
        expect("delivery-in-progress", 422, "DELIVERY_IN_PROGRESS",
                "The delivery of order " + ID + " is out or delivered: it cannot be cancelled.", "");
        expect("address-frozen", 422, "ADDRESS_FROZEN",
                "The delivery of order " + ID + " was dispatched: its address, zone and promise are frozen.", "");
        expect("fee-line-locked", 422, "FEE_LINE_LOCKED", "Line " + ID
                + " is the delivery fee: change the zone, or give a discount or an offer, instead.", "");
        expect("zone-fee-confirmed", 422, "ZONE_FEE_CONFIRMED",
                "The delivery fee is already confirmed: void it before changing the zone.", "");
    }

    private void expect(String name, int status, String code, String detail, String extra) throws Exception {
        String title = switch (status) {
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
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
