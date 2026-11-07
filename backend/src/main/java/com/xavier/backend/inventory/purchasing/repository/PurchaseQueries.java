package com.xavier.backend.inventory.purchasing.repository;

import com.xavier.backend.inventory.purchasing.model.dto.PurchaseRow;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The purchases report (D131): per supplier or per stock item, the value confirmed receipts of a store brought in on
 * business days {@code from..to} ({@code round(quantity × unit cost, 4)} per line, as the ledger values a RECEIPT),
 * and the cost corrections (revaluation + variance) dated in that period.
 */
@Repository
public class PurchaseQueries {

    private static final String BY_SUPPLIER = """
            SELECT k.id, s.code, s.name, NULL::numeric AS quantity_base, sum(k.received) AS received_value,
                   sum(k.corrected) AS correction_value
            FROM (SELECT r.supplier_id AS id, round(l.quantity_base * l.unit_cost_base, 4) AS received, 0 AS corrected
                  FROM inventory.goods_receipt r JOIN inventory.goods_receipt_line l ON l.receipt_id = r.id
                  WHERE r.store_id = :store AND r.status = 'CONFIRMED' AND r.business_date BETWEEN :from AND :to
                  UNION ALL
                  SELECT r.supplier_id, 0, c.revaluation_value + c.variance_value
                  FROM inventory.receipt_cost_correction c JOIN inventory.goods_receipt r ON r.id = c.receipt_id
                  WHERE r.store_id = :store AND c.business_date BETWEEN :from AND :to) k
            JOIN inventory.supplier s ON s.id = k.id
            GROUP BY k.id, s.code, s.name ORDER BY s.code""";

    private static final String BY_ITEM = """
            SELECT k.id, i.code, i.name, sum(k.quantity) AS quantity_base, sum(k.received) AS received_value,
                   sum(k.corrected) AS correction_value
            FROM (SELECT l.stock_item_id AS id, l.quantity_base AS quantity,
                         round(l.quantity_base * l.unit_cost_base, 4) AS received, 0 AS corrected
                  FROM inventory.goods_receipt r JOIN inventory.goods_receipt_line l ON l.receipt_id = r.id
                  WHERE r.store_id = :store AND r.status = 'CONFIRMED' AND r.business_date BETWEEN :from AND :to
                  UNION ALL
                  SELECT l.stock_item_id, 0, 0, c.revaluation_value + c.variance_value
                  FROM inventory.receipt_cost_correction c
                  JOIN inventory.goods_receipt r ON r.id = c.receipt_id
                  JOIN inventory.goods_receipt_line l ON l.id = c.receipt_line_id
                  WHERE r.store_id = :store AND c.business_date BETWEEN :from AND :to) k
            JOIN inventory.stock_item i ON i.id = k.id
            GROUP BY k.id, i.code, i.name ORDER BY i.code""";

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
                .query((rs, n) -> new PurchaseRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getBigDecimal("quantity_base"), rs.getBigDecimal("received_value"),
                        rs.getBigDecimal("correction_value"),
                        rs.getBigDecimal("received_value").add(rs.getBigDecimal("correction_value"))))
                .list();
    }
}
