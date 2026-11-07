package com.xavier.backend.inventory.consumption;

import static com.xavier.backend.inventory.consumption.Kitchen.ingredient;
import static com.xavier.backend.inventory.consumption.Kitchen.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.OrderMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * D115, D133 "a recipe changes while a round is processed late": consumption uses the version in force at the line's
 * confirmation, whenever the round is processed. Quantities: sold × ingredient ÷ yield, in the base unit, HALF_UP 6.
 */
@InventoryModuleTest
class RecipeVersionAtConfirmationTest extends ConsumptionTestBase {

    @Test
    void aRoundProcessedAfterARecipeChangeUsesTheVersionInForceAtItsConfirmation() throws Exception {
        UUID pizza = kitchen.catalogItem(null);
        UUID flour = kitchen.stockItem("kg");
        kitchen.receive(kitchen.kitchen, flour, "10", "2.000000");
        UUID recipe = kitchen.saleRecipe(pizza, null, "1", ingredient(flour, "250", "g"));
        clock.advance(Duration.ofMinutes(1));
        BatchFacts early = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(pizza, "2", "KITCHEN"));
        clock.advance(Duration.ofMinutes(1));
        kitchen.version(recipe, "1", ingredient(flour, "300", "g"));
        kitchen.activate(recipe, 2, null);
        clock.advance(Duration.ofMinutes(1));
        BatchFacts late = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(pizza, "2", "KITCHEN"));

        consumption.consume(late.batchId());
        consumption.consume(early.batchId());

        Map<String, Object> first = kitchen.movements("ORDER_LINE", early.lines().getFirst().lineId()).getFirst();
        assertThat(first.get("type")).isEqualTo("SALE_CONSUMPTION");
        assertThat((java.math.BigDecimal) first.get("quantity")).isEqualByComparingTo("-0.5");
        assertThat((java.math.BigDecimal) first.get("value")).isEqualByComparingTo("-1.0000");
        assertThat((java.math.BigDecimal) kitchen.movements("ORDER_LINE", late.lines().getFirst().lineId())
                .getFirst().get("quantity")).isEqualByComparingTo("-0.6");
        assertThat(first.get("recipe_version_id")).isNotEqualTo(
                kitchen.movements("ORDER_LINE", late.lines().getFirst().lineId()).getFirst().get("recipe_version_id"));
    }

    @Test
    void aMovementRecordsTheSaleItsDayAndItsConfirmationInstant() throws Exception {
        UUID pizza = kitchen.catalogItem(null);
        UUID flour = kitchen.stockItem("kg");
        kitchen.saleRecipe(pizza, null, "3", ingredient(flour, "1", "g"));
        Instant at = clock.instant();
        BatchFacts round = kitchen.round(at, OrderMode.DINE_IN, line(pizza, "1", "KITCHEN"));

        consumption.consume(round.batchId());

        Map<String, Object> movement = kitchen.movements("ORDER_LINE", round.lines().getFirst().lineId()).getFirst();
        assertThat((java.math.BigDecimal) movement.get("quantity")).isEqualByComparingTo("-0.000333");
        assertThat(movement.get("sale_item_id")).isEqualTo(pizza);
        assertThat(movement.get("source_line_id")).isEqualTo(round.lines().getFirst().lineId());
        assertThat(movement.get("business_date").toString()).isEqualTo(Kitchen.DAY.toString());
        assertThat(((java.sql.Timestamp) movement.get("occurred_at")).toInstant()).isEqualTo(at);
    }

    @Test
    void theVariantsRecipeWinsOverTheItems() throws Exception {
        UUID large = UUID.randomUUID();
        UUID pizza = kitchen.catalogItem(null, large);
        UUID flour = kitchen.stockItem("g");
        kitchen.saleRecipe(pizza, null, "1", ingredient(flour, "250", "g"));
        kitchen.saleRecipe(pizza, large, "1", ingredient(flour, "400", "g"));
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, Kitchen.variantLine(pizza, large, "1"),
                line(pizza, "1", "KITCHEN"));

        consumption.consume(round.batchId());

        assertThat((java.math.BigDecimal) kitchen.movements("ORDER_LINE", round.lines().get(0).lineId()).getFirst()
                .get("quantity")).isEqualByComparingTo("-400");
        assertThat((java.math.BigDecimal) kitchen.movements("ORDER_LINE", round.lines().get(1).lineId()).getFirst()
                .get("quantity")).isEqualByComparingTo("-250");
    }
}
