package com.xavier.backend.inventory.purchasing.repository;

import com.xavier.backend.inventory.purchasing.model.dto.PurchaseRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The net purchases report (D131): per supplier or per stock item, over business days {@code from..to} of a store,
 * what confirmed receipts brought in ({@code round(quantity × unit cost, 4)} per line, as the ledger values a RECEIPT),
 * what confirmed supplier returns sent back at the supplier's credit (the SUPPLIER_RETURN value plus a linked
 * return's cost variance), and the cost corrections (revaluation + variance) dated in the period.
 */
@Repository
public class PurchaseQueries {

    /** One row per fact, keyed by supplier and stock item; the report groups them by one of the two. */
    private static final String FACTS = """
            SELECT r.supplier_id, l.stock_item_id, l.quantity_base AS received_qty, 0 AS returned_qty,
                   round(l.quantity_base * l.unit_cost_base, 4) AS received, 0 AS returned, 0 AS corrected
            FROM inventory.goods_receipt r JOIN inventory.goods_receipt_line l ON l.receipt_id = r.id
            WHERE r.store_id = :store AND r.status = 'CONFIRMED' AND r.business_date BETWEEN :from AND :to
            UNION ALL
            SELECT s.supplier_id, l.stock_item_id, 0, l.quantity_base, 0,
                   -m.value + coalesce(v.value, 0), 0
            FROM inventory.supplier_return s
            JOIN inventory.supplier_return_line l ON l.return_id = s.id
            JOIN inventory.stock_movement m ON m.source_type = 'SUPPLIER_RETURN' AND m.source_id = s.id
                AND m.source_line_id = l.id AND m.type = 'SUPPLIER_RETURN'
            LEFT JOIN inventory.cost_variance v ON v.return_line_id = l.id
            WHERE s.store_id = :store AND s.status = 'CONFIRMED' AND s.business_date BETWEEN :from AND :to
            UNION ALL
            SELECT r.supplier_id, l.stock_item_id, 0, 0, 0, 0, c.revaluation_value + c.variance_value
            FROM inventory.receipt_cost_correction c
            JOIN inventory.goods_receipt r ON r.id = c.receipt_id
            JOIN inventory.goods_receipt_line l ON l.id = c.receipt_line_id
            WHERE r.store_id = :store AND c.business_date BETWEEN :from AND :to""";

    private static final String BY_SUPPLIER = """
            SELECT k.supplier_id AS id, s.code, s.name, NULL::numeric AS received_qty, NULL::numeric AS returned_qty,
                   sum(k.received) AS received, sum(k.returned) AS returned, sum(k.corrected) AS corrected
            FROM (%s) k JOIN inventory.supplier s ON s.id = k.supplier_id
            GROUP BY k.supplier_id, s.code, s.name ORDER BY s.code""".formatted(FACTS);

    private static final String BY_ITEM = """
            SELECT k.stock_item_id AS id, i.code, i.name, sum(k.received_qty) AS received_qty,
                   sum(k.returned_qty) AS returned_qty, sum(k.received) AS received, sum(k.returned) AS returned,
                   sum(k.corrected) AS corrected
            FROM (%s) k JOIN inventory.stock_item i ON i.id = k.stock_item_id
            GROUP BY k.stock_item_id, i.code, i.name ORDER BY i.code""".formatted(FACTS);

    private final JdbcClient jdbc;

    PurchaseQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PurchaseRow> bySupplier(UUID storeId, LocalDate from, LocalDate to) {
        return rows(BY_SUPPLIER, storeId, from, to);
    }

    public List<PurchaseRow> byItem(UUID storeId, LocalDate from, LocalDate to) {
        return rows(BY_ITEM, storeId, from, to);
    }

    private List<PurchaseRow> rows(String sql, UUID storeId, LocalDate from, LocalDate to) {
        return jdbc.sql(sql).param("store", storeId).param("from", from).param("to", to)
                .query((rs, n) -> {
                    BigDecimal receivedQty = rs.getBigDecimal("received_qty");
                    BigDecimal returnedQty = rs.getBigDecimal("returned_qty");
                    BigDecimal received = rs.getBigDecimal("received");
                    BigDecimal returned = rs.getBigDecimal("returned");
                    BigDecimal corrected = rs.getBigDecimal("corrected");
                    return new PurchaseRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                            receivedQty, returnedQty, receivedQty == null ? null : receivedQty.subtract(returnedQty),
                            received, returned, corrected, received.subtract(returned).add(corrected));
                })
                .list();
    }
}
