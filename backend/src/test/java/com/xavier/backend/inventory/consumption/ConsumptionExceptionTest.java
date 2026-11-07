package com.xavier.backend.inventory.consumption;

import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static com.xavier.backend.inventory.InventoryFixtures.json;
import static com.xavier.backend.inventory.consumption.Kitchen.ingredient;
import static com.xavier.backend.inventory.consumption.Kitchen.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.OrderMode;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * D116: a line that cannot be consumed (no recipe, no route, an inactive stock item) is a visible exception, the rest
 * of the round still posts, and a reprocess after the fix consumes it once (with a late recipe, flagged).
 */
@InventoryModuleTest
class ConsumptionExceptionTest extends ConsumptionTestBase {

    static final String EXCEPTIONS = InventoryFixtures.BO + "/consumption-exceptions";

    @Test
    void aLineWithoutRecipeIsListedAndTheRestOfTheRoundPosts() throws Exception {
        UUID pizza = kitchen.catalogItem(null);
        UUID ghost = kitchen.catalogItem(null);
        UUID flour = kitchen.stockItem("g");
        kitchen.saleRecipe(pizza, null, "1", ingredient(flour, "250", "g"));
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(pizza, "1", "KITCHEN"),
                line(ghost, "1", "KITCHEN"));

        consumption.consume(round.batchId());

