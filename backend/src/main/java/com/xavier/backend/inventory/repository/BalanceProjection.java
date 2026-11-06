package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.BalanceKey;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The reconciliation side of the balance projection (D113): Σ movements against the balance rows, the keys a rebuild
 * replays, and the ledger rows in posting order.
 */
@Repository
public class BalanceProjection {

    private final JdbcClient jdbc;

    BalanceProjection(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every (warehouse, item) whose balance differs from Σ of its movements, in quantity or value. */
    public List<BalanceDrift> drifts(UUID storeId) {
        return jdbc.sql("""
                        WITH ledger AS (
                            SELECT warehouse_id, stock_item_id, sum(quantity) AS quantity, sum(value) AS value
                            FROM inventory.stock_movement
                            WHERE (CAST(:store AS uuid) IS NULL OR store_id = :store)
                            GROUP BY warehouse_id, stock_item_id),
                        balance AS (
                            SELECT b.* FROM inventory.stock_balance b JOIN inventory.warehouse w ON w.id = b.warehouse_id
                            WHERE (CAST(:store AS uuid) IS NULL OR w.store_id = :store))
                        SELECT w.store_id, k.warehouse_id, k.stock_item_id,
                               coalesce(b.quantity, 0) AS balance_quantity, coalesce(b.value, 0) AS balance_value,
                               coalesce(l.quantity, 0) AS ledger_quantity, coalesce(l.value, 0) AS ledger_value
                        FROM (SELECT warehouse_id, stock_item_id FROM ledger
                              UNION SELECT warehouse_id, stock_item_id FROM balance) k
                        JOIN inventory.warehouse w ON w.id = k.warehouse_id
                        LEFT JOIN balance b ON b.warehouse_id = k.warehouse_id AND b.stock_item_id = k.stock_item_id
                        LEFT JOIN ledger l ON l.warehouse_id = k.warehouse_id AND l.stock_item_id = k.stock_item_id
                        WHERE coalesce(b.quantity, 0) <> coalesce(l.quantity, 0)
                           OR coalesce(b.value, 0) <> coalesce(l.value, 0)
                        ORDER BY k.warehouse_id, k.stock_item_id""")
                .param("store", storeId)
                .query((rs, n) -> new BalanceDrift(rs.getObject("store_id", UUID.class),
                        rs.getObject("warehouse_id", UUID.class), rs.getObject("stock_item_id", UUID.class),
                        rs.getBigDecimal("balance_quantity"), rs.getBigDecimal("balance_value"),
                        rs.getBigDecimal("ledger_quantity"), rs.getBigDecimal("ledger_value")))
                .list();
    }

    /** Negative balances per store (D118 gauge and status). */
    public Map<UUID, Long> negativeBalancesPerStore() {
        return jdbc.sql("""
                        SELECT w.store_id, count(*) AS negatives FROM inventory.stock_balance b
                        JOIN inventory.warehouse w ON w.id = b.warehouse_id
                        WHERE b.quantity < 0 GROUP BY w.store_id""")
                .query((rs, n) -> Map.entry(rs.getObject("store_id", UUID.class), rs.getLong("negatives"))).list()
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** The keys with a balance row or a movement, in one warehouse or everywhere ({@code warehouseId} null). */
    public List<BalanceKey> keys(UUID warehouseId) {
        return jdbc.sql("""
                        SELECT warehouse_id, stock_item_id FROM inventory.stock_balance
                        WHERE CAST(:warehouse AS uuid) IS NULL OR warehouse_id = :warehouse
                        UNION
                        SELECT DISTINCT warehouse_id, stock_item_id FROM inventory.stock_movement
                        WHERE CAST(:warehouse AS uuid) IS NULL OR warehouse_id = :warehouse""")
                .param("warehouse", warehouseId)
                .query((rs, n) -> new BalanceKey(rs.getObject("warehouse_id", UUID.class),
                        rs.getObject("stock_item_id", UUID.class))).list();
    }

    /** The movements of one warehouse (or all), in posting order per key, handed one by one to {@code row}. */
    public void replay(UUID warehouseId, Consumer<Recorded> row) {
        jdbc.sql("""
                        SELECT warehouse_id, stock_item_id, posting_seq, quantity, value, unit_cost
                        FROM inventory.stock_movement
                        WHERE CAST(:warehouse AS uuid) IS NULL OR warehouse_id = :warehouse
                        ORDER BY warehouse_id, stock_item_id, posting_seq""")
                .param("warehouse", warehouseId)
                .query(rs -> {
                    row.accept(new Recorded(new BalanceKey(rs.getObject("warehouse_id", UUID.class),
                            rs.getObject("stock_item_id", UUID.class)), rs.getLong("posting_seq"),
                            rs.getBigDecimal("quantity"), rs.getBigDecimal("value"), rs.getBigDecimal("unit_cost")));
                });
    }

    /** A recorded movement as the rebuild replays it. */
    public record Recorded(BalanceKey key, long postingSeq, BigDecimal quantity, BigDecimal value,
                           BigDecimal unitCost) {
    }
}
