package com.xavier.backend.customers.controller;

import static com.xavier.backend.customers.CustomersFixtures.BO;
import static com.xavier.backend.customers.CustomersFixtures.POS;
import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.idem;
import static com.xavier.backend.customers.CustomersFixtures.pos;
import static com.xavier.backend.customers.CustomersFixtures.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.customers.CustomersFixtures.Pos;
import com.xavier.backend.customers.CustomersModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Limit changes and credit blocks (D73, spec §9: a limit change is a fact with reason and actor, and there is no
 * endpoint that sets a balance). {@code CREDIT_LIMIT_MANAGE} installation-wide or in any store (P38), audited.
 */
@CustomersModuleTest
class CreditAccountHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CustomersFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    Pos cashier;
    UUID customer;

    @BeforeEach
    void aCustomerWithoutCredit() throws Exception {
        cashier = pos(fixtures.newStore());
        customer = fixtures.newCustomer(cashier, "Credit " + token(), null, null);
    }

    @Test
    void theFirstLimitChangeOpensTheAccountAndEachChangeIsAFactWithItsReasonAndActor() throws Exception {
        changeLimit("100.00", "regular customer").andExpect(status().isOk())
                .andExpect(jsonPath("$.limit.amount").value(100.00))
                .andExpect(jsonPath("$.limit.currency").value("MZN"))
                .andExpect(jsonPath("$.balance.amount").value(0))
                .andExpect(jsonPath("$.available.amount").value(100.00))
                .andExpect(jsonPath("$.blocked").value(false));
        changeLimit("250.00", "pays on time").andExpect(status().isOk())
                .andExpect(jsonPath("$.limit.amount").value(250.00));

        assertThat(jdbc.sql("""
                        SELECT coalesce(old_limit::text, '-') || '>' || new_limit || ':' || reason || ':' || actor_kind
                        FROM customers.credit_limit_change WHERE customer_id = ? ORDER BY at, id""")
                .param(customer).query(String.class).list())
                .containsExactly("->100.0000:regular customer:ACCOUNT", "100.0000>250.0000:pays on time:ACCOUNT");
        assertThat(audits("CREDIT_LIMIT_CHANGED")).isEqualTo(2);
    }

    @Test
    void aReplayedLimitChangeIsRecordedOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            mvc.perform(idem(post(BO + "/" + customer + "/credit-limit-changes"), key)
                            .with(account(null, Permission.CREDIT_LIMIT_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                            .content(limitBody("80.00", "first")))
                    .andExpect(status().isOk());
        }

        assertThat(jdbc.sql("SELECT count(*) FROM customers.credit_limit_change WHERE customer_id = ?")
                .param(customer).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aLimitChangeNeedsAReasonAndANonNegativeLimit() throws Exception {
        changeLimit("100.00", " ").andExpect(status().isBadRequest());
        changeLimit("-1.00", "why").andExpect(status().isBadRequest());
        changeLimit("1.001", "why").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    }

    @Test
    void limitsAreManagedWithCreditLimitManageInAnyStore() throws Exception {
        mvc.perform(idem(post(BO + "/" + customer + "/credit-limit-changes"))
                        .with(account(UUID.randomUUID(), Permission.CREDIT_LIMIT_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(limitBody("10.00", "store manager")))
                .andExpect(status().isOk());
        mvc.perform(idem(post(BO + "/" + customer + "/credit-limit-changes"))
                        .with(account(null, Permission.CUSTOMER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(limitBody("10.00", "no")))
                .andExpect(status().isForbidden());
        mvc.perform(idem(post(BO + "/" + UUID.randomUUID() + "/credit-limit-changes"))
                        .with(account(null, Permission.CREDIT_LIMIT_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(limitBody("10.00", "who")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aBlockedAccountShowsBlockedUntilItIsUnblockedAndBothAreAudited() throws Exception {
        changeLimit("100.00", "open").andExpect(status().isOk());

        mvc.perform(idem(post(BO + "/" + customer + "/credit-block"))
                        .with(account(null, Permission.CREDIT_LIMIT_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "cheque bounced"}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.blocked").value(true));
        mvc.perform(get(POS + "/" + customer + "/credit").with(cashier.with(Permission.CUSTOMER_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.blocked").value(true));
        mvc.perform(idem(post(BO + "/" + customer + "/credit-unblock"))
                        .with(account(null, Permission.CREDIT_LIMIT_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "paid"}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.blocked").value(false));

        assertThat(audits("CREDIT_BLOCKED")).isEqualTo(1);
        assertThat(audits("CREDIT_UNBLOCKED")).isEqualTo(1);
    }

    @Test
    void aBlockNeedsAReasonAndAnAccount() throws Exception {
        mvc.perform(idem(post(BO + "/" + customer + "/credit-block"))
                        .with(account(null, Permission.CREDIT_LIMIT_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "no account"}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CREDIT_ACCOUNT_MISSING"));
        mvc.perform(idem(post(BO + "/" + customer + "/credit-block"))
                        .with(account(null, Permission.CREDIT_LIMIT_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thePosShowsTheAvailableCreditOfACustomer() throws Exception {
        mvc.perform(get(POS + "/" + customer + "/credit").with(cashier.with(Permission.CUSTOMER_VIEW)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CREDIT_ACCOUNT_MISSING"));
        changeLimit("75.50", "open").andExpect(status().isOk());

        mvc.perform(get(POS + "/" + customer + "/credit").with(cashier.with(Permission.CUSTOMER_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available.amount").value(75.50))
                .andExpect(jsonPath("$.reserved.amount").value(0));
        mvc.perform(get(POS + "/" + customer + "/credit").with(cashier.with(Permission.CUSTOMER_MANAGE)))
                .andExpect(status().isForbidden());
    }

    private ResultActions changeLimit(String newLimit, String reason) throws Exception {
        return mvc.perform(idem(post(BO + "/" + customer + "/credit-limit-changes"))
                .with(account(null, Permission.CREDIT_LIMIT_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                .content(limitBody(newLimit, reason)));
    }

    private static String limitBody(String newLimit, String reason) {
        return """
                {"newLimit": %s, "reason": "%s"}""".formatted(newLimit, reason);
    }

    private long audits(String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = ? AND entity_id = ?")
                .param(action).param(customer.toString()).query(Long.class).single();
    }
}