        assertThat(kitchen.quantity(kitchen.kitchen, flour)).isEqualByComparingTo("-250");
        JsonNode open = list(true);
        assertThat(open.get("totalElements").asLong()).isEqualTo(1);
        JsonNode exception = open.get("content").get(0);
        assertThat(exception.get("kind").asString()).isEqualTo("NO_RECIPE");
        assertThat(exception.get("sourceType").asString()).isEqualTo("ORDER_LINE");
        assertThat(exception.get("sourceId").asString()).isEqualTo(round.lines().get(1).lineId().toString());
        assertThat(exception.get("itemId").asString()).isEqualTo(ghost.toString());
        assertThat(exception.get("batchId").asString()).isEqualTo(round.batchId().toString());
    }

    @Test
    void aRedeliveredRoundRaisesTheExceptionOnce() throws Exception {
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN,
                line(kitchen.catalogItem(null), "1", "KITCHEN"));

        consumption.consume(round.batchId());
        consumption.consume(round.batchId());

        assertThat(kitchen.exceptions()).hasSize(1);
    }

    @Test
    void anInactiveStockItemIsAnException() throws Exception {
        UUID pizza = kitchen.catalogItem(null);
        UUID flour = kitchen.stockItem("g");
        kitchen.saleRecipe(pizza, null, "1", ingredient(flour, "250", "g"));
        mvc.perform(put(InventoryFixtures.BO + "/stock-items/" + flour)
                        .with(account(null, Permission.INVENTORY_MANAGE))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "%s", "name": "Old flour", "active": false, "version": 0}"""
                                .formatted(InventoryFixtures.code("I"))))
                .andExpect(status().isOk());

        consumption.consume(kitchen.round(clock.instant(), OrderMode.DINE_IN, line(pizza, "1", "KITCHEN"))
                .batchId());

        assertThat(kitchen.exceptions()).singleElement()
                .satisfies(e -> assertThat(e.get("kind")).isEqualTo("INACTIVE_STOCK_ITEM"));
        assertThat(kitchen.quantity(kitchen.kitchen, flour)).isEqualByComparingTo("0");
    }

    @Test
    void aReprocessAfterTheRecipeIsAddedConsumesOnceWithTheLateRecipeFlagged() throws Exception {
        UUID pizza = kitchen.catalogItem(null);
        UUID flour = kitchen.stockItem("g");
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(pizza, "2", "KITCHEN"));
        consumption.consume(round.batchId());
        UUID exception = (UUID) kitchen.exceptions().getFirst().get("id");

        reprocess(exception).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CONSUMPTION_UNRESOLVED"))
                .andExpect(jsonPath("$.kind").value("NO_RECIPE"));
        clock.advance(Duration.ofMinutes(5));
        kitchen.saleRecipe(pizza, null, "1", ingredient(flour, "250", "g"));

        reprocess(exception).andExpect(status().isOk())
                .andExpect(jsonPath("$.exception.resolution").value("REPROCESSED"))
                .andExpect(jsonPath("$.exception.lateRecipe").value(true))
                .andExpect(jsonPath("$.movements.length()").value(1));

        Map<String, Object> movement = kitchen.movements("ORDER_LINE", round.lines().getFirst().lineId())
                .getFirst();
        assertThat((java.math.BigDecimal) movement.get("quantity")).isEqualByComparingTo("-500");
        assertThat(movement.get("reason")).isEqualTo("late_recipe");
        assertThat(((java.sql.Timestamp) movement.get("occurred_at")).toInstant()).isEqualTo(round.confirmedAt());
        reprocess(exception).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSUMPTION_EXCEPTION_RESOLVED"));
        assertThat(list(true).get("totalElements").asLong()).isZero();
        assertThat(list(false).get("totalElements").asLong()).isEqualTo(1);
        consumption.consume(round.batchId());
        assertThat(kitchen.movements("ORDER_LINE", round.lines().getFirst().lineId())).hasSize(1);
    }

    @Test
    void exceptionsAreScopedToTheirStore() throws Exception {
        consumption.consume(kitchen.round(clock.instant(), OrderMode.DINE_IN,
                line(kitchen.catalogItem(null), "1", "KITCHEN")).batchId());
        UUID exception = (UUID) kitchen.exceptions().getFirst().get("id");
        UUID other = fixtures.newStore();

        mvc.perform(get(EXCEPTIONS).param("storeId", kitchen.store.toString())
                .with(account(other, Permission.INVENTORY_VIEW))).andExpect(status().isForbidden());
        mvc.perform(idem(post(EXCEPTIONS + "/" + exception + "/reprocess"))
                .with(account(kitchen.store, Permission.INVENTORY_VIEW))).andExpect(status().isForbidden());
        mvc.perform(idem(post(EXCEPTIONS + "/" + exception + "/reprocess"))
                .with(account(other, Permission.INVENTORY_REBUILD))).andExpect(status().isForbidden());
        mvc.perform(idem(post(EXCEPTIONS + "/" + UUID.randomUUID() + "/reprocess"))
                .with(account(kitchen.store, Permission.INVENTORY_REBUILD))).andExpect(status().isNotFound());
    }

    @Test
    void theStatusCountsTheOpenExceptions() throws Exception {
        consumption.consume(kitchen.round(clock.instant(), OrderMode.DINE_IN,
                line(kitchen.catalogItem(null), "1", "KITCHEN")).batchId());

        mvc.perform(get(InventoryFixtures.BO + "/status").param("storeId", kitchen.store.toString())
                        .with(account(kitchen.store, Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.openExceptions").value(1));
    }

    /**
     * A void waits only for a line that may still be consumed: one held by an exception counts as a pending void on
     * the status; one consumed for nothing (packaging of another mode only) resolves at once.
     */
    @Test
    void onlyAVoidOfALineHeldByAnExceptionWaits() throws Exception {
        UUID burger = kitchen.catalogItem(null);
        kitchen.saleRecipe(burger, null, "1", Kitchen.packaging(kitchen.stockItem("un"), "1", "un", "TAKEAWAY"));
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(burger, "1", "KITCHEN"),
                line(kitchen.catalogItem(null), "1", "KITCHEN"));
        consumption.consume(round.batchId());

        voids.reverse(kitchen.voided(round.lines().get(0).lineId(), "1", false, clock.instant()).voidId());
        voids.reverse(kitchen.voided(round.lines().get(1).lineId(), "1", false, clock.instant()).voidId());

        mvc.perform(get(InventoryFixtures.BO + "/status").param("storeId", kitchen.store.toString())
                        .with(account(kitchen.store, Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingVoids").value(1));
    }

    private JsonNode list(boolean open) throws Exception {
        return json(mvc.perform(get(EXCEPTIONS).param("storeId", kitchen.store.toString())
                        .param("open", String.valueOf(open)).with(account(kitchen.store, Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions reprocess(UUID exception) throws Exception {
        return mvc.perform(idem(post(EXCEPTIONS + "/" + exception + "/reprocess"))
                .with(account(kitchen.store, Permission.INVENTORY_REBUILD)));
    }
}
