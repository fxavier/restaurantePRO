package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/** D91, A18: two terminals partition the same quantity at once; both wait on order_settlement; one wins. */
@SettlementModuleTest
class PartitionRaceTest {

    @Autowired
    SettlementFixtures fixtures;

    @Test
    void twoConcurrentPartitionsOfTheSameQuantityMakeOneAndOneRefusal() throws Exception {
        Sale sale = fixtures.sale("2");
        var second = fixtures.secondCashier(sale);
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        long version = fixtures.orderVersion(sale.cashier(), sale.order());

        List<MockHttpServletResponse> results = fixtures.raceBehindOrderLock(sale.order(),
                () -> fixtures.partitionItems(sale.cashier(), sale.order(), version, UUID.randomUUID().toString(),
                        line, "2").andReturn().getResponse(),
                () -> fixtures.partitionItems(second, sale.order(), version, UUID.randomUUID().toString(),
                        line, "2").andReturn().getResponse());

        assertThat(results).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 422);
        assertThat(results).filteredOn(r -> r.getStatus() == 422).singleElement()
                .satisfies(r -> assertThat(r.getContentAsString())
                        .contains("\"code\":\"QUANTITY_ALREADY_PARTITIONED\""));
        assertThat(fixtures.count("SELECT count(*) FROM settlement.bill_partition WHERE order_id = ?", sale.order()))
                .isEqualTo(1);
    }
}
