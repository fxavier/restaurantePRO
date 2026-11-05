package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.model.event.CreditChargePosted;
import com.xavier.backend.settlement.service.CreditChargeSweeper;
import com.xavier.backend.settlement.service.CreditSaleSteps;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D74 recovery: the sale stops after its document was issued (s2) and before the debit was posted (s3), as a crash
 * would leave it. The {@link CreditChargeSweeper}, with no request and no actor (as after a restart), posts the debit
 * once, audited as the system, and publishes {@code CreditChargePosted}; the client's retry then answers POSTED with
 * nothing added. The sweeper leaves alone a charge whose document is not issued, and one whose order a request holds.
 */
@SettlementModuleTest
class CreditChargeRecoveryTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    CustomersApi credit;

    @Autowired
    CreditSaleSteps steps;

    @Autowired
    CreditChargeSweeper sweeper;

    @Autowired
    InternalFiscalAdapter fiscalAdapter;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    Sale sale;
    UUID customer;
    String key;

    @BeforeEach
    void aSaleAndACustomer() throws Exception {
        sale = fixtures.sale("2");
        customer = customers.creditCustomer("100.00");
        key = UUID.randomUUID().toString();
    }

    @AfterEach
    void realSteps() {
        Mockito.reset(steps, fiscalAdapter);
    }

    @Test
    void theSweeperPostsTheDebitOfAnIssuedDocumentOnceAsTheSystem(PublishedEvents events) throws Exception {
        crashAfterTheIssue();

        sweeper.sweep();
        sweeper.sweep();

        assertThat(status()).isEqualTo("POSTED");
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("25.00");
        assertThat(credit.credit(customer).reserved().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE action = 'CREDIT_CHARGE_POSTED' "
                + "AND entity_id = ? AND actor_kind = 'SYSTEM'", charge().toString())).isEqualTo(1);
        assertThat(events.ofType(CreditChargePosted.class).matching(e -> e.chargeId().equals(charge())))
                .hasSize(1);

        fixtures.creditCharge(sale.cashier(), sale.order(), customer, "25.00", null, key)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"));
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("25.00");
        assertThat(fixtures.issuedNumber(sale.order())).isEqualTo(1);
    }

    @Test
    void aChargeWhoseDocumentIsNotIssuedStaysReserved() throws Exception {
        Mockito.doThrow(new IllegalStateException("adapter offline")).when(fiscalAdapter).issue(any());
        fixtures.creditCharge(sale.cashier(), sale.order(), customer, "25.00", null, key)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isUnprocessableContent());

        sweeper.sweep();

        assertThat(status()).isEqualTo("RESERVED");
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void theSweeperSkipsAnOrderThatARequestHolds() throws Exception {
        crashAfterTheIssue();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.sql("SELECT order_id FROM settlement.order_settlement WHERE order_id = ? FOR UPDATE")
                        .param(sale.order()).query(UUID.class).single();
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            locked.await(30, TimeUnit.SECONDS);

            sweeper.sweep(); // SKIP LOCKED: returns at once, the held order untouched

            assertThat(status()).isEqualTo("RESERVED");
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
        sweeper.sweep();
        assertThat(status()).isEqualTo("POSTED");
    }

    /** s1 and s2 commit; s3 fails, as a crash between them would leave the sale. */
    private void crashAfterTheIssue() throws Exception {
        Mockito.doThrow(new IllegalStateException("crash before s3")).when(steps).post(any(), any());
        fixtures.creditCharge(sale.cashier(), sale.order(), customer, "25.00", null, key)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isInternalServerError());
        Mockito.reset(steps);
        assertThat(status()).isEqualTo("RESERVED");
        assertThat(fixtures.issuedNumber(sale.order())).as("s2 committed").isEqualTo(1);
    }

    private UUID charge() {
        return jdbc.sql("SELECT id FROM settlement.credit_charge WHERE order_id = ?").param(sale.order())
                .query(UUID.class).single();
    }

    private String status() {
        return jdbc.sql("SELECT status FROM settlement.credit_charge WHERE order_id = ?").param(sale.order())
                .query(String.class).single();
    }
}
