package com.xavier.backend.inventory.purchasing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * What a supplier sells of a stock item in one pack (none = the base unit), with the supplier's code and the last
 * cost per base unit (D123). The last cost is configuration, updated by each confirmed receipt; the receipt line is
 * the fact. Order lines default to it.
 */
@Entity
@Table(schema = "inventory", name = "supplier_item")
public class SupplierItem {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID supplierId;

    @Column(nullable = false, updatable = false)
    private UUID stockItemId;

    @Column(updatable = false)
    private UUID packId;

    private String supplierCode;

    @Column(precision = 19, scale = 6)
    private BigDecimal lastUnitCost;

    private Instant lastCostAt;

    @Version
    private Long version;

    protected SupplierItem() {
        // JPA
    }

    /** @param lastUnitCost per base unit, or {@code null} until a receipt sets it */
    public static SupplierItem of(UUID supplierId, UUID stockItemId, UUID packId, String supplierCode,
                                  BigDecimal lastUnitCost, Instant now) {
        SupplierItem item = new SupplierItem();
        item.supplierId = supplierId;
        item.stockItemId = stockItemId;
        item.packId = packId;
        item.supplierCode = supplierCode == null || supplierCode.isBlank() ? null : supplierCode.strip();
        item.lastUnitCost = lastUnitCost;
        item.lastCostAt = lastUnitCost == null ? null : now;
        return item;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public UUID getStockItemId() {
        return stockItemId;
    }

    public UUID getPackId() {
        return packId;
    }

    public String getSupplierCode() {
        return supplierCode;
    }

    public BigDecimal getLastUnitCost() {
        return lastUnitCost;
    }

    public Instant getLastCostAt() {
        return lastCostAt;
    }
}
