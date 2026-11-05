package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * D70, A19: an ITEMS partition is invoiced on its own (source PARTITION) without freezing the order. A new round is
 * still accepted, the issued document is unchanged, and the new lines are in the residual. D63: a fiscal failure is
 * 422 FISCAL_PENDING and the retry with the same key issues it with no gap.
 */
@SettlementModuleTest
class PartitionDocumentTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    InternalFiscalAdapter fiscalAdapter;

    @AfterEach
    void realAdapter() {
        Mockito.reset(fiscalAdapter);
    }

    @Test
    void aPartitionDocumentLeavesTheOrderOpenForANewRoundWhoseLinesStayInTheResidual() throws Exception {
        Sale sale = fixtures.sale("2"); // 25.00
        UUID partition = itemsPartition(sale, "1");

        JsonNode document = SettlementFixtures.json(fixtures.partitionDocument(sale.cashier(), partition,
                        UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.sourceType").value("PARTITION"))
                .andExpect(jsonPath("$.sourceId").value(partition.toString()))
                .andExpect(jsonPath("$.orderId").value(sale.order().toString()))
                .andExpect(jsonPath("$.totalGross.amount").value(12.50)));

        fixtures.confirmRound(sale.shop(), sale.cashier(), sale.order(), "1",
                fixtures.orderVersion(sale.cashier(), sale.order()));

        String id = document.get("id").asString();
        assertThat(fixtures.count("SELECT count(*) FROM fiscal.fiscal_document_line WHERE document_id = ?::uuid", id))
                .as("the issued document is unchanged").isEqualTo(1);
        fixtures.settlement(sale.cashier(), sale.order())
                .andExpect(jsonPath("$.due.amount").value(37.50))
                .andExpect(jsonPath("$.residualBalance.amount").value(25.00))
                .andExpect(jsonPath("$.fiscalState").value("NONE"))
                .andExpect(jsonPath("$.partitions[0].invoiced").value(true))
                .andExpect(jsonPath("$.coverage.length()").value(2));
    }

    @Test
    void anAmountPartitionIsNotInvoicedOnItsOwn() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID partition = UUID.fromString(SettlementFixtures.json(fixtures.partitionAmount(sale.cashier(),
                sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), "10.00")).get("id").asString());

        fixtures.partitionDocument(sale.cashier(), partition, UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PARTITION_NOT_INVOICEABLE"));
    }

    @Test
    void aFiscalFailureIsPendingAndTheRetryWithTheSameKeyIssuesOnce() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID partition = itemsPartition(sale, "1");
        String key = UUID.randomUUID().toString();
        Mockito.doThrow(new IllegalStateException("adapter offline")).when(fiscalAdapter).issue(any());

        fixtures.partitionDocument(sale.cashier(), partition, key).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FISCAL_PENDING"))
                .andExpect(jsonPath("$.documentId").isNotEmpty());
        fixtures.cancelPartition(sale.cashier(), partition, 0, "changed mind")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PARTITION_IN_USE"));
        Mockito.reset(fiscalAdapter);

        fixtures.partitionDocument(sale.cashier(), partition, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(1));
        assertThat(fixtures.nextNumber(sale.series())).isEqualTo(2);
        assertThat(fixtures.count("SELECT count(*) FROM fiscal.fiscal_document WHERE source_id = ?", partition))
                .isEqualTo(1);
    }

    private UUID itemsPartition(Sale sale, String quantity) throws Exception {
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        return UUID.fromString(SettlementFixtures.json(fixtures.partitionItems(sale.cashier(), sale.order(),
                        fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line,
                        quantity).andExpect(status().isCreated())).get("id").asString());
    }
}
