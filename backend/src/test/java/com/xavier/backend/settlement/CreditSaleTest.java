package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * A20, D74: a credit sale puts an order's target on a customer's account in three steps (reserve and draft, issue,
 * post). It creates no Payment and no cash movement (§7.7, §14 #4), counts in the financial state, and the order
 * then closes SETTLED with its document on account.
 */
@SettlementModuleTest
class CreditSaleTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    CustomersApi credit;

    Sale sale;
    UUID customer;

    @BeforeEach
    void aSaleAndACustomerWithCredit() throws Exception {
        sale = fixtures.sale("2"); // 25.00
        customer = customers.creditCustomer("100.00");
    }

    @Test
    void theWholeOrderOnAccountIsPostedSettledAndClosesWithNoMoneyReceived() throws Exception {
        JsonNode charge = SettlementFixtures.json(fixtures.creditCharge(sale, customer, "25.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.targetType").value("ORDER"))
                .andExpect(jsonPath("$.customerId").value(customer.toString()))
                .andExpect(jsonPath("$.amount.amount").value(25.00)));
        String document = charge.get("documentId").asString();

        assertThat(fixtures.count("SELECT count(*) FROM fiscal.fiscal_document WHERE id = ?::uuid AND status = "
                + "'ISSUED' AND on_account AND customer_id = ?", document, customer)).isEqualTo(1);
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("25.00");
        assertThat(fixtures.payments(sale.order())).isZero();
        assertThat(fixtures.movementsOfStore(sale.store())).as("credit is never money received").isZero();
        fixtures.settlement(sale.cashier(), sale.order())
                .andExpect(jsonPath("$.financialState").value("SETTLED"))
                .andExpect(jsonPath("$.paid.amount").value(0))
                .andExpect(jsonPath("$.credit.amount").value(25.00))
                .andExpect(jsonPath("$.balance.amount").value(0))
                .andExpect(jsonPath("$.fiscalState").value("ISSUED"))
                .andExpect(jsonPath("$.creditCharges[0].status").value("POSTED"));

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                        UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("CLOSED"));
        assertThat(fixtures.documents(sale.order())).as("the credit sale's document is the order's").isEqualTo(1);
    }

    @Test
    void partOnAccountAndTheRestInCashCloses() throws Exception {
        fixtures.creditCharge(sale, customer, "10.00").andExpect(status().isCreated());
        fixtures.settlement(sale.cashier(), sale.order())
                .andExpect(jsonPath("$.financialState").value("PARTIALLY_SETTLED"))
                .andExpect(jsonPath("$.residualBalance.amount").value(15.00));

        fixtures.payCash(sale, "15.01", "20.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AMOUNT_EXCEEDS_BALANCE"));
        fixtures.payCash(sale, "15.00", "20.00").andExpect(status().isCreated());

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void aReplayWithTheSameKeyPostsOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = fixtures.creditCharge(sale.cashier(), sale.order(), customer, "25.00", null, key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String again = fixtures.creditCharge(sale.cashier(), sale.order(), customer, "25.00", null, key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(SettlementFixtures.json(again).get("id")).isEqualTo(SettlementFixtures.json(first).get("id"));
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("25.00");
    }

    @Test
    void moreThanTheBalanceIsRefused() throws Exception {
        fixtures.creditCharge(sale, customer, "25.01").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AMOUNT_EXCEEDS_BALANCE"));
    }

    @Test
    void beyondTheLimitNothingIsReservedNorFrozen() throws Exception {
        UUID poor = customers.creditCustomer("20.00");

        fixtures.creditCharge(sale, poor, "25.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CREDIT_LIMIT_EXCEEDED"));

        assertThat(credit.credit(poor).reserved().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fixtures.documents(sale.order())).isZero();
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
    }

    @Test
    void aCustomerWithoutCreditAccountIsRefused() throws Exception {
        UUID walkIn = customers.newCustomer(CustomersFixtures.pos(customers.newStore()), "Walk in", null, null);

        fixtures.creditCharge(sale, walkIn, "5.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CREDIT_ACCOUNT_MISSING"));
    }

    @Test
    void aSecondChargeOnTheSameTargetIsAConflict() throws Exception {
        fixtures.creditCharge(sale, customer, "10.00").andExpect(status().isCreated());

        fixtures.creditCharge(sale, customer, "5.00").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDIT_CHARGE_EXISTS"));
    }

    @Test
    void anOpenAttemptOnTheTargetBlocksTheCharge() throws Exception {
        String method = SettlementFixtures.freshCode("EXT");
        fixtures.method(method, "EXTERNAL_TERMINAL", null);
        fixtures.pay(sale.cashier(), sale.order(), method, "5.00", null, UUID.randomUUID().toString())
                .andExpect(status().isCreated());

        fixtures.creditCharge(sale, customer, "10.00").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_DOUBT"));
    }

    @Test
    void aDocumentIssuedWithoutTheCustomerIsNotPutOnAccount() throws Exception {
        fixtures.issue(sale.cashier(), sale.order(), UUID.randomUUID().toString()).andExpect(status().isCreated());

        fixtures.creditCharge(sale, customer, "25.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ALREADY_ISSUED"));
        assertThat(credit.credit(customer).reserved().amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void anItemsPartitionOnAccountGetsItsOwnDocumentAndTheRestIsPaid() throws Exception {
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        UUID partition = SettlementFixtures.id(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line, "1"));

        JsonNode charge = SettlementFixtures.json(fixtures.creditCharge(sale.cashier(), sale.order(), customer,
                        "12.50", partition, UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetType").value("PARTITION"))
                .andExpect(jsonPath("$.status").value("POSTED")));

        assertThat(fixtures.count("SELECT count(*) FROM fiscal.fiscal_document WHERE id = ?::uuid AND source_type = "
                + "'PARTITION' AND source_id = ? AND on_account", charge.get("documentId").asString(), partition))
                .isEqualTo(1);
        fixtures.partitions(sale.cashier(), sale.order())
                .andExpect(jsonPath("$[0].credit.amount").value(12.50))
                .andExpect(jsonPath("$[0].balance.amount").value(0))
                .andExpect(jsonPath("$[0].financialState").value("SETTLED"));
        fixtures.payCash(sale, "12.50", "12.50").andExpect(status().isCreated());
        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());
    }

    @Test
    void anAmountPartitionIsNotPutOnAccount() throws Exception {
        UUID partition = SettlementFixtures.id(fixtures.partitionAmount(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), "10.00"));

        fixtures.creditCharge(sale.cashier(), sale.order(), customer, "10.00", partition,
                        UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PARTITION_NOT_INVOICEABLE"));
    }
}
