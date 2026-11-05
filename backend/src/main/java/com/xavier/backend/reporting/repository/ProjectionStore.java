package com.xavier.backend.reporting.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The projection rows (D84), in plain SQL: every insert is keyed by its source id and does nothing when the row
 * exists ({@code ON CONFLICT DO NOTHING}), so projecting a fact twice changes nothing. Rows are only ever deleted by
 * a rebuild of a store and business-date range.
 */
@Repository
public class ProjectionStore {

    private final JdbcClient jdbc;

    ProjectionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SalesHeader(UUID documentId, UUID storeId, LocalDate businessDate, LocalDate localDate,
                              int localHour, Instant issuedAt, UUID orderId, UUID partitionId, UUID terminalId,
                              UUID issuedByEmployeeId, UUID customerId, boolean onAccount, String seriesCode,
                              long number, boolean nonFiscal, String currency, BigDecimal totalGross,
                              BigDecimal totalNet, BigDecimal totalTax) {
    }

    public record SalesLine(int lineNo, UUID itemId, String itemCode, String familyCode, String description,
                            BigDecimal quantity, BigDecimal gross, BigDecimal net, BigDecimal tax, String taxCode,
                            BigDecimal taxRate, UUID servedByEmployeeId, UUID areaId, String areaCode, String mode) {
    }

    public record SalesTax(String taxCode, BigDecimal taxRate, BigDecimal net, BigDecimal tax) {
    }

    public record MoneyRecord(String sourceType, UUID sourceId, UUID storeId, LocalDate businessDate, int localHour,
                              String methodCode, BigDecimal signedAmount, String targetType, UUID orderId,
                              UUID customerId, UUID employeeId, UUID terminalId) {
    }

    public record CreditRecord(UUID chargeId, UUID storeId, LocalDate businessDate, UUID customerId, UUID orderId,
                               BigDecimal amount) {
    }

    public record VoidRecord(UUID voidId, UUID storeId, LocalDate businessDate, int localHour, String itemCode,
                             String description, BigDecimal quantity, BigDecimal gross, String reason,
                             boolean wasted, boolean afterDispatch, UUID employeeId, UUID approverId) {
    }

    /** @return whether the header was inserted (false: the document was already projected) */
    public boolean insertSalesHeader(SalesHeader h, Instant projectedAt) {
        return jdbc.sql("""
                        INSERT INTO reporting.sales_record (document_id, store_id, business_date, local_date,
                            local_hour, issued_at, order_id, partition_id, terminal_id, issued_by_employee_id,
                            customer_id, on_account, series_code, number, non_fiscal, currency, total_gross,
                            total_net, total_tax, projected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (document_id) DO NOTHING""")
                .params(h.documentId(), h.storeId(), h.businessDate(), h.localDate(), h.localHour(),
                        Timestamp.from(h.issuedAt()), h.orderId(), h.partitionId(), h.terminalId(),
                        h.issuedByEmployeeId(), h.customerId(), h.onAccount(), h.seriesCode(), h.number(),
                        h.nonFiscal(), h.currency(), h.totalGross(), h.totalNet(), h.totalTax(),
                        Timestamp.from(projectedAt))
                .update() == 1;
    }

    public void insertSalesLine(SalesHeader h, SalesLine l, Instant projectedAt) {
        jdbc.sql("""
                        INSERT INTO reporting.sales_record_line (document_id, line_no, store_id, business_date,
                            local_hour, item_id, item_code, family_code, description, quantity, gross, net, tax,
                            tax_code, tax_rate, served_by_employee_id, area_id, area_code, mode, projected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (document_id, line_no) DO NOTHING""")
                .params(h.documentId(), l.lineNo(), h.storeId(), h.businessDate(), h.localHour(), l.itemId(),
                        l.itemCode(), l.familyCode(), l.description(), l.quantity(), l.gross(), l.net(), l.tax(),
                        l.taxCode(), l.taxRate(), l.servedByEmployeeId(), l.areaId(), l.areaCode(), l.mode(),
                        Timestamp.from(projectedAt))
                .update();
    }

    public void insertSalesTax(SalesHeader h, SalesTax t, Instant projectedAt) {
        jdbc.sql("""
                        INSERT INTO reporting.sales_record_tax (document_id, tax_code, tax_rate, store_id,
                            business_date, net, tax, projected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (document_id, tax_code, tax_rate) DO NOTHING""")
                .params(h.documentId(), t.taxCode(), t.taxRate(), h.storeId(), h.businessDate(), t.net(), t.tax(),
                        Timestamp.from(projectedAt))
                .update();
    }

    /** @return whether the row was inserted */
    public boolean insertMoney(MoneyRecord m, Instant projectedAt) {
        return jdbc.sql("""
                        INSERT INTO reporting.payment_record (source_type, source_id, store_id, business_date,
                            local_hour, method_code, signed_amount, target_type, order_id, customer_id, employee_id,
                            terminal_id, projected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (source_type, source_id) DO NOTHING""")
                .params(m.sourceType(), m.sourceId(), m.storeId(), m.businessDate(), m.localHour(),
                        m.methodCode(), m.signedAmount(), m.targetType(), m.orderId(), m.customerId(),
                        m.employeeId(), m.terminalId(), Timestamp.from(projectedAt))
                .update() == 1;
    }

    /** @return whether the row was inserted */
    public boolean insertCredit(CreditRecord c, Instant projectedAt) {
        return jdbc.sql("""
                        INSERT INTO reporting.credit_record (charge_id, store_id, business_date, customer_id,
                            order_id, amount, projected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (charge_id) DO NOTHING""")
                .params(c.chargeId(), c.storeId(), c.businessDate(), c.customerId(), c.orderId(), c.amount(),
                        Timestamp.from(projectedAt))
                .update() == 1;
    }

    /** @return whether the row was inserted */
    public boolean insertVoid(VoidRecord v, Instant projectedAt) {
        return jdbc.sql("""
                        INSERT INTO reporting.void_record (void_id, store_id, business_date, local_hour, item_code,
                            description, quantity, gross, reason, wasted, after_dispatch, employee_id, approver_id,
                            projected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (void_id) DO NOTHING""")
                .params(v.voidId(), v.storeId(), v.businessDate(), v.localHour(), v.itemCode(), v.description(),
                        v.quantity(), v.gross(), v.reason(), v.wasted(), v.afterDispatch(), v.employeeId(),
                        v.approverId(), Timestamp.from(projectedAt))
                .update() == 1;
    }

    /** A rebuild's first step: the store's projection rows in {@code [from, to]} (projections, never facts). */
    public void deleteRange(UUID storeId, LocalDate from, LocalDate to) {
        for (String table : new String[] {"sales_record", "payment_record", "credit_record", "void_record"}) {
            jdbc.sql("DELETE FROM reporting." + table + " WHERE store_id = ? AND business_date BETWEEN ? AND ?")
                    .params(storeId, from, to).update();
        }
    }
}
