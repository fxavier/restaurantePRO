package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.service.OrderSettlementSteps;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * D96, A35: 2 x 1.00 at 23 % (line L, tax 0.37), one unit in partition A (share tax 0.37 - 0.19 = 0.18), the other
 * voided after the split: the order recomputes L's tax as 0.19, so δ = +0.01. The order also sells 2 x 12.50 at 16 %
 * (line X), so a residual document exists even when A is invoiced on its own. Through the real endpoints and fiscal.
 */
@SettlementModuleTest
class TaxRoundingResidualTest {

    static final UUID MANAGER = UUID.randomUUID();

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrderSettlementSteps steps;

    Sale sale;
    UUID lineL;

    @BeforeEach
    void anOrderWithA23PercentLine() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(MANAGER);
        sale = fixtures.sale("2"); // X: 2 x 12.50 at 16 %
        lineL = fixtures.confirmOtherRate(sale, "1.00", "23", "2");
    }

    @AfterEach
    void realSteps() {
        Mockito.reset(steps);
    }

    @Test
    void anUninvoicedShareOfTheDriftingLineAbsorbsTheCentInTheResidual() throws Exception {
        UUID a = partitionAndVoid();
        payAll(a);

        close().andExpect(jsonPath("$.taxRoundingDifference[?(@.ratePercent == 23)].difference.amount").value(0.01))
                .andExpect(jsonPath("$.taxRoundingDifference[?(@.ratePercent == 23)].absorbed.amount").value(0.01))
                .andExpect(jsonPath("$.taxRoundingDifference[?(@.ratePercent == 23)].unassigned.amount").value(0.0))
                .andExpect(jsonPath("$.taxRoundingAdjustments[0].kind").value("ABSORBED"))
                .andExpect(jsonPath("$.taxRoundingAdjustments[0].targetPartitionId").value(a.toString()));

        assertThat(documentTaxAt("23")).isEqualByComparingTo(tax("1.00", "1", "23")).isEqualByComparingTo("0.19");
        assertThat(documentTaxAt("16")).isEqualByComparingTo(tax("12.50", "2", "16"));
        UUID residual = residualDocument();
        assertThat(lineTax(residual, lineL)).isEqualByComparingTo("0.19");
        assertThat(lineNet(residual, lineL)).isEqualByComparingTo("0.81");
        assertThat(rows()).containsExactly(Map.of("kind", "ABSORBED", "amount", "0.0100",
                "document_id", residual.toString(), "target_line_id", lineL.toString(),
                "target_partition_id", a.toString()));
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE action = 'TAX_ROUNDING_ABSORBED' "
                + "AND entity_id = ?", sale.order().toString())).isEqualTo(1);
        assertGrossIsTheDue();
    }

    @Test
    void anInvoicedShareLeavesTheCentToAnotherLineOfTheRateInTheResidual() throws Exception {
        UUID lineM = fixtures.confirmOtherRate(sale, "1.00", "23", "1"); // M: tax 0.19, net 0.81
        UUID a = partitionAndVoid();
        UUID documentA = invoice(a);
        payAll(a);

        close().andExpect(status().isOk());

        assertThat(documentTaxAt("23")).isEqualByComparingTo(tax("1.00", "1", "23").add(tax("1.00", "1", "23")));
        UUID residual = residualDocument();
        assertThat(lineTax(residual, lineM)).isEqualByComparingTo("0.20");
        assertThat(lineTax(documentA, lineL)).isEqualByComparingTo("0.18");
        assertThat(rows()).containsExactly(Map.of("kind", "ABSORBED", "amount", "0.0100",
                "document_id", residual.toString(), "target_line_id", lineM.toString(), "target_partition_id", ""));
        assertGrossIsTheDue();
    }

    @Test
    void anInvoicedShareAndNoOtherLineOfTheRateIsAPendingCorrectionOfItsUnchangedDocument() throws Exception {
        UUID a = partitionAndVoid();
        UUID documentA = invoice(a);
        String before = documentRow(documentA);
        payAll(a);

        close().andExpect(status().isOk()).andExpect(jsonPath("$.lifecycle").value("CLOSED"))
                .andExpect(jsonPath("$.taxRoundingDifference[?(@.ratePercent == 23)].pendingCorrection.amount")
                        .value(0.01))
                .andExpect(jsonPath("$.taxRoundingAdjustments[0].kind").value("PENDING_CORRECTION"))
                .andExpect(jsonPath("$.taxRoundingAdjustments[0].documentId").value(documentA.toString()));

        assertThat(documentRow(documentA)).isEqualTo(before);
        assertThat(rows()).containsExactly(Map.of("kind", "PENDING_CORRECTION", "amount", "0.0100",
                "document_id", documentA.toString(), "target_line_id", "", "target_partition_id", ""));
        assertThat(documentTaxAt("23")).isEqualByComparingTo(tax("1.00", "1", "23").subtract(new BigDecimal("0.01")));
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE action = 'FISCAL_CORRECTION_PENDING' "
                + "AND entity_id = ?", sale.order().toString())).isEqualTo(1);
    }

    @Test
    void withNoResidualDocumentTheCloseRecordsThePendingCorrection() throws Exception {
        Sale only = fixtures.sale("2"); // 2 x 12.50 at 16 %: A of 1 takes tax 1.73, the void leaves the line 1.72
        UUID line = fixtures.lineIds(only.cashier(), only.order()).getFirst();
        UUID a = SettlementFixtures.id(fixtures.partitionItems(only.cashier(), only.order(),
                fixtures.orderVersion(only.cashier(), only.order()), UUID.randomUUID().toString(), line, "1")
                .andExpect(status().isCreated()));
        fixtures.settledVoid(only.cashier(), only.order(), fixtures.orderVersion(only.cashier(), only.order()), line,
                "1", UUID.randomUUID().toString()).andExpect(status().isCreated());
        UUID documentA = UUID.fromString(SettlementFixtures.json(fixtures.partitionDocument(only.cashier(), a,
                UUID.randomUUID().toString()).andExpect(status().isCreated())).get("id").asString());
        fixtures.payPartition(only.cashier(), only.order(), a, "CASH", "12.50", "12.50",
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        fixtures.settlement(only.cashier(), only.order())
                .andExpect(jsonPath("$.taxRoundingDifference[0].unassigned.amount").value(-0.01));

        fixtures.close(only.cashier(), only.order(), fixtures.orderVersion(only.cashier(), only.order()),
                        UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.taxRoundingDifference[0].pendingCorrection.amount").value(-0.01))
                .andExpect(jsonPath("$.taxRoundingDifference[0].unassigned.amount").value(0));

        assertThat(fixtures.documents(only.order())).as("no residual document").isZero();
        assertThat(fixtures.count("""
                SELECT count(*) FROM settlement.tax_rounding_adjustment WHERE order_id = ?
                AND kind = 'PENDING_CORRECTION' AND document_id = ? AND amount = -0.01""", only.order(), documentA))
                .isEqualTo(1);
    }

    @Test
    void aRetriedCloseAfterTheDraftStepReappliesTheStoredRows() throws Exception {
        UUID a = partitionAndVoid();
        payAll(a);
        String key = UUID.randomUUID().toString();
        long version = fixtures.orderVersion(sale.cashier(), sale.order());
        Mockito.doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("injected failure after s1");
        }).when(steps).prepareDocument(any(), any(), any(), any());
        fixtures.close(sale.cashier(), sale.order(), version, key).andExpect(status().isInternalServerError());
        List<Map<String, String>> rowsAfterS1 = rows();
        String draft = documentLines(residualDocument());
        Mockito.reset(steps);

        fixtures.close(sale.cashier(), sale.order(), version, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("CLOSED"));

        assertThat(rows()).isEqualTo(rowsAfterS1).hasSize(1);
        assertThat(documentLines(residualDocument())).isEqualTo(draft);
        assertThat(fixtures.count("SELECT count(*) FROM fiscal.fiscal_document WHERE order_ref = ?", sale.order()))
                .isEqualTo(1);
    }

    @Test
    void aRedraftAppliesTheStoredRowRatherThanPlacingAgain() throws Exception {
        UUID lineM = fixtures.confirmOtherRate(sale, "1.00", "23", "1");
        UUID a = partitionAndVoid();
        // placing now would choose A's share (L's own line); the stored row says M's pool line took it
        jdbc.sql("""
                INSERT INTO settlement.tax_rounding_adjustment (id, order_id, store_id, business_date, source_line_id,
                    rate_percent, amount, currency, kind, document_id, target_line_id, target_partition_id,
                    actor_kind, actor_id, created_at)
                SELECT ?, id, store_id, business_day, ?, 23, 0.01, 'MZN', 'ABSORBED', ?, ?, NULL, 'SYSTEM', NULL, now()
                FROM orders.order_header WHERE id = ?""")
                .params(UUID.randomUUID(), lineL, UUID.randomUUID(), lineM, sale.order()).update();
        payAll(a);

        close().andExpect(status().isOk());

        UUID residual = residualDocument();
        assertThat(lineTax(residual, lineM)).isEqualByComparingTo("0.20");
        assertThat(jdbc.sql("""
                SELECT tax FROM fiscal.fiscal_document_line WHERE document_id = ? AND source_line_id = ?""")
                .params(residual, lineL).query(BigDecimal.class).single()).isEqualByComparingTo("0.18");
        assertThat(rows()).hasSize(1);
    }

    @Test
    void anUnpartitionedOrderHasNoRowAndKeepsStrictAmounts() throws Exception {
        String due = SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order())).get("due")
                .get("amount").asString();
        fixtures.payCash(sale, due, due).andExpect(status().isCreated());

        close().andExpect(status().isOk());

        assertThat(rows()).isEmpty();
        assertThat(fixtures.count("SELECT count(*) FROM fiscal.fiscal_document WHERE order_ref = ? "
                + "AND amounts_allocated", sale.order())).isZero();
        assertThat(documentTaxAt("23")).isEqualByComparingTo(tax("1.00", "2", "23"));
    }

    /** A takes one unit of L; the other unit is voided (settled). @return A's id */
    private UUID partitionAndVoid() throws Exception {
        UUID a = SettlementFixtures.id(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), lineL, "1")
                .andExpect(status().isCreated()));
        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), lineL,
                "1", UUID.randomUUID().toString()).andExpect(status().isCreated());
        fixtures.settlement(sale.cashier(), sale.order())
                .andExpect(jsonPath("$.taxRoundingDifference[?(@.ratePercent == 23)].unassigned.amount").value(0.01));
        return a;
    }

    private UUID invoice(UUID partition) throws Exception {
        return UUID.fromString(SettlementFixtures.json(fixtures.partitionDocument(sale.cashier(), partition,
                UUID.randomUUID().toString()).andExpect(status().isCreated())).get("id").asString());
    }

    private void payAll(UUID partition) throws Exception {
        fixtures.payPartition(sale.cashier(), sale.order(), partition, "CASH", "1.00", "1.00",
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        String residual = SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order()))
                .get("residualBalance").get("amount").asString();
        fixtures.payCash(sale, residual, residual).andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions close() throws Exception {
        return fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());
    }

    private void assertGrossIsTheDue() throws Exception {
        String due = SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order())).get("due")
                .get("amount").asString();
        assertThat(fixtures.decimal("SELECT sum(total_gross) FROM fiscal.fiscal_document WHERE order_ref = ?",
                sale.order())).isEqualByComparingTo(due);
    }

    private UUID residualDocument() {
        return jdbc.sql("SELECT id FROM fiscal.fiscal_document WHERE order_ref = ? AND source_type = 'ORDER'")
                .param(sale.order()).query(UUID.class).single();
    }

    private BigDecimal documentTaxAt(String rate) {
        return fixtures.decimal("""
                SELECT coalesce(sum(t.tax), 0) FROM fiscal.fiscal_document_tax t JOIN fiscal.fiscal_document d
                ON d.id = t.document_id WHERE d.order_ref = ? AND t.tax_rate = ?::numeric""", sale.order(), rate);
    }

    /** The order's recomputed tax of {@code quantity} x {@code price} at {@code rate} (LineAmounts, per line). */
    private static BigDecimal tax(String price, String quantity, String rate) {
        return com.xavier.backend.orders.LineAmounts.of(com.xavier.backend.shared.Money.of(price,
                java.util.Currency.getInstance("MZN")), new BigDecimal(quantity), new BigDecimal(rate)).tax().amount();
    }

    private BigDecimal lineTax(UUID document, UUID line) {
        return jdbc.sql("SELECT sum(tax) FROM fiscal.fiscal_document_line WHERE document_id = ? AND source_line_id = ?")
                .params(document, line).query(BigDecimal.class).single();
    }

    private BigDecimal lineNet(UUID document, UUID line) {
        return jdbc.sql("SELECT sum(net) FROM fiscal.fiscal_document_line WHERE document_id = ? AND source_line_id = ?")
                .params(document, line).query(BigDecimal.class).single();
    }

    private String documentRow(UUID document) {
        return jdbc.sql("SELECT row_to_json(d)::text FROM fiscal.fiscal_document d WHERE id = ?").param(document)
                .query(String.class).single() + documentLines(document);
    }

    private String documentLines(UUID document) {
        return jdbc.sql("""
                SELECT json_agg(json_build_array(line_no, source_line_id, quantity, gross, net, tax) ORDER BY line_no)
                ::text FROM fiscal.fiscal_document_line WHERE document_id = ?""").param(document)
                .query(String.class).single();
    }

    private List<Map<String, String>> rows() {
        return jdbc.sql("""
                SELECT kind, amount::text AS amount, document_id::text AS document_id,
                    coalesce(target_line_id::text, '') AS target_line_id,
                    coalesce(target_partition_id::text, '') AS target_partition_id
                FROM settlement.tax_rounding_adjustment WHERE order_id = ? ORDER BY source_line_id""")
                .param(sale.order()).query((rs, n) -> Map.of("kind", rs.getString("kind"),
                        "amount", rs.getString("amount"), "document_id", rs.getString("document_id"),
                        "target_line_id", rs.getString("target_line_id"),
                        "target_partition_id", rs.getString("target_partition_id"))).list();
    }
}
