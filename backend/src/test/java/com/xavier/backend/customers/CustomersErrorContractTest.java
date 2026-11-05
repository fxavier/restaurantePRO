package com.xavier.backend.customers;

import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.mzn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.exception.CustomerRuleException;
import com.xavier.backend.customers.exception.CustomersConflictException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The exact client contract of every customers error with a stable code (status, {@code code}, {@code detail}, extra
 * properties), strict as {@code architecture.CodedErrorContractTest}: no property may appear or disappear. Kept in
 * the module so S20 stays in its write scope; the cases move into CodedErrorContractTest at integration.
 */
@CustomersModuleTest
@Import(CustomersErrorContractTest.Probe.class)
class CustomersErrorContractTest {

    static final UUID ID = UUID.fromString("0190a000-0000-7000-8000-0000000000d1");
    static final String PATH = "/api/v1/backoffice/customers-error-contract/";

    static final Map<String, Supplier<RuntimeException>> ERRORS = Map.ofEntries(
            Map.entry("inactive", () -> CustomerRuleException.inactive(ID)),
            Map.entry("account-missing", () -> CustomerRuleException.accountMissing(ID)),
            Map.entry("blocked", () -> CustomerRuleException.blocked(ID)),
            Map.entry("limit-exceeded", () -> CustomerRuleException.limitExceeded(mzn("60.00"), mzn("40.00"))),
            Map.entry("exceeds-debt", () -> CustomerRuleException.exceedsDebt(mzn("50.01"), mzn("50.00"))),
            Map.entry("exceeds-credit", () -> CustomerRuleException.exceedsCredit(mzn("20.01"), mzn("20.00"))),
            Map.entry("invalid-amount", () -> CustomerRuleException.invalidAmount(
                    "The amount 1.001 has more decimals than MZN allows.")),
            Map.entry("invalid-period", () -> CustomerRuleException.invalidPeriod(
                    Instant.parse("2026-11-05T00:00:00Z"), Instant.parse("2026-11-04T00:00:00Z"))),
            Map.entry("reservation-not-active", () -> CustomersConflictException.reservationClosed(ID, "CONSUMED",
                    "be released")),
            Map.entry("phone-exists", () -> CustomersConflictException.phoneExists(ID, "258841234567")),
            Map.entry("account-exists", () -> CustomersConflictException.accountExists(ID)),
            Map.entry("source-mismatch", () -> CustomersConflictException.sourceMismatch("debt payment", ID)));

    @Autowired
    MockMvc mvc;

    @Test
    void customerAndCreditRules() throws Exception {
        expect("inactive", 422, "CUSTOMER_INACTIVE", "Customer " + ID + " is inactive.", "");
        expect("account-missing", 422, "CREDIT_ACCOUNT_MISSING", "Customer " + ID + " has no credit account.", "");
        expect("blocked", 422, "CREDIT_BLOCKED", "The credit of customer " + ID + " is blocked.", "");
        expect("invalid-amount", 422, "INVALID_AMOUNT", "The amount 1.001 has more decimals than MZN allows.", "");
        expect("invalid-period", 422, "INVALID_PERIOD",
                "The period starts (2026-11-05T00:00:00Z) after it ends (2026-11-04T00:00:00Z).", "");
    }

    @Test
    void amountRulesCarryTheAmountThatWasAvailable() throws Exception {
        expect("limit-exceeded", 422, "CREDIT_LIMIT_EXCEEDED",
                "The amount 60.00 is more than the available credit 40.00.", ",\"available\":40.00");
        expect("exceeds-debt", 422, "AMOUNT_EXCEEDS_DEBT", "The amount 50.01 is more than the open debt 50.00.",
                ",\"debt\":50.00");
        expect("exceeds-credit", 422, "AMOUNT_EXCEEDS_CREDIT",
                "The amount 20.01 is more than the 20.00 of the debt payment still to reverse.",
                ",\"refundable\":20.00");
    }

    @Test
    void conflicts() throws Exception {
        expect("reservation-not-active", 409, "RESERVATION_NOT_ACTIVE",
                "The credit reservation of charge " + ID + " is CONSUMED and cannot be released.", "");
        expect("phone-exists", 409, "PHONE_EXISTS", "Customer " + ID + " already has the phone 258841234567.", "");
        expect("account-exists", 409, "CREDIT_ACCOUNT_EXISTS",
                "The credit account of customer " + ID + " was opened at the same time; retry.", "");
        expect("source-mismatch", 409, "CREDIT_SOURCE_MISMATCH",
                "The debt payment " + ID + " is already recorded with other values.", "");
    }

    private void expect(String name, int status, String code, String detail, String extra) throws Exception {
        String title = status == 409 ? "Conflict" : "Unprocessable Content";
        String body = "{\"title\":\"" + title + "\",\"status\":" + status + ",\"detail\":\"" + detail
                + "\",\"instance\":\"" + PATH + name + "\",\"code\":\"" + code + "\"" + extra + "}";
        mvc.perform(get(PATH + name).with(account(null, Permission.CUSTOMER_VIEW)))
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
