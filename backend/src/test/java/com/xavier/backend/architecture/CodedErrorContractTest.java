package com.xavier.backend.architecture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.catalog.exception.ReleaseValidationFailedException;
import com.xavier.backend.catalog.model.dto.ReleaseValidationError;
import com.xavier.backend.dining.exception.IllegalOccupationStateException;
import com.xavier.backend.dining.exception.StillOccupiedException;
import com.xavier.backend.dining.exception.TableOccupiedException;
import com.xavier.backend.dining.exception.TableUnavailableException;
import com.xavier.backend.fiscal.FiscalIssueFailedException;
import com.xavier.backend.identity.exception.LoginThrottledException;
import com.xavier.backend.identity.exception.PinChangeNotRequiredException;
import com.xavier.backend.identity.exception.PinUnchangedException;
import com.xavier.backend.orders.exception.OrderConflictException;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.production.exception.ProductionRule;
import com.xavier.backend.production.exception.ProductionRuleViolationException;
import com.xavier.backend.production.model.Printer;
import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.AttemptStatus;
import java.math.BigDecimal;
import java.time.Duration;
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
            Map.entry("order-not-open", () -> OrderConflictException.notOpen(ID)),
            Map.entry("line-not-draft", () -> OrderConflictException.lineNotDraft(ID)),
            Map.entry("catalog-changed", () -> OrderConflictException.catalogChanged(3, 4)),
            Map.entry("order-in-settlement", () -> OrderRuleException.inSettlement(ID)),
            Map.entry("quantity-exceeds-net", () -> OrderRuleException.quantityExceedsNet(ID, new BigDecimal("1.500"))),
            Map.entry("payment-in-doubt", () -> SettlementConflictException.inDoubt("order", ID, OTHER)),
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
            Map.entry("duplicate-route", () -> new ProductionRuleViolationException(ProductionRule.DUPLICATE_ROUTE,
                    "The destination KITCHEN appears twice")));

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

    private void expect(String name, int status, String code, String detail, String extra) throws Exception {
        String title = switch (status) {
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Content";
            case 429 -> "Too Many Requests";
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
