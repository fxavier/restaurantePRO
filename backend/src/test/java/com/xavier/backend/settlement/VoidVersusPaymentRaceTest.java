package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * D91: a settled void and a payment of the whole bill race on one order. Both take order_settlement first and the
 * second re-checks against what the first committed, so exactly one succeeds and the bill never falls below what was
 * paid.
 */
@SettlementModuleTest
class VoidVersusPaymentRaceTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    ApproverVerifier approvers;

    @Test
    void aVoidAndAFullPaymentAtOnceLeaveOneOfThemAndTheBillCovered() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(UUID.randomUUID());
        Sale sale = fixtures.sale("2"); // 25.00
        var second = fixtures.secondCashier(sale);
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        long version = fixtures.orderVersion(sale.cashier(), sale.order());

        List<MockHttpServletResponse> results = fixtures.raceBehindOrderLock(sale.order(),
                () -> fixtures.settledVoid(sale.cashier(), sale.order(), version, line, "1",
                        UUID.randomUUID().toString()).andReturn().getResponse(),
                () -> fixtures.pay(second, sale.order(), "CASH", "25.00", "25.00", UUID.randomUUID().toString())
                        .andReturn().getResponse());

        assertThat(results).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 422);
        BigDecimal due = new BigDecimal(SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order()))
                .get("due").get("amount").asString());
        BigDecimal paid = new BigDecimal(SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order()))
                .get("paid").get("amount").asString());
        assertThat(paid).isLessThanOrEqualTo(due);
    }
}
