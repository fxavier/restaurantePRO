package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * D77, D76: two terminals refunding one payment at once take the payment's target lock in turn (the order's
 * {@code order_settlement}, or a debt payment's {@code credit_account}), so the second sees the first's refund: one
 * refund, one 422 {@code REFUND_EXCEEDS_REFUNDABLE}, one movement. Both requests are proven to wait on the lock
 * ({@code pg_blocking_pids}) before it is released.
 */
@SettlementModuleTest
class RefundRaceTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    CustomersApi credit;

    @Autowired
    ApproverVerifier approvers;

    @BeforeEach
    void aManagerApproves() {
        when(approvers.verify(any(), any(), eq(Permission.PAYMENT_REFUND_APPROVE), any())).thenReturn(UUID.randomUUID());
    }

    @Test
    void twoRefundsOfOnePaymentRefundAtMostThePayment() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        UUID payment = fixtures.paymentOf(sale.order());
        Pos other = fixtures.secondCashier(sale);

        List<Integer> statuses = fixtures.raceBehindOrderLock(sale.order(),
                () -> code(fixtures.refund(sale.cashier(), payment, "15.00")),
                () -> code(fixtures.refund(other, payment, "15.00")));

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(fixtures.decimal("SELECT sum(amount) FROM settlement.refund WHERE payment_id = ?", payment))
                .isEqualByComparingTo("15.00");
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement m JOIN settlement.refund r ON r.id = "
                + "m.source_id WHERE r.payment_id = ?", payment)).isEqualTo(1);
    }

    @Test
    void twoRefundsOfOneDebtPaymentReopenTheDebtOnce() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID customer = customers.creditCustomer("100.00");
        fixtures.creditCharge(sale, customer, "25.00").andExpect(status().isCreated());
        JsonNode attempt = SettlementFixtures.json(fixtures.payDebt(sale.cashier(), customer, "CASH", "20.00",
                "20.00", UUID.randomUUID().toString()).andExpect(status().isCreated()));
        UUID payment = fixtures.paymentOfAttempt(UUID.fromString(attempt.get("id").asString()));
        Pos other = fixtures.secondCashier(sale);

        List<Integer> statuses = fixtures.raceBehindRowLock(
                "SELECT customer_id FROM customers.credit_account WHERE customer_id = ? FOR UPDATE", customer,
                () -> code(fixtures.refund(sale.cashier(), payment, "12.00")),
                () -> code(fixtures.refund(other, payment, "12.00")));

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(credit.debtOf(customer).amount()).isEqualByComparingTo("17.00");
    }

    private static int code(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }
}
