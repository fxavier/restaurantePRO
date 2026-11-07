package com.xavier.backend.inventory.purchasing.repository;

import com.xavier.backend.inventory.purchasing.model.ReceiptShare;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The ledger history a receipt line's share still in stock is replayed from ({@link ReceiptShare}): read under the
 * balance row's lock, so no movement of the item and warehouse can post meanwhile.
 */
@Repository
public class ShareQueries {

    private final JdbcClient jdbc;

    ShareQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The receipt line's RECEIPT movement position: its posting_seq and the quantity right after it. */
    public ReceiptPoint receiptPoint(UUID warehouseId, UUID stockItemId, UUID receiptId) {
        long seq = jdbc.sql("""
                        SELECT posting_seq FROM inventory.stock_movement
                        WHERE source_type = 'GOODS_RECEIPT' AND source_id = ? AND stock_item_id = ? AND type = 'RECEIPT'
                          AND warehouse_id = ?""")
                .params(receiptId, stockItemId, warehouseId).query(Long.class).single();
        BigDecimal after = jdbc.sql("""
                        SELECT coalesce(sum(quantity), 0) FROM inventory.stock_movement
                        WHERE warehouse_id = ? AND stock_item_id = ? AND posting_seq <= ?""")
                .params(warehouseId, stockItemId, seq).query(BigDecimal.class).single();
        return new ReceiptPoint(seq, after);
    }

    /** The item's movements in the warehouse after {@code seq}, flagging the returns of {@code receiptLineId}. */
    public List<ReceiptShare.Step> stepsAfter(UUID warehouseId, UUID stockItemId, long seq, UUID receiptLineId) {
        return jdbc.sql("""
                        SELECT m.quantity,
                               m.source_type = 'SUPPLIER_RETURN' AND m.source_line_id IN (
                                   SELECT l.id FROM inventory.supplier_return_line l WHERE l.receipt_line_id = ?)
                                   AS own_return
                        FROM inventory.stock_movement m
                        WHERE m.warehouse_id = ? AND m.stock_item_id = ? AND m.posting_seq > ? AND m.quantity <> 0
                        ORDER BY m.posting_seq""")
                .params(receiptLineId, warehouseId, stockItemId, seq)
                .query((rs, n) -> new ReceiptShare.Step(rs.getBigDecimal("quantity"), rs.getBoolean("own_return")))
                .list();
    }

    public record ReceiptPoint(long postingSeq, BigDecimal quantityAfter) {
    }
}
