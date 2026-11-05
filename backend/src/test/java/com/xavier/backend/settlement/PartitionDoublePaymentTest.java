package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * D69, D91, A17: payments per target. Two terminals paying the same part at once → one Payment and one movement;
 * the same key twice → one Payment; an attempt open on one part does not block another part.
 */
@SettlementModuleTest
class PartitionDoublePaymentTest {

    @Autowired
    SettlementFixtures fixtures;

    @Test
    void twoTerminalsPayingTheSamePartAtOnceMakeOnePaymentAndOneMovement() throws Exception {
        Sale sale = fixtures.sale("3"); // 37.50 in three parts of 12.50
        var second = fixtures.secondCashier(sale);
        UUID part = firstPart(sale, 3);

        List<MockHttpServletResponse> results = fixtures.raceBehindOrderLock(sale.order(),
                () -> fixtures.payPartition(sale.cashier(), sale.order(), part, "CASH", "12.50", "12.50",
                        UUID.randomUUID().toString()).andReturn().getResponse(),
                () -> fixtures.payPartition(second, sale.order(), part, "CASH", "12.50", "12.50",
                        UUID.randomUUID().toString()).andReturn().getResponse());

        assertThat(results).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 422);
        assertThat(results).filteredOn(r -> r.getStatus() == 422).singleElement()
                .satisfies(r -> assertThat(r.getContentAsString()).contains("\"code\":\"AMOUNT_EXCEEDS_BALANCE\""));
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
        assertThat(fixtures.receipts(sale.order())).isEqualTo(1);
    }

    @Test
    void payingAPartTwiceWithTheSameKeyPaysOnce() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID part = firstPart(sale, 2);
        String key = UUID.randomUUID().toString();

        String first = fixtures.payPartition(sale.cashier(), sale.order(), part, "CASH", "12.50", "20.00", key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String again = fixtures.payPartition(sale.cashier(), sale.order(), part, "CASH", "12.50", "20.00", key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(CashFixtures.json(again)).isEqualTo(CashFixtures.json(first));
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM settlement.payment_allocation a JOIN settlement.payment p "
                + "ON p.id = a.payment_id WHERE p.order_id = ? AND a.target_type = 'PARTITION' AND a.target_id = ?",
                sale.order(), part)).isEqualTo(1);
    }

    @Test
    void anAttemptOpenOnOnePartDoesNotBlockAnotherButBlocksItsOwn() throws Exception {
        Sale sale = fixtures.sale("2");
        String card = SettlementFixtures.freshCode("CARD");
        fixtures.method(card, "EXTERNAL_TERMINAL", null);
        fixtures.equalParts(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), 2)
                .andExpect(status().isCreated());
        var parts = SettlementFixtures.json(fixtures.partitions(sale.cashier(), sale.order()));
        UUID a = UUID.fromString(parts.get(0).get("id").asString());
        UUID b = UUID.fromString(parts.get(1).get("id").asString());
        fixtures.payPartition(sale.cashier(), sale.order(), a, card, "12.50", null, UUID.randomUUID().toString())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));

        fixtures.payPartition(sale.cashier(), sale.order(), a, "CASH", "12.50", "12.50", UUID.randomUUID().toString())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_IN_DOUBT"));
        fixtures.payPartition(sale.cashier(), sale.order(), b, "CASH", "12.50", "12.50", UUID.randomUUID().toString())
                .andExpect(status().isCreated());
    }

    private UUID firstPart(Sale sale, int parts) throws Exception {
        fixtures.equalParts(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), parts)
                .andExpect(status().isCreated());
        return UUID.fromString(SettlementFixtures.json(fixtures.partitions(sale.cashier(), sale.order())).get(0)
                .get("id").asString());
    }
}
