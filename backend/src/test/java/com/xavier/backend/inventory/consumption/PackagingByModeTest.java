package com.xavier.backend.inventory.consumption;

import static com.xavier.backend.inventory.consumption.Kitchen.ingredient;
import static com.xavier.backend.inventory.consumption.Kitchen.line;
import static com.xavier.backend.inventory.consumption.Kitchen.packaging;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.orders.OrderMode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D115: packaging limited to modes is consumed only by orders of those modes. */
@InventoryModuleTest
class PackagingByModeTest extends ConsumptionTestBase {

    @Test
    void aTakeawayBurgerTakesItsBoxAndADineInOneDoesNot() throws Exception {
        UUID burger = kitchen.catalogItem(null);
        UUID bun = kitchen.stockItem("un");
        UUID box = kitchen.stockItem("un");
        kitchen.saleRecipe(burger, null, "1",
                ingredient(bun, "1", "un") + "," + packaging(box, "1", "un", "TAKEAWAY", "DELIVERY"));

        consumption.consume(kitchen.round(clock.instant(), OrderMode.TAKEAWAY, line(burger, "2", "KITCHEN"))
                .batchId());
        consumption.consume(kitchen.round(clock.instant(), OrderMode.DINE_IN, line(burger, "3", "KITCHEN"))
                .batchId());

        assertThat(kitchen.quantity(kitchen.kitchen, bun)).isEqualByComparingTo("-5");
        assertThat(kitchen.quantity(kitchen.kitchen, box)).isEqualByComparingTo("-2");
    }

    @Test
    void theSameStockItemTwiceInARecipeIsOneMovement() throws Exception {
        UUID burger = kitchen.catalogItem(null);
        UUID napkin = kitchen.stockItem("un");
        kitchen.saleRecipe(burger, null, "1",
                ingredient(napkin, "1", "un") + "," + packaging(napkin, "2", "un", "TAKEAWAY"));
        var round = kitchen.round(clock.instant(), OrderMode.TAKEAWAY, line(burger, "1", "KITCHEN"));

        consumption.consume(round.batchId());

        assertThat(kitchen.movements("ORDER_LINE", round.lines().getFirst().lineId())).singleElement()
                .satisfies(m -> assertThat((java.math.BigDecimal) m.get("quantity")).isEqualByComparingTo("-3"));
    }
}
