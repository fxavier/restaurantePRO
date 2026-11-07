package com.xavier.backend.inventory.consumption;

import static com.xavier.backend.inventory.consumption.Kitchen.component;
import static com.xavier.backend.inventory.consumption.Kitchen.ingredient;
import static com.xavier.backend.inventory.consumption.Kitchen.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.OrderMode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D114, D116: a line consumes from its destination's route, else the store's default route. */
@InventoryModuleTest
class ConsumptionRouteTest extends ConsumptionTestBase {

    @Test
    void aBarLineConsumesFromTheBarAndOtherDestinationsFromTheDefault() throws Exception {
        UUID beer = kitchen.catalogItem(null);
        UUID soup = kitchen.catalogItem(null);
        UUID bottle = kitchen.stockItem("un");
        UUID stock = kitchen.stockItem("ml");
        kitchen.saleRecipe(beer, null, "1", ingredient(bottle, "1", "un"));
        kitchen.saleRecipe(soup, null, "1", ingredient(stock, "300", "ml"));
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(beer, "2", "BAR"),
                line(soup, "1", "KITCHEN"), line(soup, "1", null));

        consumption.consume(round.batchId());

        assertThat(kitchen.quantity(kitchen.bar, bottle)).isEqualByComparingTo("-2");
        assertThat(kitchen.quantity(kitchen.kitchen, bottle)).isEqualByComparingTo("0");
        assertThat(kitchen.quantity(kitchen.kitchen, stock)).isEqualByComparingTo("-600");
    }

    @Test
    void aMenuConsumesOnlyItsComponentsEachFromItsOwnRoute() throws Exception {
        UUID menu = kitchen.catalogItem(null);
        UUID starter = kitchen.catalogItem(null);
        UUID drink = kitchen.catalogItem(null);
        UUID box = kitchen.stockItem("un");
        UUID bread = kitchen.stockItem("g");
        UUID juice = kitchen.stockItem("ml");
        kitchen.saleRecipe(menu, null, "1", ingredient(box, "1", "un"));
        kitchen.saleRecipe(starter, null, "1", ingredient(bread, "80", "g"));
        kitchen.saleRecipe(drink, null, "1", ingredient(juice, "250", "ml"));
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN,
                Kitchen.menu(menu, "2", component(starter, "1", "KITCHEN"), component(drink, "1", "BAR")));

        consumption.consume(round.batchId());

        assertThat(kitchen.quantity(kitchen.kitchen, bread)).isEqualByComparingTo("-160");
        assertThat(kitchen.quantity(kitchen.bar, juice)).isEqualByComparingTo("-500");
        assertThat(kitchen.quantity(kitchen.kitchen, box)).isEqualByComparingTo("0");
        UUID starterComponent = round.lines().getFirst().components().getFirst().componentId();
        assertThat(kitchen.movements("ORDER_LINE_COMPONENT", starterComponent)).singleElement()
                .satisfies(m -> assertThat(m.get("source_line_id")).isEqualTo(round.lines().getFirst().lineId()));
    }

    @Test
    void aStoreWithoutAnyRouteRaisesAnException() throws Exception {
        UUID store = fixtures.newActiveStore();
        UUID item = kitchen.catalogItem(null);
        kitchen.saleRecipe(item, null, "1", ingredient(kitchen.stockItem("un"), "1", "un"));
        BatchFacts round = new BatchFacts(UUID.randomUUID(), UUID.randomUUID(), store, Kitchen.DAY,
                OrderMode.DINE_IN, clock.instant(), java.util.List.of(line(item, "1", "KITCHEN")));
        org.mockito.Mockito.when(orders.batchFacts(round.batchId())).thenReturn(round);

        consumption.consume(round.batchId());

        assertThat(jdbc.sql("SELECT kind FROM inventory.consumption_exception WHERE store_id = ?").param(store)
                .query(String.class).list()).containsExactly("NO_ROUTE");
    }
}
