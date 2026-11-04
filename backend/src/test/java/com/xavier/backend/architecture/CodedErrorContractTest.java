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
                    new IllegalStateException("adapter offline"))));

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
