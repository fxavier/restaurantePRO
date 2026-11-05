package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D70, A16: the close issues a residual ORDER document for exactly what no partition document covers, and requires
 * every document ISSUED, every line's net quantity covered and Σ document gross = due. When partition documents cover
 * everything, no residual document exists.
 */
@SettlementModuleTest
class ResidualDocumentCloseTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter fiscalAdapter;

    @Test
    void twoInvoicedPartitionsAndAResidualDocumentSumToTheOrder() throws Exception {
        Sale sale = fixtures.sale("3"); // 37.50: net 32.33, tax 5.17
        UUID a = invoicedPartition(sale, "1");
        UUID b = invoicedPartition(sale, "1");
        pay(sale, a, "12.50");
        pay(sale, b, "12.50");
        fixtures.payCash(sale, "12.50", "12.50").andExpect(status().isCreated());

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                        UUID.randomUUID().toString())
                .andExpect(status().isOk()).andExpect(jsonPath("$.lifecycle").value("CLOSED"))
                .andExpect(jsonPath("$.fiscalState").value("ISSUED"));

        assertThat(documents(sale, "count(*)")).isEqualTo(new BigDecimal("3"));
        assertThat(documents(sale, "sum(total_gross)")).isEqualByComparingTo("37.50");
        assertThat(documents(sale, "sum(total_tax)")).isEqualByComparingTo("5.17");
        assertThat(fixtures.count("""
                SELECT count(*) FROM fiscal.fiscal_document WHERE source_id = ? AND amounts_allocated""", sale.order()))
                .as("the residual of a partitioned order carries allocated amounts").isEqualTo(1);
        assertThat(new BigDecimal(fixtures.count("""
                SELECT sum(l.quantity)::bigint FROM fiscal.fiscal_document_line l
                JOIN fiscal.fiscal_document d ON d.id = l.document_id WHERE d.order_ref = ?""", sale.order())))
                .isEqualTo(new BigDecimal("3"));
    }

    @Test
    void anUninvoicedPartitionIsALineOfItsOwnOnTheResidualDocument() throws Exception {
        Sale sale = fixtures.sale("3");
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        fixtures.partitionItems(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString(), line, "1").andExpect(status().isCreated());
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        UUID partition = UUID.fromString(SettlementFixtures.json(fixtures.partitions(sale.cashier(), sale.order()))
                .get(0).get("id").asString());
        pay(sale, partition, "12.50");

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());

        assertThat(fixtures.count("""
                SELECT count(*) FROM fiscal.fiscal_document_line l JOIN fiscal.fiscal_document d
                ON d.id = l.document_id WHERE d.source_id = ?""", sale.order())).isEqualTo(2);
        assertThat(documents(sale, "sum(total_gross)")).isEqualByComparingTo("37.50");
    }

    @Test
    void whenPartitionDocumentsCoverEverythingNoResidualDocumentExists() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID a = invoicedPartition(sale, "1");
        UUID b = invoicedPartition(sale, "1");
        pay(sale, a, "12.50");
        pay(sale, b, "12.50");

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                        UUID.randomUUID().toString())
                .andExpect(status().isOk()).andExpect(jsonPath("$.lifecycle").value("CLOSED"))
                .andExpect(jsonPath("$.fiscalState").value("NONE"));

        assertThat(documents(sale, "count(*)")).isEqualTo(new BigDecimal("2"));
        assertThat(fixtures.documents(sale.order())).as("no ORDER document").isZero();
    }

    @Test
    void aNewRoundAfterPartitionDocumentsIsPaidBeforeTheClose() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID a = invoicedPartition(sale, "1");
        pay(sale, a, "12.50");
        fixtures.payCash(sale, "12.50", "12.50").andExpect(status().isCreated());
        fixtures.confirmRound(sale.shop(), sale.cashier(), sale.order(), "1",
                fixtures.orderVersion(sale.cashier(), sale.order()));

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                        UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("NOT_SETTLED"));
    }

    @Test
    void aPartitionDraftStillPendingBlocksTheCloseWithItsDocument() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        UUID partition = UUID.fromString(SettlementFixtures.json(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line, "1"))
                .get("id").asString());
        org.mockito.Mockito.doThrow(new IllegalStateException("adapter offline")).when(fiscalAdapter)
                .issue(org.mockito.ArgumentMatchers.any());
        String pending = SettlementFixtures.json(fixtures.partitionDocument(sale.cashier(), partition,
                UUID.randomUUID().toString()).andExpect(status().isUnprocessableContent())).get("documentId")
                .asString();
        org.mockito.Mockito.reset(fiscalAdapter);
        pay(sale, partition, "12.50");
        fixtures.payCash(sale, "12.50", "12.50").andExpect(status().isCreated());

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                        UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("FISCAL_PENDING"))
                .andExpect(jsonPath("$.documentId").value(pending));
    }

    @org.junit.jupiter.api.AfterEach
    void realAdapter() {
        org.mockito.Mockito.reset(fiscalAdapter);
    }

    private UUID invoicedPartition(Sale sale, String quantity) throws Exception {
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        UUID partition = UUID.fromString(SettlementFixtures.json(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line, quantity)
                .andExpect(status().isCreated())).get("id").asString());
        fixtures.partitionDocument(sale.cashier(), partition, UUID.randomUUID().toString())
                .andExpect(status().isCreated());
        return partition;
    }

    private void pay(Sale sale, UUID partition, String amount) throws Exception {
        fixtures.payPartition(sale.cashier(), sale.order(), partition, "CASH", amount, amount,
                UUID.randomUUID().toString()).andExpect(status().isCreated());
    }

    private BigDecimal documents(Sale sale, String aggregate) {
        return fixtures.decimal("SELECT " + aggregate + " FROM fiscal.fiscal_document WHERE order_ref = ?",
                sale.order());
    }
}
