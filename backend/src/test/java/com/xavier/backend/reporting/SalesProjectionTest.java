package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.fiscal.model.event.FiscalDocumentIssued;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A sales record matches the issued document it projects (D84): header, lines with their resolved dimensions, and
 * the tax summary per rate read from the document (never recomputed). A redelivered event projects nothing new.
 */
@ReportingModuleTest
class SalesProjectionTest {

    @Autowired
    SettlementFixtures sales;

    @Autowired
    ReportingFixtures reporting;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Test
    void anIssuedDocumentIsProjectedWithItsLinesTaxesAndDimensions() throws Exception {
        Sale sale = sales.sale("2");
        sales.payAndClose(sale);
        UUID document = reporting.issuedDocument(sale.order());

        reporting.awaitSalesRecord(document);

        Map<String, Object> fact = reporting.row("""
                SELECT d.store_id, d.issued_at, d.issued_terminal_id AS terminal_id, d.issued_by_employee_id,
                       d.series_code, d.number, d.non_fiscal, d.total_gross, d.total_net, d.total_tax
                FROM fiscal.fiscal_document d WHERE d.id = ?""", document);
        Map<String, Object> record = reporting.row("""
                SELECT store_id, business_date, issued_at, terminal_id, issued_by_employee_id, series_code, number,
                       non_fiscal, total_gross, total_net, total_tax, order_id, on_account
                FROM reporting.sales_record WHERE document_id = ?""", document);
        assertThat(record).containsAllEntriesOf(fact).containsEntry("order_id", sale.order())
                .containsEntry("on_account", false);
        assertThat(record.get("business_date")).isEqualTo(java.sql.Date.valueOf(reporting.businessDayOf(sale.order())));

        List<Map<String, Object>> lines = reporting.rows("""
                SELECT line_no, description, quantity, gross, net, tax, tax_rate FROM reporting.sales_record_line
                WHERE document_id = ? ORDER BY line_no""", document);
        assertThat(lines).isEqualTo(reporting.rows("""
                SELECT line_no, description, quantity, gross, net, tax, tax_rate FROM fiscal.fiscal_document_line
                WHERE document_id = ? ORDER BY line_no""", document));
        Map<String, Object> line = reporting.row("""
                SELECT item_id, item_code, family_code, tax_code, served_by_employee_id, area_code, mode, local_hour
                FROM reporting.sales_record_line WHERE document_id = ? AND line_no = 1""", document);
        assertThat(line.get("item_id")).isEqualTo(sale.shop().item());
        assertThat(line.get("item_code")).isNotNull();
        assertThat(line.get("family_code")).isNotNull();
        assertThat(line.get("tax_code")).isNotNull();
        assertThat(line).containsEntry("served_by_employee_id", sale.cashier().employeeId())
                .containsEntry("area_code", "SALA").containsEntry("mode", "DINE_IN");

        assertThat(reporting.rows("""
                SELECT tax_code, tax_rate, net, tax FROM reporting.sales_record_tax WHERE document_id = ?
                ORDER BY tax_rate""", document)).isEqualTo(reporting.rows("""
                SELECT tax_code, tax_rate, net, tax FROM fiscal.fiscal_document_tax WHERE document_id = ?
                ORDER BY tax_rate""", document));
        assertThat(reporting.row("SELECT tax_code FROM fiscal.fiscal_document_tax WHERE document_id = ?", document)
                .get("tax_code")).as("the document's tax summary names the code").isEqualTo(line.get("tax_code"));
        assertThat(record.get("total_tax")).isEqualTo(new BigDecimal("3.4500"));
    }

    @Test
    void aWalkInSalesLinesCarryTheOrdersMode() throws Exception {
        Sale sale = sales.walkInSale("2", "TAKEAWAY");
        sales.payAndClose(sale);
        UUID document = reporting.issuedDocument(sale.order());

        reporting.awaitSalesRecord(document);

        assertThat(reporting.row("SELECT mode, area_code FROM reporting.sales_record_line WHERE document_id = ? "
                + "AND line_no = 1", document)).containsEntry("mode", "TAKEAWAY").containsEntry("area_code", null);
    }

    @Test
    void aRedeliveredDocumentEventProjectsNothingNew() throws Exception {
        Sale sale = sales.sale("2");
        sales.payAndClose(sale);
        UUID document = reporting.issuedDocument(sale.order());
        reporting.awaitSalesRecord(document);
        Object projectedAt = reporting.row("SELECT projected_at FROM reporting.sales_record WHERE document_id = ?",
                document).get("projected_at");

        tx.executeWithoutResult(status -> events.publishEvent(
                new FiscalDocumentIssued(document, sale.store(), sale.cashier().terminalId(), null)));
        reporting.awaitNothingPendingFor(sale.store());

        assertThat(reporting.count("SELECT count(*) FROM reporting.sales_record WHERE document_id = ?", document))
                .isOne();
        assertThat(reporting.count("SELECT count(*) FROM reporting.sales_record_line WHERE document_id = ?",
                document)).isOne();
        assertThat(reporting.row("SELECT projected_at FROM reporting.sales_record WHERE document_id = ?", document)
                .get("projected_at")).isEqualTo(projectedAt);
    }
}
