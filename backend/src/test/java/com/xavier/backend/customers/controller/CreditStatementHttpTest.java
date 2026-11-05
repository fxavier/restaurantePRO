package com.xavier.backend.customers.controller;

import static com.xavier.backend.customers.CustomersFixtures.BO;
import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.mzn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.customers.CustomersModuleTest;
import com.xavier.backend.customers.LedgerEntryView;
import com.xavier.backend.organization.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The customer's statement ("extratos", spec §5, D73: entries with a running balance, document refs and
 * settlements) and the debts by age (aging buckets by debit age). {@code CUSTOMER_VIEW} in any store (P38).
 */
@CustomersModuleTest
class CreditStatementHttpTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 5);

    @Autowired
    MockMvc mvc;

    @Autowired
    CustomersFixtures fixtures;

    @Autowired
    CustomersApi customers;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MutableClock clock;

    UUID customer;
    UUID store;

    @BeforeEach
    void aCreditCustomer() throws Exception {
        customer = fixtures.creditCustomer("100000000.00");
        store = UUID.randomUUID();
    }

    @Test
    void theStatementRunsTheBalanceThroughEveryEntryWithItsDocumentAndSettlements() throws Exception {
        UUID document = UUID.randomUUID();
        UUID oldest = sale("30.00", document).id();
        Instant afterFirst = clock.instant();
        UUID newest = sale("50.00", UUID.randomUUID()).id();
        UUID payment = UUID.randomUUID();
        clock.advance(Duration.ofSeconds(1));
        UUID credit = tx.execute(s -> customers.postCredit(payment, customer, mzn("60.00"), List.of(), store, DAY))
                .id();
        clock.advance(Duration.ofSeconds(1));
        tx.execute(s -> customers.reverseCredit(UUID.randomUUID(), payment, mzn("40.00")));

        JsonNode statement = statement(null, null);

        assertThat(statement.get("openingBalance").get("amount").decimalValue()).isEqualByComparingTo("0");
        assertThat(statement.get("closingBalance").get("amount").decimalValue()).isEqualByComparingTo("60.00");
        JsonNode lines = statement.get("lines");
        assertThat(lines.valueStream().map(l -> l.get("sourceType").asString()))
                .containsExactly("CREDIT_CHARGE", "CREDIT_CHARGE", "PAYMENT", "REFUND");
        assertThat(lines.valueStream().map(l -> l.get("balance").get("amount").decimalValue().setScale(2).toPlainString()))
                .containsExactly("30.00", "80.00", "20.00", "60.00");
        assertThat(lines.get(0).get("documentId").asString()).isEqualTo(document.toString());
        assertThat(lines.get(0).get("open").get("amount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(lines.get(1).get("open").get("amount").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(links(lines.get(2), true)).containsExactlyInAnyOrder(oldest + "=30.00", newest + "=30.00");
        assertThat(links(lines.get(2), false)).containsExactly(newest + "=30.00", oldest + "=10.00");
        assertThat(lines.get(3).get("settlements").valueStream()
                .map(s -> s.get("creditEntryId").asString() + ">" + s.get("debitEntryId").asString()))
                .containsExactly(credit + ">" + newest, credit + ">" + oldest);

        JsonNode later = statement(afterFirst.plusNanos(1000), null);
        assertThat(later.get("openingBalance").get("amount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(later.get("lines").size()).isEqualTo(3);
    }

    @Test
    void theStatementNeedsCustomerViewAndAKnownCustomer() throws Exception {
        mvc.perform(get(BO + "/" + customer + "/statement").with(account(UUID.randomUUID(), Permission.CUSTOMER_VIEW)))
                .andExpect(status().isOk());
        mvc.perform(get(BO + "/" + customer + "/statement").with(account(null, Permission.CREDIT_LIMIT_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BO + "/" + UUID.randomUUID() + "/statement").with(account(null, Permission.CUSTOMER_VIEW)))
                .andExpect(status().isNotFound());
        mvc.perform(get(BO + "/" + customer + "/statement").param("from", "2026-11-05T00:00:00Z")
                        .param("to", "2026-11-04T00:00:00Z").with(account(null, Permission.CUSTOMER_VIEW)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
    }

    @Test
    void debtsAreAgedByTheAgeOfEachOpenDebitAsOfADate() throws Exception {
        Instant start = clock.instant();
        sale("3000000.00", UUID.randomUUID());
        clock.advance(Duration.ofDays(35));
        sale("2000000.00", UUID.randomUUID());
        clock.advance(Duration.ofDays(35));
        sale("1000000.00", UUID.randomUUID());
        clock.advance(Duration.ofDays(1));
        tx.execute(s -> customers.postCredit(UUID.randomUUID(), customer, mzn("1500000.00"), List.of(), store, DAY));

        JsonNode now = debtOf(start.plus(Duration.ofDays(100)));
        assertThat(amounts(now)).containsExactly("4500000.00", "1000000.00", "0.00", "2000000.00", "1500000.00");

        JsonNode before = debtOf(start.plus(Duration.ofDays(50)));
        assertThat(amounts(before)).containsExactly("5000000.00", "2000000.00", "3000000.00", "0.00", "0.00");

        mvc.perform(get(BO + "/debts").param("minBalance", "1000000").with(account(null, Permission.CUSTOMER_MANAGE)))
                .andExpect(status().isForbidden());
    }

    /** The settlements ({@code settling}) or the reopenings of a statement line, as "debit=amount". */
    private static List<String> links(JsonNode line, boolean settling) {
        return line.get("settlements").valueStream()
                .filter(link -> link.get("reversalEntryId").isNull() == settling)
                .map(link -> link.get("debitEntryId").asString() + "="
                        + link.get("amount").get("amount").decimalValue().setScale(2).toPlainString())
                .toList();
    }

    private JsonNode debtOf(Instant asOf) throws Exception {
        String body = mvc.perform(get(BO + "/debts").param("asOf", asOf.toString()).param("minBalance", "1000000")
                        .param("size", "200").with(account(UUID.randomUUID(), Permission.CUSTOMER_VIEW)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return CustomersFixtures.json(body).get("content").valueStream()
                .filter(row -> row.get("customerId").asString().equals(customer.toString()))
                .findFirst().orElseThrow();
    }

    private static List<String> amounts(JsonNode row) {
        return List.of("balance", "days0To30", "days31To60", "days61To90", "over90").stream()
                .map(field -> row.get(field).get("amount").decimalValue().setScale(2).toPlainString()).toList();
    }

    private JsonNode statement(Instant from, Instant to) throws Exception {
        var request = get(BO + "/" + customer + "/statement").with(account(null, Permission.CUSTOMER_VIEW));
        if (from != null) {
            request = request.param("from", from.toString());
        }
        if (to != null) {
            request = request.param("to", to.toString());
        }
        return CustomersFixtures.json(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private LedgerEntryView sale(String amount, UUID document) {
        clock.advance(Duration.ofSeconds(1));
        UUID charge = UUID.randomUUID();
        return tx.execute(s -> {
            customers.reserve(customer, mzn(amount), charge);
            return customers.postDebit(charge, document, store, DAY, mzn(amount));
        });
    }
}
