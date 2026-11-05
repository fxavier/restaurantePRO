package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * D67, A18: ITEMS and AMOUNT partitions of an order (12.50 an item, 16 % VAT). A quantity already in a LIVE partition
 * is refused; a partition with a payment cannot be cancelled; the first partition locks the order for payment.
 */
@SettlementModuleTest
class PartitionTest {

    @Autowired
    SettlementFixtures fixtures;

    @Test
    void anItemsPartitionTakesItsShareAndTheResidualKeepsTheRest() throws Exception {
        Sale sale = fixtures.sale("3"); // 37.50
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        long version = fixtures.orderVersion(sale.cashier(), sale.order());

        fixtures.partitionItems(sale.cashier(), sale.order(), version, UUID.randomUUID().toString(), line, "1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("ITEMS"))
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.due.amount").value(12.50))
                .andExpect(jsonPath("$.lines[0].tax.amount").value(1.72))
                .andExpect(jsonPath("$.financialState").value("UNSETTLED"))
                .andExpect(jsonPath("$.invoiced").value(false));

        fixtures.settlement(sale.cashier(), sale.order())
                .andExpect(jsonPath("$.residualBalance.amount").value(25.00))
                .andExpect(jsonPath("$.partitions.length()").value(1))
                .andExpect(jsonPath("$.coverage[0].partitioned").value(1));
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_header WHERE id = ? AND billing_lock = 'PAYMENT'",
                sale.order())).as("the first partition locks the order for payment").isEqualTo(1);
    }

    @Test
    void aQuantityAlreadyInALivePartitionIsRefused() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        fixtures.partitionItems(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString(), line, "2").andExpect(status().isCreated());

        fixtures.partitionItems(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                        UUID.randomUUID().toString(), line, "1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_ALREADY_PARTITIONED"));
    }

    @Test
    void moreThanTheNetQuantityIsRefused() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();

        fixtures.partitionItems(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                        UUID.randomUUID().toString(), line, "3")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_EXCEEDS_NET"));
    }

    @Test
    void aStaleOrderVersionIs409() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        long version = fixtures.orderVersion(sale.cashier(), sale.order());

        fixtures.partitionItems(sale.cashier(), sale.order(), version - 1, UUID.randomUUID().toString(), line, "1")
                .andExpect(status().isConflict());
    }

    @Test
    void anAmountPartitionIsAtMostTheResidual() throws Exception {
        Sale sale = fixtures.sale("2"); // 25.00
        long version = fixtures.orderVersion(sale.cashier(), sale.order());
        fixtures.payCash(sale, "5.00", "5.00").andExpect(status().isCreated());
        version = fixtures.orderVersion(sale.cashier(), sale.order());

        fixtures.partitionAmount(sale.cashier(), sale.order(), version, "20.01")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AMOUNT_EXCEEDS_RESIDUAL"));
        fixtures.partitionAmount(sale.cashier(), sale.order(), version, "20.00")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.due.amount").value(20.00));
        fixtures.settlement(sale.cashier(), sale.order()).andExpect(jsonPath("$.residualBalance.amount").value(0));
    }

    @Test
    void aPartitionWithAPaymentCannotBeCancelledButAnUnusedOneCan() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        JsonNode paid = SettlementFixtures.json(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line, "1"));
        JsonNode unused = SettlementFixtures.json(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line, "1"));
        UUID paidId = UUID.fromString(paid.get("id").asString());
        fixtures.payPartition(sale.cashier(), sale.order(), paidId, "CASH", "12.50", "12.50",
                UUID.randomUUID().toString()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetType").value("PARTITION"));

        fixtures.cancelPartition(sale.cashier(), paidId, paid.get("version").asLong(), "wrong diner")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PARTITION_IN_USE"));
        fixtures.cancelPartition(sale.cashier(), UUID.fromString(unused.get("id").asString()),
                        unused.get("version").asLong(), "wrong diner")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        fixtures.partitions(sale.cashier(), sale.order()).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].financialState").value("SETTLED"))
                .andExpect(jsonPath("$[0].paid.amount").value(12.50))
                .andExpect(jsonPath("$[1].status").value("CANCELLED"));
        fixtures.settlement(sale.cashier(), sale.order())
                .andExpect(jsonPath("$.residualBalance.amount").value(12.50))
                .andExpect(jsonPath("$.paid.amount").value(12.50));
    }
}
