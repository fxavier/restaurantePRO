package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * D107: a document line of a discounted or offered line carries its effective unit price (the one its amounts follow)
 * plus the list price and the label to print, on the residual document and on a partition's; a line at its list price
 * carries neither.
 */
@SettlementModuleTest
class DiscountedDocumentLineTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void aManagerApproves() {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_DISCOUNT_APPROVE), any()))
                .thenReturn(UUID.randomUUID());
    }

    @Test
    void theResidualDocumentPrintsTheDiscountOfALine() throws Exception {
        Sale sale = fixtures.sale("2"); // 2 × 12.50
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        fixtures.percentOff(sale, line, null, "50").andExpect(status().isOk());
        fixtures.payCash(sale, "12.50", "12.50").andExpect(status().isCreated());

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());

        Map<String, Object> printed = documentLine(line, "ORDER");
        assertThat((java.math.BigDecimal) printed.get("unit_price")).isEqualByComparingTo("6.25");
        assertThat((java.math.BigDecimal) printed.get("gross")).isEqualByComparingTo("12.50");
        assertThat((java.math.BigDecimal) printed.get("list_unit_price")).isEqualByComparingTo("12.50");
        assertThat(printed.get("discount_label")).isEqualTo("Desconto 50%");
    }

    @Test
    void aPartitionDocumentPrintsTheOfferAndTheResidualALineAtListPriceWithoutOne() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        fixtures.offer(sale, line, "1").andExpect(status().isOk()); // splits off one offered unit
        UUID offered = fixtures.lineIds(sale.cashier(), sale.order()).get(1);
        UUID partition = SettlementFixtures.id(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), offered, "1",
                line, "1"));
        fixtures.partitionDocument(sale.cashier(), partition, UUID.randomUUID().toString())
                .andExpect(status().isCreated());

        Map<String, Object> printed = documentLine(offered, "PARTITION");
        assertThat((java.math.BigDecimal) printed.get("unit_price")).isEqualByComparingTo("0.00");
        assertThat((java.math.BigDecimal) printed.get("list_unit_price")).isEqualByComparingTo("12.50");
        assertThat(printed.get("discount_label")).isEqualTo("Oferta");
        Map<String, Object> atList = documentLine(line, "PARTITION");
        assertThat(atList.get("list_unit_price")).isNull();
        assertThat(atList.get("discount_label")).isNull();
    }

    private Map<String, Object> documentLine(UUID sourceLine, String sourceType) {
        return jdbc.sql("""
                SELECT l.unit_price, l.gross, l.list_unit_price, l.discount_label
                FROM fiscal.fiscal_document_line l JOIN fiscal.fiscal_document d ON d.id = l.document_id
                WHERE l.source_line_id = ? AND d.source_type = ?""").param(sourceLine).param(sourceType)
                .query().singleRow();
    }
}
