package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.service.CreditSaleSteps;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S29 review Low (2): a cancel that lands while the sale is between its steps (s1 committed, s2 about to issue, no
 * transaction held) releases the charge and turns the draft back into a paid sale's; the sale's s2 then issues that
 * document and its s3 finds the charge RELEASED and refuses (409 {@code ILLEGAL_CHARGE_STATE}) before anything is
 * posted, so the customer owes nothing. The sale is held at the start of s2 by a latch, so the cancel really runs
 * between s1 and s3. (A cancel that lands while fiscal holds the document's lock to number it waits, then fails on
 * the changed document and rolls back: nothing is released.)
 */
@SettlementModuleTest
class CreditChargeCancelRaceTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    CustomersApi credit;

    @Autowired
    CreditSaleSteps steps;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void realSteps() {
        Mockito.reset(steps);
    }

    @Test
    void aCancelDuringTheIssueLeavesNothingPosted() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID customer = customers.creditCustomer("100.00");
        CountDownLatch issuing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(call -> {
            issuing.countDown();
            release.await(30, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(steps).issue(any(), any());

        try (var pool = Executors.newSingleThreadExecutor()) {
            var sold = pool.submit(() -> fixtures.creditCharge(sale.cashier(), sale.order(), customer, "25.00", null,
                    UUID.randomUUID().toString()).andReturn());
            assertThat(issuing.await(30, TimeUnit.SECONDS)).as("the sale reached s2").isTrue();
            UUID charge = jdbc.sql("SELECT id FROM settlement.credit_charge WHERE order_id = ?").param(sale.order())
                    .query(UUID.class).single();

            fixtures.cancelCharge(sale.cashier(), charge, "customer paid cash instead")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));
            release.countDown();
            MvcResult sale3 = sold.get(30, TimeUnit.SECONDS);

            assertThat(sale3.getResponse().getStatus()).isEqualTo(409);
            assertThat(SettlementFixtures.json(sale3.getResponse().getContentAsString()).get("code").asString())
                    .isEqualTo("ILLEGAL_CHARGE_STATE");
        }
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(credit.credit(customer).reserved().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fixtures.count("SELECT count(*) FROM settlement.credit_charge WHERE order_id = ? AND status = "
                + "'RELEASED'", sale.order())).isEqualTo(1);
    }
}
