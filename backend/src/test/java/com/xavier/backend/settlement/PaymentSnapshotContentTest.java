package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot;
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot.PaidAmount;
import com.xavier.backend.fiscal.model.dto.PaymentStatus;
import com.xavier.backend.fiscal.model.event.FiscalDocumentIssued;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.PublishedEvents;
import tools.jackson.databind.JsonNode;

/**
 * D97 (A36), the content of the stored snapshot of a partition document and of the residual of the same order (S35
 * review). One order of 4 x 12.50: partition P1 (one unit) is paid 5.00 cash then 7.50 by card and invoiced on its own;
 * partition P2 (one unit, items) is paid 12.50 cash and not invoiced; an AMOUNT partition of 10.00 is paid by card;
 * the ORDER target's 15.00 goes on account, which issues the residual at once. The residual covers the ORDER target and
 * every live partition without a document of its own (P2 and the AMOUNT part), never P1:
 * <ul>
 *   <li>P1: PAID, [Numerário 5.00, card 7.50] in confirmation order, no credit, nothing outstanding;</li>
 *   <li>residual: MIXED, [Numerário 12.50 (P2), card 10.00 (AMOUNT)] in confirmation order, credit 15.00, nothing
 *       outstanding, on account to the customer;</li>
 * </ul>
 * and, for each, the {@code fiscal_document_payment} rows (position, code, label, amount), the totals and status
 * columns, {@code FiscalApi.paymentSnapshot}, and the printed block are the same snapshot.
 */
@SettlementModuleTest
class PaymentSnapshotContentTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    FiscalApi fiscal;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aPartitionDocumentAndTheResidualEachStoreTheTargetsTheyCover(PublishedEvents events) throws Exception {
        Sale sale = fixtures.sale("4"); // 50.00
        String card = SettlementFixtures.freshCode("CARD");
        fixtures.method(card, "EXTERNAL_TERMINAL", null); // named as its code
        UUID customer = customers.creditCustomer("100.00");
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        UUID p1 = items(sale, line);
        UUID p2 = items(sale, line);
        UUID amount = SettlementFixtures.id(fixtures.partitionAmount(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), "10.00").andExpect(status().isCreated()));

        fixtures.payPartition(sale.cashier(), sale.order(), p1, "CASH", "5.00", "5.00", UUID.randomUUID().toString())
                .andExpect(status().isCreated());
        byCard(sale, p1, card, "7.50");
        fixtures.payPartition(sale.cashier(), sale.order(), p2, "CASH", "12.50", "12.50",
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        byCard(sale, amount, card, "10.00");
        UUID p1Document = SettlementFixtures.id(fixtures.partitionDocument(sale.cashier(), p1,
                UUID.randomUUID().toString()).andExpect(status().isCreated()));
        JsonNode charge = SettlementFixtures.json(fixtures.creditCharge(sale.cashier(), sale.order(), customer,
                "15.00", null, UUID.randomUUID().toString()).andExpect(status().isCreated()));
        UUID residual = UUID.fromString(charge.get("documentId").asString());

        PaymentSnapshot p1Snapshot = new PaymentSnapshot(PaymentStatus.PAID, List.of(cash("5.00"),
                new PaidAmount(card, card, m("7.50"))), m("0.00"), m("0.00"));
        assertThat(fiscal.paymentSnapshot(p1Document)).contains(p1Snapshot);
        assertThat(rows(p1Document)).containsExactly(List.of(1, "CASH", "Numerário", "5.00"),
                List.of(2, card, card, "7.50"));
        assertThat(totals(p1Document)).containsExactly("PAID", "12.50", "0.00", "0.00", false);
        assertThat(printout(events, p1Document)).contains("Pagamento misto — Numerário: 5.00; " + card + ": 7.50")
                .noneMatch(l -> l.startsWith("Saldo em aberto"));

        PaymentSnapshot residualSnapshot = new PaymentSnapshot(PaymentStatus.MIXED, List.of(cash("12.50"),
                new PaidAmount(card, card, m("10.00"))), m("15.00"), m("0.00"));
        assertThat(fiscal.paymentSnapshot(residual)).contains(residualSnapshot);
        assertThat(rows(residual)).containsExactly(List.of(1, "CASH", "Numerário", "12.50"),
                List.of(2, card, card, "10.00"));
        assertThat(totals(residual)).containsExactly("MIXED", "22.50", "15.00", "0.00", true);
        assertThat(jdbc.sql("SELECT total_gross FROM fiscal.fiscal_document WHERE id = ?").param(residual)
                .query(BigDecimal.class).single()).as("the residual's gross: 3 units less P1's own")
                .isEqualByComparingTo("37.50");
        assertThat(printout(events, residual))
                .contains("Pagamento misto — Numerário: 12.50; " + card + ": 10.00; A crédito: 15.00")
                .doesNotContain("A CRÉDITO").noneMatch(l -> l.startsWith("Saldo em aberto"));
    }

    private UUID items(Sale sale, UUID line) throws Exception {
        return SettlementFixtures.id(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line, "1")
                .andExpect(status().isCreated()));
    }

    private void byCard(Sale sale, UUID partition, String card, String amount) throws Exception {
        UUID attempt = SettlementFixtures.id(fixtures.payPartition(sale.cashier(), sale.order(), partition, card,
                amount, null, UUID.randomUUID().toString()).andExpect(status().isCreated()));
        fixtures.confirm(sale.cashier(), attempt, "AUTH-" + amount).andExpect(status().isOk());
    }

    private List<List<Object>> rows(UUID document) {
        return jdbc.sql("""
                        SELECT position, method_code, label, amount FROM fiscal.fiscal_document_payment
                        WHERE document_id = ? ORDER BY position""").param(document)
                .query((rs, n) -> List.<Object>of(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4).setScale(2).toPlainString())).list();
    }

    private List<Object> totals(UUID document) {
        Map<String, Object> row = jdbc.sql("""
                        SELECT payment_status, paid_total, credit_total, outstanding_total, on_account
                        FROM fiscal.fiscal_document WHERE id = ?""").param(document).query().singleRow();
        return List.of(row.get("payment_status"), plain(row.get("paid_total")), plain(row.get("credit_total")),
                plain(row.get("outstanding_total")), row.get("on_account"));
    }

    private static String plain(Object amount) {
        return ((BigDecimal) amount).setScale(2).toPlainString();
    }

    private static List<String> printout(PublishedEvents events, UUID document) {
        return events.ofType(FiscalDocumentIssued.class).matching(e -> e.documentId().equals(document))
                .iterator().next().printout().lines();
    }

    private static PaidAmount cash(String amount) {
        return new PaidAmount("CASH", "Numerário", m(amount));
    }

    private static Money m(String amount) {
        return Money.of(amount, MZN);
    }
}
