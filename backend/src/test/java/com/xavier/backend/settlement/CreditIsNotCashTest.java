package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * §14 #4, §7.7, D74: credit is never money received. A credit sale creates no Payment and no cash movement; the cash
 * session expects nothing for it and closes counting only the money in the drawer; the open-orders map and the
 * settlement view show it as credit, not as paid; reporting finds no payment fact for it; the order closes SETTLED
 * with nothing paid. Only the later debt payment is money received.
 */
@SettlementModuleTest
class CreditIsNotCashTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    SettlementFacts facts;

    Sale sale;
    UUID customer;
    LocalDate day;

    @BeforeEach
    void aSaleAndACustomer() throws Exception {
        sale = fixtures.sale("2"); // 25.00
        customer = customers.creditCustomer("100.00");
        day = LocalDate.parse(fixtures.currentDay(sale.cashier()).get("businessDate").asString());
    }

    @Test
    void aCreditSaleIsNeitherAPaymentNorACashMovementNorExpectedInTheDrawer() throws Exception {
        fixtures.creditCharge(sale, customer, "25.00").andExpect(status().isCreated());

        assertThat(fixtures.count("SELECT count(*) FROM settlement.payment WHERE store_id = ?", sale.store()))
                .isZero();
        assertThat(fixtures.count("SELECT count(*) FROM settlement.payment_attempt WHERE store_id = ?",
                sale.store())).isZero();
        assertThat(fixtures.movementsOfStore(sale.store())).isZero();
        assertThat(fixtures.session(sale.cashier(), sale.session()).get("expected")).isEmpty();
        assertThat(facts.paymentIds(sale.store(), day, day, PageRequest.of(0, 10))).isEmpty();
        mvc.perform(get("/api/v1/pos/settlement/open-orders").with(sale.cashier().with(Permission.ORDER_VIEW)))
                .andExpect(jsonPath("$[0].paid.amount").value(0))
                .andExpect(jsonPath("$[0].credit.amount").value(25.00))
                .andExpect(jsonPath("$[0].balance.amount").value(0))
                .andExpect(jsonPath("$[0].financialState").value("SETTLED"));

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                        UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid.amount").value(0))
                .andExpect(jsonPath("$.credit.amount").value(25.00))
                .andExpect(jsonPath("$.financialState").value("SETTLED"));
        fixtures.closeSession(sale, "0");
        assertThat(fixtures.session(sale.cashier(), sale.session()).get("expected")).isEmpty();
    }

    @Test
    void partOnCreditAndPartInCashExpectsOnlyTheCash() throws Exception {
        fixtures.creditCharge(sale, customer, "10.00").andExpect(status().isCreated());
        fixtures.payCash(sale, "15.00", "20.00").andExpect(status().isCreated());

        assertThat(fixtures.decimal("SELECT coalesce(sum(amount), 0) FROM cash.cash_movement WHERE store_id = ?",
                sale.store())).isEqualByComparingTo("15.00");
        assertThat(fixtures.session(sale.cashier(), sale.session()).get("expected").get(0).get("amount")
                .get("amount").decimalValue()).isEqualByComparingTo("15.00");
        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                        UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid.amount").value(15.00));
        fixtures.closeSession(sale, "15.00");
    }

    @Test
    void onlyTheDebtPaymentIsMoneyReceived() throws Exception {
        fixtures.creditCharge(sale, customer, "25.00").andExpect(status().isCreated());

        fixtures.payDebt(sale.cashier(), customer, "CASH", "25.00", "25.00", UUID.randomUUID().toString())
                .andExpect(status().isCreated());

        assertThat(fixtures.decimal("SELECT coalesce(sum(amount), 0) FROM cash.cash_movement WHERE store_id = ?",
                sale.store())).isEqualByComparingTo("25.00");
        assertThat(facts.paymentIds(sale.store(), day, day, PageRequest.of(0, 10)).getContent()).hasSize(1);
        assertThat(facts.creditChargeIds(sale.store(), day, day, PageRequest.of(0, 10)).getContent()).hasSize(1);
    }
}
