package com.xavier.backend.inventory.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.inventory.exception.InventoryRuleException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Stock units convert within their dimension only (D114): MASS g/kg, VOLUME ml/l, COUNT un, by their factor to the
 * dimension's base, HALF_UP at scale 6. A pack converts a purchase quantity into the item's base unit.
 */
class UnitConversionTest {

    static final StockUnit G = StockUnit.of("g", Dimension.MASS, "1");
    static final StockUnit KG = StockUnit.of("kg", Dimension.MASS, "1000");
    static final StockUnit ML = StockUnit.of("ml", Dimension.VOLUME, "1");
    static final StockUnit L = StockUnit.of("l", Dimension.VOLUME, "1000");
    static final StockUnit UN = StockUnit.of("un", Dimension.COUNT, "1");

    @Test
    void gramsIntoAKilogramBase() {
        assertThat(G.convert(new BigDecimal("500"), KG)).isEqualTo(new BigDecimal("0.500000"));
    }

    @Test
    void litresIntoAMillilitreBase() {
        assertThat(L.convert(new BigDecimal("2.5"), ML)).isEqualTo(new BigDecimal("2500.000000"));
    }

    @Test
    void theSameUnitKeepsTheQuantityAtScale6() {
        assertThat(UN.convert(new BigDecimal("3"), UN)).isEqualTo(new BigDecimal("3.000000"));
    }

    @Test
    void roundingIsHalfUpAtScale6() {
        assertThat(G.convert(new BigDecimal("0.0005"), KG)).isEqualTo(new BigDecimal("0.000001"));
        assertThat(G.convert(new BigDecimal("0.0004"), KG)).isEqualTo(new BigDecimal("0.000000"));
    }

    @Test
    void anotherDimensionIsRefused() {
        assertThatThrownBy(() -> KG.convert(BigDecimal.ONE, L))
                .isInstanceOfSatisfying(InventoryRuleException.class, refused -> {
                    assertThat(refused.code()).isEqualTo("UNIT_DIMENSION_MISMATCH");
                    assertThat(refused.getMessage()).isEqualTo("The unit kg cannot be converted to l.");
                });
        assertThatThrownBy(() -> UN.convert(BigDecimal.ONE, G)).isInstanceOf(InventoryRuleException.class);
    }

    @Test
    void aPackConvertsPacksIntoTheBaseUnit() {
        StockItemPack box = StockItemPack.create(UUID.randomUUID(), "BOX12", "Caixa 12 un", new BigDecimal("12"));
        StockItemPack sack = StockItemPack.create(UUID.randomUUID(), "SACK", "Saco 25 kg", new BigDecimal("25"));

        assertThat(box.toBase(new BigDecimal("3"))).isEqualTo(new BigDecimal("36.000000"));
        assertThat(sack.toBase(new BigDecimal("0.5"))).isEqualTo(new BigDecimal("12.500000"));
    }
}
