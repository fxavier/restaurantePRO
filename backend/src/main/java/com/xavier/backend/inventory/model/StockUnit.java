package com.xavier.backend.inventory.model;

import com.xavier.backend.inventory.exception.InventoryRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A stock unit (D114): seeded by V13001, never edited by the application. {@code factorToBase} relates it to its
 * dimension's base (g, ml, un). Catalog's sale unit is a different thing and has no conversions.
 */
@Entity
@Table(schema = "inventory", name = "stock_unit")
public class StockUnit {

    @Id
    @Column(length = 10)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Dimension dimension;

    @Column(nullable = false, precision = 19, scale = 9)
    private BigDecimal factorToBase;

    protected StockUnit() {
        // JPA
    }

    public static StockUnit of(String code, Dimension dimension, String factorToBase) {
        StockUnit unit = new StockUnit();
        unit.code = code;
        unit.dimension = dimension;
        unit.factorToBase = new BigDecimal(factorToBase);
        return unit;
    }

    /**
     * {@code quantity} of this unit expressed in {@code target}, HALF_UP at the ledger's quantity scale.
     *
     * @throws InventoryRuleException UNIT_DIMENSION_MISMATCH for a unit of another dimension
     */
    public BigDecimal convert(BigDecimal quantity, StockUnit target) {
        if (dimension != target.dimension) {
            throw InventoryRuleException.dimensionMismatch(code, target.code);
        }
        return quantity.multiply(factorToBase).divide(target.factorToBase, StockPosition.QUANTITY_SCALE,
                RoundingMode.HALF_UP);
    }

    public String getCode() {
        return code;
    }

    public Dimension getDimension() {
        return dimension;
    }
}
