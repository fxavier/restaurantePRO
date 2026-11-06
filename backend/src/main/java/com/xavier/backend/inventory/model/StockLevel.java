package com.xavier.backend.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** The minimum of a stock item in a warehouse (§5 "stock mínimo", D114), in the item's base unit. */
@Entity
@Table(schema = "inventory", name = "stock_level")
@IdClass(StockLevel.Key.class)
public class StockLevel {

    @Id
    private UUID warehouseId;

    @Id
    private UUID stockItemId;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal minQuantity;

    @Version
    private Long version;

    protected StockLevel() {
        // JPA
    }

    public static StockLevel create(UUID warehouseId, UUID stockItemId, BigDecimal minQuantity) {
        StockLevel level = new StockLevel();
        level.warehouseId = warehouseId;
        level.stockItemId = stockItemId;
        level.change(minQuantity);
        return level;
    }

    public void change(BigDecimal minQuantity) {
        if (minQuantity.signum() < 0) {
            throw new IllegalArgumentException("a minimum is never negative");
        }
        this.minQuantity = minQuantity.setScale(StockPosition.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public UUID getStockItemId() {
        return stockItemId;
    }

    public BigDecimal getMinQuantity() {
        return minQuantity;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }

    public record Key(UUID warehouseId, UUID stockItemId) implements Serializable {
    }
}
