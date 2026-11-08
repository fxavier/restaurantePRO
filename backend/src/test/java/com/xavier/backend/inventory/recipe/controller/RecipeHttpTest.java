package com.xavier.backend.inventory.recipe.controller;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static com.xavier.backend.inventory.InventoryFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.ItemRef;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.organization.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/** D115: recipes per item (or variant) or prepared stock item, immutable versions, activation, RECIPE_MANAGE. */
@InventoryModuleTest
class RecipeHttpTest {

    static final String RECIPES = BO + "/recipes";

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    CatalogApi catalog;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    UUID item;
    UUID variant;
    UUID flour;

    @BeforeEach
    void aCatalogItemAndAStockItem() throws Exception {
        item = UUID.randomUUID();
        variant = UUID.randomUUID();
        when(catalog.items(any())).thenAnswer(call -> {
            java.util.Collection<UUID> ids = call.getArgument(0);
            return ids.contains(item)
                    ? List.of(new ItemRef(item, "PIZZA", "Pizza", UUID.randomUUID(), true, List.of(variant)))
                    : List.of();
        });
        flour = fixtures.newStockItem("g");
    }

    @Test
    void aDraftIsEditedThenActivatedAndThenImmutable() throws Exception {
        UUID recipe = id(create("""
                {"itemId": "%s", "name": "Pizza"}""".formatted(item)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        version(recipe, "0.1", "kg").andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1)).andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(idem(put(RECIPES + "/" + recipe + "/versions/1")).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content(versionBody("250", "g", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ingredients[0].quantity").value(250));

        Instant now = clock.instant();
        activate(recipe, 1, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.effectiveFrom").value(now.toString()));

        mvc.perform(idem(put(RECIPES + "/" + recipe + "/versions/1")).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content(versionBody("300", "g", 1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECIPE_VERSION_IMMUTABLE"));
        activate(recipe, 1, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECIPE_VERSION_IMMUTABLE"));
    }

    @Test
    void activatingTheNextVersionSupersedesTheCurrentOneAtItsStart() throws Exception {
        UUID recipe = activeRecipe();
        version(recipe, "300", "g").andExpect(status().isCreated()).andExpect(jsonPath("$.versionNo").value(2));
        Instant next = clock.instant().plus(Duration.ofHours(2));

        activate(recipe, 2, next).andExpect(status().isOk());

        JsonNode versions = json(mvc.perform(get(RECIPES + "/" + recipe).with(manager()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("versions");
        assertThat(versions.get(0).get("status").asString()).isEqualTo("SUPERSEDED");
        assertThat(versions.get(0).get("effectiveTo").asString()).isEqualTo(next.toString());
        assertThat(versions.get(1).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(versions.get(1).get("effectiveFrom").asString()).isEqualTo(next.toString());
    }

    @Test
    void aVersionNeverTakesEffectInThePastOrBeforeTheCurrentOne() throws Exception {
        UUID recipe = activeRecipe();
        version(recipe, "300", "g").andExpect(status().isCreated());

        activate(recipe, 2, clock.instant().minusSeconds(1)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RECIPE_EFFECTIVE_FROM_INVALID"));
        activate(recipe, 2, clock.instant()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RECIPE_EFFECTIVE_FROM_INVALID"));
    }

    @Test
    void anUnknownItemOrAForeignVariantIsRefused() throws Exception {
        create("""
                {"itemId": "%s", "name": "Ghost"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));
        create("""
                {"itemId": "%s", "variantId": "%s", "name": "Ghost"}""".formatted(item, UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));
    }

    @Test
    void theVariantsRecipeIsItsOwnAndEachTargetHasOneRecipe() throws Exception {
        create("""
                {"itemId": "%s", "name": "Pizza"}""".formatted(item)).andExpect(status().isCreated());
        create("""
                {"itemId": "%s", "variantId": "%s", "name": "Large pizza"}""".formatted(item, variant))
                .andExpect(status().isCreated());

        create("""
                {"itemId": "%s", "name": "Pizza again"}""".formatted(item))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECIPE_EXISTS"));
    }

    @Test
    void anIngredientInAUnitOfAnotherDimensionIsRefused() throws Exception {
        UUID recipe = id(create("""
                {"itemId": "%s", "name": "Pizza"}""".formatted(item)).andReturn().getResponse().getContentAsString());

        version(recipe, "0.2", "l").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNIT_DIMENSION_MISMATCH"));
    }

    @Test
    void aPreparationRecipeThatReachesItselfIsACycle() throws Exception {
        UUID sauce = preparedItem();
        UUID dough = preparedItem();
        UUID sauceRecipe = preparationRecipe(sauce);
        UUID doughRecipe = preparationRecipe(dough);
        mvc.perform(idem(post(RECIPES + "/" + sauceRecipe + "/versions")).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content(ingredientsBody(dough)))
                .andExpect(status().isCreated());
        activate(sauceRecipe, 1, null).andExpect(status().isOk());

        mvc.perform(idem(post(RECIPES + "/" + doughRecipe + "/versions")).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content(ingredientsBody(sauce)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("RECIPE_CYCLE"));
        mvc.perform(idem(post(RECIPES + "/" + doughRecipe + "/versions")).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content(ingredientsBody(dough)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("RECIPE_CYCLE"));
    }

    @Test
    void aPreparationRecipeMakesAPreparedItem() throws Exception {
        create("""
                {"outputStockItemId": "%s", "name": "Flour?"}""".formatted(flour))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RECIPE_OUTPUT_NOT_PREPARED"));
        create("""
                {"itemId": "%s", "outputStockItemId": "%s", "name": "Both"}""".formatted(item, preparedItem()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RECIPE_TARGET_INVALID"));
    }

    @Test
    void aRecipeIsDeletedOnlyWhileNoVersionWasActivated() throws Exception {
        UUID draftOnly = id(create("""
                {"itemId": "%s", "name": "Pizza"}""".formatted(item)).andReturn().getResponse().getContentAsString());
        version(draftOnly, "250", "g").andExpect(status().isCreated());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(RECIPES + "/" + draftOnly)
                        .with(manager()).with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(RECIPES + "/" + draftOnly).with(manager())).andExpect(status().isNotFound());

        UUID used = activeRecipe();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(RECIPES + "/" + used)
                        .with(manager()).with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECIPE_VERSION_IMMUTABLE"));
    }

    @Test
    void recipesNeedRecipeManage() throws Exception {
        mvc.perform(idem(post(RECIPES)).with(account(null, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"itemId": "%s", "name": "Pizza"}""".formatted(item)))
                .andExpect(status().isForbidden());
        mvc.perform(get(RECIPES).with(account(null, Permission.INVENTORY_VIEW))).andExpect(status().isForbidden());
    }

    @Test
    void recipeWritesNeedAnInstallationWideGrantButReadsAcceptAStoreGrant() throws Exception {
        RequestPostProcessor storeManager = account(UUID.randomUUID(), Permission.RECIPE_MANAGE);
        UUID recipe = id(create("""
                {"itemId": "%s", "name": "Pizza"}""".formatted(item)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        version(recipe, "250", "g").andExpect(status().isCreated());

        mvc.perform(get(RECIPES + "/" + recipe).with(storeManager)).andExpect(status().isOk());
        mvc.perform(idem(post(RECIPES)).with(storeManager).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\": \"%s\", \"name\": \"Other\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(idem(put(RECIPES + "/" + recipe)).with(storeManager).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Renamed\", \"version\": 0}")).andExpect(status().isForbidden());
        mvc.perform(idem(post(RECIPES + "/" + recipe + "/versions")).with(storeManager)
                .contentType(MediaType.APPLICATION_JSON).content(versionBody("300", "g", null)))
                .andExpect(status().isForbidden());
        mvc.perform(idem(put(RECIPES + "/" + recipe + "/versions/1")).with(storeManager)
                .contentType(MediaType.APPLICATION_JSON).content(versionBody("300", "g", null)))
                .andExpect(status().isForbidden());
        mvc.perform(idem(post(RECIPES + "/" + recipe + "/versions/1/activate")).with(storeManager)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(RECIPES + "/" + recipe)
                        .with(storeManager).with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get(RECIPES + "/" + recipe).with(manager())).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pizza"));
    }

    @Test
    void theDatabaseRefusesToChangeAnActiveVersionsIngredients() throws Exception {
        UUID recipe = activeRecipe();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE inventory.recipe_ingredient SET quantity = 1 WHERE recipe_version_id IN
                        (SELECT id FROM inventory.recipe_version WHERE recipe_id = ?)""").param(recipe).update())
                .hasMessageContaining("immutable");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE inventory.recipe_version SET yield_quantity = 3 WHERE recipe_id = ?""")
                        .param(recipe).update())
                .hasMessageContaining("immutable");
    }

    private UUID activeRecipe() throws Exception {
        UUID recipe = id(create("""
                {"itemId": "%s", "name": "Pizza"}""".formatted(item)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        version(recipe, "250", "g").andExpect(status().isCreated());
        activate(recipe, 1, null).andExpect(status().isOk());
        return recipe;
    }

    private UUID preparedItem() throws Exception {
        return id(mvc.perform(idem(post(BO + "/stock-items")).with(account(null, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "%s", "name": "Prepared", "baseUnit": "g", "kind": "PREPARED"}"""
                                .formatted(InventoryFixtures.code("P"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID preparationRecipe(UUID output) throws Exception {
        return id(create("""
                {"outputStockItemId": "%s", "name": "Prep"}""".formatted(output)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private String ingredientsBody(UUID ingredient) {
        return """
                {"yieldQuantity": 1, "yieldUnit": "kg",
                 "ingredients": [{"stockItemId": "%s", "quantity": 500, "unit": "g", "kind": "INGREDIENT"}]}"""
                .formatted(ingredient);
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(idem(post(RECIPES)).with(manager()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions version(UUID recipe, String quantity, String unit) throws Exception {
        return mvc.perform(idem(post(RECIPES + "/" + recipe + "/versions")).with(manager())
                .contentType(MediaType.APPLICATION_JSON).content(versionBody(quantity, unit, null)));
    }

    private String versionBody(String quantity, String unit, Integer version) {
        return """
                {"yieldQuantity": 1, "yieldUnit": "un", "notes": "one pizza", %s
                 "ingredients": [{"stockItemId": "%s", "quantity": %s, "unit": "%s", "kind": "INGREDIENT"}]}"""
                .formatted(version == null ? "" : "\"version\": " + version + ",", flour, quantity, unit);
    }

    private ResultActions activate(UUID recipe, int versionNo, Instant from) throws Exception {
        return mvc.perform(idem(post(RECIPES + "/" + recipe + "/versions/" + versionNo + "/activate")).with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content(from == null ? "{}" : "{\"effectiveFrom\": \"%s\"}".formatted(from)));
    }

    private static RequestPostProcessor manager() {
        return account(null, Permission.RECIPE_MANAGE);
    }
}
