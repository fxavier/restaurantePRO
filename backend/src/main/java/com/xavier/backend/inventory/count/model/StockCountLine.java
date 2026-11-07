package com.xavier.backend.inventory.count.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/**
 * One stock item of a count: what was counted, in the base unit, and when ({@code countedAt} is the line's cut-off,
 * D119). Approval writes the expected quantity at the cut-off and the difference once.
 */
@Entity
@Table(schema = "inventory", name = "stock_count_line")
public class StockCountLine {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID stockItemId;

    private BigDecimal countedQuantity;

    @Column(nullable = false, length = 8)
    private String unit;

    private Instant countedAt;

    @Column(length = 10)
    private String countedByActorKind;

    private UUID countedByActorId;

    private BigDecimal unitCost;

    private BigDecimal expectedQuantity;

    private BigDecimal difference;

    protected StockCountLine() {
        // JPA
    }

    static StockCountLine of(UUID stockItemId, String baseUnit) {
        StockCountLine line = new StockCountLine();
        line.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        line.stockItemId = stockItemId;
        line.unit = baseUnit;
        return line;
    }

    void record(BigDecimal quantity, Instant at, BigDecimal cost, String actorKind, UUID actorId) {
        countedQuantity = quantity;
        countedAt = at;
        unitCost = cost;
        countedByActorKind = actorKind;
        countedByActorId = actorId;
    }

    /** @return the difference, counted − expected */
    BigDecimal settle(BigDecimal expected) {
        expectedQuantity = expected;
        difference = countedQuantity.subtract(expected);
        return difference;
    }

    boolean wasCountedBy(String actorKind, UUID actorId) {
        return actorKind.equals(countedByActorKind) && actorId.equals(countedByActorId);
    }

    public boolean isCounted() {
        return countedQuantity != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStockItemId() {
        return stockItemId;
    }

    public BigDecimal getCountedQuantity() {
        return countedQuantity;
    }

    public String getUnit() {
        return unit;
    }

    public Instant getCountedAt() {
        return countedAt;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public BigDecimal getExpectedQuantity() {
        return expectedQuantity;
    }

    public BigDecimal getDifference() {
        return difference;
    }
}
