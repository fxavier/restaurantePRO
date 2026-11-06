package com.xavier.backend.inventory.service;

import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.model.StockUnit;
import com.xavier.backend.inventory.repository.StockUnitRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/** The seeded stock units (D114) and conversions into an item's base unit. */
@Service
public class Units {

    private final StockUnitRepository units;

    Units(StockUnitRepository units) {
        this.units = units;
    }

    /** @throws InventoryRuleException UNKNOWN_UNIT */
    public StockUnit unit(String code) {
        return units.findById(code).orElseThrow(() -> InventoryRuleException.unknownUnit(code));
    }

    /**
     * {@code quantity} of {@code unit} (the base unit when null) in {@code baseUnit}, HALF_UP at scale 6.
     *
     * @throws InventoryRuleException UNKNOWN_UNIT or UNIT_DIMENSION_MISMATCH
     */
    public BigDecimal toBase(BigDecimal quantity, String unit, String baseUnit) {
        StockUnit base = unit(baseUnit);
        return (unit == null || unit.equals(baseUnit) ? base : unit(unit)).convert(quantity, base);
    }
}
