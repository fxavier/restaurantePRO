package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.model.event.CashDrawerKickRequested;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * D158, P59, A66 (settlement half): a payment or a refund of a CASH-kind method recorded on a terminal publishes one
 * {@link CashDrawerKickRequested} with its own id as the source and its recording time as {@code requestedAt}, in the
 * recording transaction. A card (external
 * terminal) payment or refund and a credit sale publish none.
 */
@SettlementModuleTest
@RecordApplicationEvents
class SettlementPublishesKickTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JdbcClient jdbc;

    Sale sale;

    @BeforeEach
    void aSale() throws Exception {
        Mockito.reset(approvers);
        when(approvers.verify(any(), any(), eq(Permission.PAYMENT_REFUND_APPROVE), any()))
                .thenReturn(UUID.randomUUID());
        sale = fixtures.sale("2"); // 25.00
    }

    @Test
    void aCashPaymentRequestsOneKickOnItsTerminal() throws Exception {
        fixtures.payCash(sale, "25.00", "30.00").andExpect(status().isCreated());
        UUID payment = fixtures.paymentOf(sale.order());

        assertThat(kicksOf(sale.store())).singleElement().isEqualTo(new CashDrawerKickRequested("PAYMENT", payment,
                sale.store(), sale.cashier().terminalId(), confirmedAt("payment", payment)));
    }

    @Test
    void aCardPaymentRequestsNoKick() throws Exception {
        String card = SettlementFixtures.freshCode("CARD");
        fixtures.method(card, "EXTERNAL_TERMINAL", null);
        UUID attempt = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), card, "25.00", null,
                UUID.randomUUID().toString()));
        fixtures.confirm(sale.cashier(), attempt, "AUTH-1").andExpect(status().isOk());

        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
        assertThat(kicksOf(sale.store())).isEmpty();
    }

    @Test
    void aCreditSaleRequestsNoKick() throws Exception {
        UUID customer = customers.creditCustomer("100.00");

        fixtures.creditCharge(sale, customer, "25.00").andExpect(status().isCreated());

        assertThat(kicksOf(sale.store())).isEmpty();
    }

    @Test
    void aCashRefundRequestsOneKickOnTheRefundersTerminal() throws Exception {
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        UUID payment = fixtures.paymentOf(sale.order());
        Pos refunder = fixtures.secondCashier(sale);

        UUID refund = SettlementFixtures.id(fixtures.refund(refunder, payment, "10.00")
                .andExpect(status().isCreated()));

        assertThat(kicksOf(sale.store())).containsExactly(
                new CashDrawerKickRequested("PAYMENT", payment, sale.store(), sale.cashier().terminalId(),
                        confirmedAt("payment", payment)),
                new CashDrawerKickRequested("REFUND", refund, sale.store(), refunder.terminalId(),
                        confirmedAt("refund", refund)));
    }

    @Test
    void aCardRefundRequestsNoKick() throws Exception {
        String card = SettlementFixtures.freshCode("CARD");
        fixtures.method(card, "EXTERNAL_TERMINAL", null);
        UUID attempt = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), card, "25.00", null,
                UUID.randomUUID().toString()));
        fixtures.confirm(sale.cashier(), attempt, "AUTH-1").andExpect(status().isOk());

        fixtures.refund(sale.cashier(), fixtures.paymentOfAttempt(attempt), "10.00", "REF-1",
                UUID.randomUUID().toString()).andExpect(status().isCreated());

        assertThat(kicksOf(sale.store())).isEmpty();
    }

    /** When the payment or refund was recorded: the kick's {@code requestedAt} (D158 amended, S60 review). */
    private Instant confirmedAt(String table, UUID id) {
        return jdbc.sql("SELECT confirmed_at FROM settlement." + table + " WHERE id = ?").param(id)
                .query(Timestamp.class).single().toInstant();
    }

    private java.util.List<CashDrawerKickRequested> kicksOf(UUID store) {
        return events.stream(CashDrawerKickRequested.class).filter(kick -> kick.storeId().equals(store)).toList();
    }
}
