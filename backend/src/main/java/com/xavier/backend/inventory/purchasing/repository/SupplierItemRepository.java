package com.xavier.backend.inventory.purchasing.repository;

import com.xavier.backend.inventory.purchasing.model.SupplierItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SupplierItemRepository extends JpaRepository<SupplierItem, UUID> {

    List<SupplierItem> findBySupplierIdOrderByStockItemId(UUID supplierId);

    @Query("""
            SELECT s FROM SupplierItem s WHERE s.supplierId = :supplierId AND s.stockItemId = :stockItemId
            AND (s.packId = :packId OR (s.packId IS NULL AND :packId IS NULL))""")
    Optional<SupplierItem> find(UUID supplierId, UUID stockItemId, UUID packId);

    /**
     * Records a confirmed receipt's cost as the supplier's last cost of the item in that pack (D123), creating the
     * supplier item when it is new. An older confirmation never overwrites a newer one. One statement, so two
     * receipts of the same item confirming at once never collide on the unique key.
     */
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO inventory.supplier_item
                (id, supplier_id, stock_item_id, pack_id, last_unit_cost, last_cost_at, version)
            VALUES (:id, :supplierId, :stockItemId, :packId, :unitCost, :at, 0)
            ON CONFLICT (supplier_id, stock_item_id, coalesce(pack_id, '00000000-0000-0000-0000-000000000000'::uuid))
            DO UPDATE SET last_unit_cost = excluded.last_unit_cost, last_cost_at = excluded.last_cost_at,
                          version = inventory.supplier_item.version + 1
            WHERE inventory.supplier_item.last_cost_at IS NULL
               OR inventory.supplier_item.last_cost_at <= excluded.last_cost_at""")
    int recordLastCost(UUID id, UUID supplierId, UUID stockItemId, UUID packId, BigDecimal unitCost, Instant at);
}
