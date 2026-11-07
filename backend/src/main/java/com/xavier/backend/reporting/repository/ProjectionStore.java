package com.xavier.backend.reporting.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    /** Every projection table with its own store and business date (the line and tax tables cascade). */
    public static final List<String> PROJECTIONS = List.of("sales_record", "payment_record", "credit_record",
            "void_record", "consumption_record", "consumption_waste_record", "cost_variance_record",
            "discount_record", "offer_cost_record");

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

    /** A stock movement that is cost of goods (D131): a sale's consumption, its reversal, or a manual waste. */
    public record ConsumptionRecord(UUID movementId, UUID storeId, LocalDate businessDate, UUID saleItemId,
                                    String familyCode, UUID lineId, UUID warehouseId, UUID stockItemId, String type,
                                    BigDecimal quantity, BigDecimal value, String sourceType, UUID sourceId) {
    }

    /** A wasted void's share of one sale movement (D117 as amended): its value moves from cost of sales to waste. */
    public record WasteRecord(UUID voidId, UUID originalMovementId, UUID storeId, LocalDate businessDate,
                              UUID saleItemId, String familyCode, UUID lineId, UUID warehouseId, UUID stockItemId,
                              BigDecimal quantity, BigDecimal value) {
    }

    /** A cost variance (D125): positive is more cost of goods. */
    public record VarianceRecord(UUID varianceId, String sourceType, UUID sourceId, UUID storeId,
                                 LocalDate businessDate, UUID warehouseId, UUID stockItemId, UUID receiptLineId,
                                 BigDecimal value) {
    }

    /** One discounted line of a discount source (D105). */
    public record DiscountRecord(String sourceType, UUID sourceId, UUID lineId, UUID storeId, LocalDate businessDate,
                                 UUID itemId, String familyCode, String origin, String kind, String promotionCode,
                                 UUID operatorEmployeeId, UUID approverEmployeeId, BigDecimal quantity,
                                 BigDecimal discountGross) {
    }

    /** A closed order's billing line priced as an OFFER (S53): its net billed quantity and its round line. */
    public record OfferCostRecord(UUID orderId, UUID lineId, UUID storeId, LocalDate businessDate, UUID itemId,
                                  String familyCode, String promotionCode, UUID operatorEmployeeId,
                                  UUID approverEmployeeId, BigDecimal netQuantity, UUID consumedLineId,
                                  BigDecimal consumedLineQuantity) {
    }

    public void insertOfferCost(OfferCostRecord o, Instant projectedAt) {
        jdbc.sql("""
                        INSERT INTO reporting.offer_cost_record (order_id, line_id, store_id, business_date, item_id,
                            family_code, promotion_code, operator_employee_id, approver_employee_id, net_quantity,
                            consumed_line_id, consumed_line_quantity, projected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (order_id, line_id) DO NOTHING""")
                .params(o.orderId(), o.lineId(), o.storeId(), o.businessDate(), o.itemId(), o.familyCode(),
                        o.promotionCode(), o.operatorEmployeeId(), o.approverEmployeeId(), o.netQuantity(),
                        o.consumedLineId(), o.consumedLineQuantity(), Timestamp.from(projectedAt))
                .update();
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

    public void insertConsumption(ConsumptionRecord c, Instant projectedAt) {
        jdbc.sql("""
                        INSERT INTO reporting.consumption_record (movement_id, store_id, business_date, sale_item_id,
                            family_code, line_id, warehouse_id, stock_item_id, type, quantity, value, source_type,
                            source_id, projected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (movement_id) DO NOTHING""")
                .params(c.movementId(), c.storeId(), c.businessDate(), c.saleItemId(), c.familyCode(), c.lineId(),
                        c.warehouseId(), c.stockItemId(), c.type(), c.quantity(), c.value(), c.sourceType(),
                        c.sourceId(), Timestamp.from(projectedAt))
                .update();
    }

    public void insertWaste(WasteRecord w, Instant projectedAt) {
        jdbc.sql("""
                        INSERT INTO reporting.consumption_waste_record (void_id, original_movement_id, store_id,
                            business_date, sale_item_id, family_code, line_id, warehouse_id, stock_item_id, quantity,
                            value, projected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (void_id, original_movement_id) DO NOTHING""")
                .params(w.voidId(), w.originalMovementId(), w.storeId(), w.businessDate(), w.saleItemId(),
                        w.familyCode(), w.lineId(), w.warehouseId(), w.stockItemId(), w.quantity(), w.value(),
                        Timestamp.from(projectedAt))
                .update();
    }

    public void insertVariance(VarianceRecord v, Instant projectedAt) {
        jdbc.sql("""
                        INSERT INTO reporting.cost_variance_record (variance_id, source_type, source_id, store_id,
                            business_date, warehouse_id, stock_item_id, receipt_line_id, value, projected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (variance_id) DO NOTHING""")
                .params(v.varianceId(), v.sourceType(), v.sourceId(), v.storeId(), v.businessDate(),
                        v.warehouseId(), v.stockItemId(), v.receiptLineId(), v.value(), Timestamp.from(projectedAt))
                .update();
    }

    public void insertDiscount(DiscountRecord d, Instant projectedAt) {
        jdbc.sql("""
                        INSERT INTO reporting.discount_record (source_type, source_id, line_id, store_id,
                            business_date, item_id, family_code, origin, kind, promotion_code, operator_employee_id,
                            approver_employee_id, quantity, discount_gross, projected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (source_type, source_id, line_id) DO NOTHING""")
                .params(d.sourceType(), d.sourceId(), d.lineId(), d.storeId(), d.businessDate(), d.itemId(),
                        d.familyCode(), d.origin(), d.kind(), d.promotionCode(), d.operatorEmployeeId(),
                        d.approverEmployeeId(), d.quantity(), d.discountGross(), Timestamp.from(projectedAt))
                .update();
    }

    /** A rebuild's first step: the store's projection rows in {@code [from, to]} (projections, never facts). */
    public void deleteRange(UUID storeId, LocalDate from, LocalDate to) {
        for (String table : PROJECTIONS) {
            jdbc.sql("DELETE FROM reporting." + table + " WHERE store_id = ? AND business_date BETWEEN ? AND ?")
                    .params(storeId, from, to).update();
        }
    }
}
