package com.xavier.backend.settlement;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.settlement.SettlementFixtures.Sale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** D67, D68, A17: equal parts are AMOUNT partitions of the residual sharing a group; the leftover goes first. */
@SettlementModuleTest
class EqualPartsTest {

    @Autowired
    SettlementFixtures fixtures;

    @Test
    void oneHundredInThreePartsIs3334And3333And3333() throws Exception {
        Sale sale = fixtures.sale("8"); // 8 x 12.50 = 100.00

        fixtures.equalParts(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].due.amount").value(33.34))
                .andExpect(jsonPath("$[1].due.amount").value(33.33))
                .andExpect(jsonPath("$[2].due.amount").value(33.33))
                .andExpect(jsonPath("$[2].partNo").value(3))
                .andExpect(jsonPath("$[2].parts").value(3))
                .andExpect(jsonPath("$[0].groupId").isNotEmpty());
        fixtures.settlement(sale.cashier(), sale.order()).andExpect(jsonPath("$.residualBalance.amount").value(0));
    }

    @Test
    void aResidualAlreadyPartitionedHasNothingToSplit() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.equalParts(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), 2)
                .andExpect(status().isCreated());

        fixtures.equalParts(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), 2)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_SPLIT"));
    }

    @Test
    void partsOutsideTwoToFiftyAreInvalid() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.equalParts(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), 51)
                .andExpect(status().isBadRequest());
    }
}
