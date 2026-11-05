package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * A7 (spec §14 #3, D50 §7.5): two terminals pay the full balance of one bill at the same instant → one Payment and
 * one cash movement; the other is refused with 422. A repeat with the same Idempotency-Key replays the response.
 */
@SettlementModuleTest
class DoublePaymentRaceTest {

    @Autowired
    SettlementFixtures fixtures;

    @Test
    void twoTerminalsPayingTheWholeBillAtOnceMakeOnePaymentAndOneRefusal() throws Exception {
        Sale sale = fixtures.sale("2"); // 25.00
        Pos second = fixtures.secondCashier(sale);

        List<MockHttpServletResponse> results = CashFixtures.race(
                () -> fixtures.pay(sale.cashier(), sale.order(), "CASH", "25.00", "25.00",
                        UUID.randomUUID().toString()).andReturn().getResponse(),
                () -> fixtures.pay(second, sale.order(), "CASH", "25.00", "25.00",
                        UUID.randomUUID().toString()).andReturn().getResponse());

        assertThat(results).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 422);
        assertThat(results).filteredOn(r -> r.getStatus() == 422).singleElement()
                .satisfies(r -> assertThat(r.getContentAsString()).contains("\"code\":\"AMOUNT_EXCEEDS_BALANCE\""));
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
        assertThat(fixtures.receipts(sale.order())).isEqualTo(1);
    }

    @Test
    void repeatingAPaymentWithTheSameKeyReplaysItAndPaysOnce() throws Exception {
        Sale sale = fixtures.sale("2");
        String key = UUID.randomUUID().toString();

        String first = fixtures.pay(sale.cashier(), sale.order(), "CASH", "25.00", "30.00", key)
                .andReturn().getResponse().getContentAsString();
        MockHttpServletResponse again = fixtures.pay(sale.cashier(), sale.order(), "CASH", "25.00", "30.00", key)
                .andReturn().getResponse();

        assertThat(again.getStatus()).isEqualTo(201);
        assertThat(CashFixtures.json(again.getContentAsString())).isEqualTo(CashFixtures.json(first));
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
        assertThat(fixtures.receipts(sale.order())).isEqualTo(1);
    }
}
