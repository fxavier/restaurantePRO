package com.xavier.backend.inventory.preparation;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.service.StockLedger;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D121: confirming a preparation posts each ingredient's PREPARATION_OUT at the average and one PREPARATION_IN of the
 * prepared item valued at their sum, in one transaction, once.
 */
@InventoryModuleTest
class PreparationCostTest {

    static final String PREPARATIONS = BO + "/preparations";

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    StockLedger ledger;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    UUID store;
    UUID warehouse;
    UUID tomato;
    UUID onion;
    UUID sauce;

    @BeforeEach
    void aSauceRecipe() throws Exception {
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "REFUSE_MANUAL");
        tomato = fixtures.newStockItem("g");
        onion = fixtures.newStockItem("g");
        sauce = preparedItem("l");
        receive(tomato, "10000", "0.002000");
        receive(onion, "2000", "0.001500");
        recipe(sauce, "5", "l", tomato, "4", "kg", onion, "500", "g");
    }

    @Test
    void confirmingPostsTheIngredientsAtTheAverageAndTheOutputAtTheirSum() throws Exception {
        UUID preparation = create(sauce, "2.5").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT")).andReturn().getResponse().getContentAsString()
                .transform(InventoryFixtures::id);

        confirm(preparation).andExpect(status().isOk())
                .andExpect(jsonPath("$.preparation.status").value("CONFIRMED"));

        List<Map<String, Object>> movements = movements(preparation);
        assertThat(movements).extracting(m -> m.get("type"))
                .containsExactly("PREPARATION_OUT", "PREPARATION_OUT", "PREPARATION_IN");
        assertThat(row(movements, tomato)).satisfies(m -> {
            assertThat((BigDecimal) m.get("quantity")).isEqualByComparingTo("-2000");
            assertThat((BigDecimal) m.get("value")).isEqualByComparingTo("-4.0000");
        });
        assertThat((BigDecimal) row(movements, onion).get("value")).isEqualByComparingTo("-0.3750");
        assertThat(row(movements, sauce)).satisfies(m -> {
            assertThat((BigDecimal) m.get("quantity")).isEqualByComparingTo("2.5");
            assertThat((BigDecimal) m.get("unit_cost")).isEqualByComparingTo("1.75");
            assertThat((BigDecimal) m.get("value")).isEqualByComparingTo("4.3750");
        });
        assertThat(balance(sauce, "value")).isEqualByComparingTo("4.3750");
        assertThat(balance(tomato, "quantity")).isEqualByComparingTo("8000");
    }

    @Test
    void theOutputKeepsTheWholeCostWhenTheUnitCostRounds() throws Exception {
        UUID dough = preparedItem("g");
        UUID salt = fixtures.newStockItem("g");
        receive(salt, "1000", "0.012345"); // 300 g cost 3.7035, which 3000 g at a 6-decimal unit cost miss
        recipe(dough, "1", "kg", salt, "100", "g", null, null, null);

        UUID preparation = id(create(dough, "3000").andReturn().getResponse().getContentAsString());
        confirm(preparation).andExpect(status().isOk());

        assertThat(movements(preparation).stream().map(m -> (BigDecimal) m.get("value"))
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
        assertThat(balance(dough, "value")).isEqualByComparingTo("3.7035");
        assertThat(balance(dough, "quantity")).isEqualByComparingTo("3000");
    }

    /** A version activated for later does not apply yet: the preparation uses the one in force now (D115). */
    @Test
    void aPreparationUsesTheRecipeVersionInForceNow() throws Exception {
        UUID recipe = jdbc.sql("SELECT id FROM inventory.recipe WHERE output_stock_item_id = ?").param(sauce)
                .query(UUID.class).single();
        mvc.perform(idem(post(BO + "/recipes/" + recipe + "/versions")).with(account(null, Permission.RECIPE_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"yieldQuantity": 5, "yieldUnit": "l", "ingredients": [
                                 {"stockItemId": "%s", "quantity": 1, "unit": "kg", "kind": "INGREDIENT"}]}"""
                                .formatted(onion)))
                .andExpect(status().isCreated());
        mvc.perform(idem(post(BO + "/recipes/" + recipe + "/versions/2/activate"))
                        .with(account(null, Permission.RECIPE_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\": \"%s\"}".formatted(Instant.now(java.time.Clock.systemUTC())
                                .plus(java.time.Duration.ofDays(3650)))))
                .andExpect(status().isOk());

        UUID preparation = id(create(sauce, "2.5").andReturn().getResponse().getContentAsString());
        confirm(preparation).andExpect(status().isOk());

        assertThat(movements(preparation)).extracting(m -> m.get("stock_item_id")).contains(tomato);
    }

    @Test
    void aWarehouseThatRefusesNegativeStockRefusesThePreparation() throws Exception {
        UUID preparation = id(create(sauce, "20").andReturn().getResponse().getContentAsString());

        confirm(preparation).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NEGATIVE_STOCK_REFUSED"));

        assertThat(movements(preparation)).isEmpty();
        confirm(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void aPreparationIsConfirmedOrCancelledOnce() throws Exception {
        UUID confirmed = id(create(sauce, "1").andReturn().getResponse().getContentAsString());
        confirm(confirmed).andExpect(status().isOk());
        UUID cancelled = id(create(sauce, "1").andReturn().getResponse().getContentAsString());

        mvc.perform(idem(post(PREPARATIONS + "/" + cancelled + "/cancel")).with(mover(store)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        confirm(confirmed).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PREPARATION_STATE"));
        confirm(cancelled).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PREPARATION_STATE"));
        mvc.perform(idem(post(PREPARATIONS + "/" + confirmed + "/cancel")).with(mover(store)))
                .andExpect(status().isConflict());
        assertThat(movements(confirmed)).hasSize(3);
        assertThat(movements(cancelled)).isEmpty();
    }

    @Test
    void aPreparationNeedsAnActiveRecipeAndInventoryMoveInItsStore() throws Exception {
        create(preparedItem("l"), "1").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_RECIPE"));
        UUID preparation = id(create(sauce, "1").andReturn().getResponse().getContentAsString());
        UUID other = fixtures.newStore();

        mvc.perform(idem(post(PREPARATIONS + "/" + preparation + "/confirm")).with(mover(other)))
                .andExpect(status().isForbidden());
        mvc.perform(idem(post(PREPARATIONS)).with(mover(other)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(sauce, "1"))).andExpect(status().isForbidden());
    }

    private ResultActions create(UUID output, String quantity) throws Exception {
        return mvc.perform(idem(post(PREPARATIONS)).with(mover(store)).contentType(MediaType.APPLICATION_JSON)
                .content(body(output, quantity)));
    }

    private String body(UUID output, String quantity) {
        return """
                {"warehouseId": "%s", "outputStockItemId": "%s", "outputQuantity": %s}"""
                .formatted(warehouse, output, quantity);
    }

    private ResultActions confirm(UUID preparation) throws Exception {
        return mvc.perform(idem(post(PREPARATIONS + "/" + preparation + "/confirm")).with(mover(store)));
    }

    private UUID preparedItem(String baseUnit) throws Exception {
        return id(mvc.perform(idem(post(BO + "/stock-items")).with(account(null, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "%s", "name": "Prepared", "baseUnit": "%s", "kind": "PREPARED"}"""
                                .formatted(InventoryFixtures.code("P"), baseUnit)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void recipe(UUID output, String yield, String yieldUnit, UUID first, String firstQuantity,
                        String firstUnit, UUID second, String secondQuantity, String secondUnit) throws Exception {
        UUID recipe = id(mvc.perform(idem(post(BO + "/recipes")).with(account(null, Permission.RECIPE_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outputStockItemId\": \"%s\", \"name\": \"Prep\"}".formatted(output)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String ingredients = "{\"stockItemId\": \"%s\", \"quantity\": %s, \"unit\": \"%s\", \"kind\": \"INGREDIENT\"}"
                .formatted(first, firstQuantity, firstUnit) + (second == null ? "" : ", {\"stockItemId\": \"%s\", "
                .formatted(second) + "\"quantity\": %s, \"unit\": \"%s\", \"kind\": \"INGREDIENT\"}"
                .formatted(secondQuantity, secondUnit));
        mvc.perform(idem(post(BO + "/recipes/" + recipe + "/versions")).with(account(null, Permission.RECIPE_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(
                                "{\"yieldQuantity\": %s, \"yieldUnit\": \"%s\", \"ingredients\": [%s]}"
                                        .formatted(yield, yieldUnit, ingredients)))
                .andExpect(status().isCreated());
        mvc.perform(idem(post(BO + "/recipes/" + recipe + "/versions/1/activate"))
                        .with(account(null, Permission.RECIPE_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    private void receive(UUID item, String quantity, String cost) {
        tx.executeWithoutResult(status -> ledger.post(List.of(PostMovement.of(warehouse, item, MovementType.RECEIPT,
                new BigDecimal(quantity), "GOODS_RECEIPT", UUID.randomUUID(), LocalDate.parse("2026-11-06"),
                Instant.parse("2026-11-06T06:00:00Z")).atCost(new BigDecimal(cost)))));
    }

    private List<Map<String, Object>> movements(UUID preparation) {
        return jdbc.sql("""
                        SELECT type, stock_item_id, quantity, unit_cost, value FROM inventory.stock_movement
                        WHERE source_type = 'PREPARATION' AND source_id = ? ORDER BY posting_seq""")
                .param(preparation).query().listOfRows();
    }

    private static Map<String, Object> row(List<Map<String, Object>> movements, UUID item) {
        return movements.stream().filter(m -> item.equals(m.get("stock_item_id"))).findFirst().orElseThrow();
    }

    private BigDecimal balance(UUID item, String column) {
        return jdbc.sql("SELECT " + column + " FROM inventory.stock_balance WHERE warehouse_id = ? AND stock_item_id = ?")
                .params(warehouse, item).query(BigDecimal.class).single();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor mover(UUID store) {
        return account(store, Permission.INVENTORY_MOVE);
    }
}
