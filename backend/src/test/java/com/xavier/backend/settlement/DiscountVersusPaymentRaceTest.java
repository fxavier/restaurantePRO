package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * D105, D91: a settled discount and a payment of the rest of the bill race on one PAYMENT-locked order. Both are
 * released at once from behind a held {@code order_settlement} row ({@code pg_blocking_pids}, no sleeps), and the
 * second re-checks against what the first committed: exactly one succeeds (a 50 % discount, or the payment), the
 * other gets its 422, and the bill never falls below what was paid.
 */
@SettlementModuleTest
class DiscountVersusPaymentRaceTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    ApproverVerifier approvers;

    @Test
    void aDiscountAndThePaymentOfTheRestAtOnceLeaveOneOfThemAndTheBillCovered() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_DISCOUNT_APPROVE), any()))
                .thenReturn(UUID.randomUUID());
        Sale sale = fixtures.sale("2"); // 25.00
        // PAYMENT-locked first, so the racing payment leaves the order's version alone
        fixtures.payCash(sale, "1.00", "1.00").andExpect(status().isCreated());
        var second = fixtures.secondCashier(sale);
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        long version = fixtures.orderVersion(sale.cashier(), sale.order());

        List<MockHttpServletResponse> results = fixtures.raceBehindOrderLock(sale.order(),
                () -> fixtures.settledDiscount(sale.cashier(), sale.order(), version, """
                        "lines": [{"lineId": "%s"}], "kind": "DISCOUNT", "percent": 50, "reason": "regular\""""
                        .formatted(line), true).andReturn().getResponse(),
                () -> fixtures.pay(second, sale.order(), "CASH", "24.00", "24.00", UUID.randomUUID().toString())
                        .andReturn().getResponse());

        MockHttpServletResponse discount = results.get(0);
        MockHttpServletResponse payment = results.get(1);
        JsonNode settlement = SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order()));
        BigDecimal due = new BigDecimal(settlement.get("due").get("amount").asString());
        BigDecimal paid = new BigDecimal(settlement.get("paid").get("amount").asString());
        if (discount.getStatus() == 200) {
            assertThat(payment.getStatus()).isEqualTo(422);
            assertThat(payment.getContentAsString()).contains("AMOUNT_EXCEEDS_BALANCE");
            assertThat(due).isEqualByComparingTo("12.50");
            assertThat(paid).isEqualByComparingTo("1.00");
        } else {
            assertThat(payment.getStatus()).isEqualTo(201);
            assertThat(discount.getStatus()).isEqualTo(422);
            assertThat(discount.getContentAsString()).contains("DISCOUNT_EXCEEDS_UNPAID");
            assertThat(due).isEqualByComparingTo("25.00");
            assertThat(paid).isEqualByComparingTo("25.00");
            assertThat(fixtures.count("SELECT count(*) FROM orders.order_line_price_adjustment WHERE order_id = ?",
                    sale.order())).as("the refused discount left no fact").isZero();
        }
        assertThat(paid).isLessThanOrEqualTo(due);
    }
}
