package com.xavier.backend.catalog;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.CatalogFixtures.CATALOG;
import static com.xavier.backend.catalog.CatalogFixtures.id;
import static com.xavier.backend.catalog.CatalogFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.Publications.SellableStore;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.catalog.model.dto.ItemKind;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * D108: a MENU item's component groups are written on the working copy, checked again when a release is validated
 * (options are ACTIVE items of the release and not menus; only a MENU has groups), and frozen into the v2 snapshot.
 * Items are company-wide: a menu left invalid would block every later publication, so every MENU item a test makes
 * is deactivated after it, also when it fails.
 */
@CatalogModuleTest
class MenuReleaseValidationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CatalogApi catalogApi;

    /** MENU items this test made: company-wide, deactivated after each test whatever its outcome. */
    final List<UUID> menus = new ArrayList<>();

    @AfterEach
    void deactivateMenus() {
        if (!menus.isEmpty()) {
            jdbc.sql("UPDATE catalog.item SET availability = 'INACTIVE', version = version + 1 WHERE id IN (:ids)")
                    .param("ids", menus).update();
        }
    }

    @Test
    void aPublishedMenuCarriesItsKindGroupsAndOptionsInTheSnapshot() throws Exception {
        SellableStore sellable = publications().sellableStore();
        JsonNode menu = menu();
        JsonNode starter = fixtures().item("STANDARD", "KITCHEN");
        JsonNode drink = fixtures().item("STANDARD", "BAR");
        JsonNode large = fixtures().variant(id(drink));
        priceAll(sellable, menu, starter, drink);
        createGroup(menu, "DRINK", 1, 1, 1, option(drink, large, "1")).andExpect(status().isCreated());
        createGroup(menu, "STARTER", 0, 2, 0, option(starter, null, "1"), option(drink, null, "2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.options[1].quantity").value(2));

        publications().publishNew(sellable.store());

        CatalogSnapshot snapshot = catalogApi.currentSnapshot(sellable.store());
        assertThat(snapshot.schemaVersion()).isEqualTo(2);
        CatalogSnapshot.Item frozen = item(snapshot, id(menu));
        assertThat(frozen.kind()).isEqualTo(ItemKind.MENU);
        assertThat(frozen.menuGroups()).extracting(CatalogSnapshot.MenuGroup::code)
                .containsExactly("STARTER", "DRINK"); // by position
        assertThat(frozen.menuGroups().get(0).minChoices()).isZero();
        assertThat(frozen.menuGroups().get(0).maxChoices()).isEqualTo(2);
        assertThat(frozen.menuGroups().get(0).options()).containsExactly(
                new CatalogSnapshot.MenuOption(id(starter), null, new BigDecimal("1.000")),
                new CatalogSnapshot.MenuOption(id(drink), null, new BigDecimal("2.000")));
        assertThat(frozen.menuGroups().get(1).options()).containsExactly(
                new CatalogSnapshot.MenuOption(id(drink), id(large), new BigDecimal("1.000")));
        assertThat(item(snapshot, id(starter)).kind()).isEqualTo(ItemKind.STANDARD);
        assertThat(item(snapshot, id(starter)).menuGroups()).isEmpty();
    }

    @Test
    void aMenuGroupIsRefusedOnANonMenuItemOrWithAnOptionAMenuCannotHave() throws Exception {
        JsonNode menu = menu();
        JsonNode otherMenu = menu();
        JsonNode standard = fixtures().item("STANDARD", "KITCHEN");
        JsonNode otherVariant = fixtures().variant(id(fixtures().item("STANDARD", "BAR")));
        expectInvalid(createGroup(standard, "G", 1, 1, 0, option(menu, null, "1")));
        expectInvalid(createGroup(menu, "G", 1, 1, 0, option(otherMenu, null, "1")));
        expectInvalid(createGroup(menu, "G", 1, 1, 0, option(menu, null, "1")));
        expectInvalid(createGroup(menu, "G", 2, 1, 0, option(standard, null, "1")));
        expectInvalid(createGroup(menu, "G", 1, 1, 0, option(standard, null, "1"), option(standard, null, "2")));
        expectInvalid(createGroup(menu, "G", 1, 1, 0, option(standard, otherVariant, "1")));
        expectInvalid(createGroup(menu, "G", 1, 1, 0, option(standard, null, "0")));
        expectInvalid(createGroup(menu, "G", 1, 1, 0,
                "{\"itemId\": \"%s\", \"variantId\": null, \"quantity\": 1}".formatted(UUID.randomUUID())));
        createGroup(menu, "G", 1, 1, 0).andExpect(status().isUnprocessableContent()); // no options
        // an option is picked at most once per group: 1 ≤ maxChoices ≤ options, 0 ≤ minChoices ≤ maxChoices
        expectInvalid(createGroup(menu, "G", 3, 3, 0, option(standard, null, "1")));
        expectInvalid(createGroup(menu, "G", 0, 0, 0, option(standard, null, "1")));
        expectInvalid(createGroup(menu, "G", 0, 2, 0, option(standard, null, "1")));

        assertThat(groupsOf(menu)).isZero();
    }

    @Test
    void validationRefusesAMenuWhoseOptionIsNoLongerSellableOrBecameAMenu() throws Exception {
        SellableStore sellable = publications().sellableStore();
        JsonNode menu = menu();
        JsonNode retired = fixtures().item("STANDARD", "KITCHEN");
        JsonNode nested = fixtures().item("STANDARD", "BAR");
        priceAll(sellable, menu, retired, nested);
        createGroup(menu, "G", 1, 1, 0, option(retired, null, "1"), option(nested, null, "1"))
                .andExpect(status().isCreated());
        fixtures().deactivate(retired);
        fixtures().updateItem(nested, "ACTIVE", "MENU");
        menus.add(id(nested));

        validate(sellable.store())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.itemId == '%s')].code".formatted(id(menu)))
                        .value(org.hamcrest.Matchers.containsInAnyOrder("MENU_OPTION_NOT_IN_RELEASE",
                                "MENU_OPTION_IS_MENU")));
    }

    @Test
    void validationRefusesAGroupWhoseBoundsItsOptionsCannotMeet() throws Exception {
        SellableStore sellable = publications().sellableStore();
        JsonNode menu = menu();
        JsonNode drink = fixtures().item("STANDARD", "BAR");
        priceAll(sellable, menu, drink);
        JsonNode group = json(createGroup(menu, "G", 1, 1, 0, option(drink, null, "1"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        jdbc.sql("UPDATE catalog.menu_group SET min_choices = 2, max_choices = 2 WHERE id = :id") // a legacy row
                .param("id", id(group)).update();

        validate(sellable.store())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.itemId == '%s')].code".formatted(id(menu)))
                        .value("MENU_GROUP_BOUNDS_INVALID"));
    }

    @Test
    void validationRefusesMenuGroupsOnAnItemThatIsNoLongerAMenu() throws Exception {
        SellableStore sellable = publications().sellableStore();
        JsonNode menu = menu();
        JsonNode drink = fixtures().item("STANDARD", "BAR");
        priceAll(sellable, menu, drink);
        createGroup(menu, "G", 1, 1, 0, option(drink, null, "1")).andExpect(status().isCreated());
        fixtures().updateItem(menu, "ACTIVE", "FEE");

        validate(sellable.store())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.itemId == '%s')].code".formatted(id(menu)))
                        .value("MENU_GROUPS_ON_NON_MENU"));
    }

    @Test
    void aGroupIsEditedAtItsVersionAndDeleted() throws Exception {
        JsonNode menu = menu();
        JsonNode starter = fixtures().item("STANDARD", "KITCHEN");
        JsonNode drink = fixtures().item("STANDARD", "BAR");
        JsonNode group = json(createGroup(menu, "G", 1, 1, 0, option(starter, null, "1"), option(drink, null, "1"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String path = groupsPath(menu) + "/" + id(group);

        mvc.perform(put(path).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(groupBody("G", 1, 2, 0, 0,
                                option(drink, null, "1"), option(starter, null, "1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxChoices").value(2))
                .andExpect(jsonPath("$.options[0].itemId").value(id(drink).toString()))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(path).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(groupBody("G", 1, 1, 0, 0,
                                option(drink, null, "1"))))
                .andExpect(status().isConflict());
        mvc.perform(get(groupsPath(menu)).with(admin(Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].options.length()").value(2));

        mvc.perform(delete(path).param("version", "1").with(admin(Permission.CATALOG_MANAGE)).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(groupsOf(menu)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE entity_id = :id")
                .param("id", id(group).toString()).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void creatingAGroupNeedsAnIdempotencyKeyAndReplaysOnce() throws Exception {
        JsonNode menu = menu();
        JsonNode drink = fixtures().item("STANDARD", "BAR");
        String body = groupBody("G", 1, 1, 0, null, option(drink, null, "1"));
        mvc.perform(post(groupsPath(menu)).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        String key = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(groupsPath(menu)).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }
        assertThat(groupsOf(menu)).isEqualTo(1);
        mvc.perform(post(groupsPath(menu)).with(inStore(UUID.randomUUID(), Permission.CATALOG_MANAGE))
                        .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()); // items are company-wide
    }

    private JsonNode menu() throws Exception {
        JsonNode menu = fixtures().item("MENU", null);
        menus.add(id(menu));
        return menu;
    }

    private ResultActions createGroup(JsonNode menu, String code, int min, int max, int position, String... options)
            throws Exception {
        return mvc.perform(post(groupsPath(menu)).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(groupBody(code, min, max, position, null, options)));
    }

    private static String groupBody(String code, int min, int max, int position, Integer version, String... options) {
        return """
                {"code": "%s", "name": "Group %s", "minChoices": %d, "maxChoices": %d, "position": %d,
                 "options": [%s]%s}""".formatted(code, code, min, max, position, String.join(",", options),
                version == null ? "" : ", \"version\": " + version);
    }

    private static String option(JsonNode item, JsonNode variant, String quantity) {
        return "{\"itemId\": \"%s\", \"variantId\": %s, \"quantity\": %s}".formatted(id(item),
                variant == null ? "null" : "\"" + id(variant) + "\"", quantity);
    }

    private static void expectInvalid(ResultActions result) throws Exception {
        result.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MENU_GROUP_INVALID"));
    }

    private ResultActions validate(UUID store) throws Exception {
        UUID release = id(publications().createDraft(store));
        return mvc.perform(post(Publications.RELEASES + "/" + release + "/validate")
                .with(inStore(store, Permission.CATALOG_MANAGE)).with(csrf()));
    }

    private void priceAll(SellableStore sellable, JsonNode... items) throws Exception {
        for (JsonNode item : items) {
            fixtures().price(sellable.store(), sellable.priceList(), id(item), null, "5.00");
        }
    }

    private long groupsOf(JsonNode menu) {
        return jdbc.sql("SELECT count(*) FROM catalog.menu_group WHERE item_id = :id").param("id", id(menu))
                .query(Long.class).single();
    }

    private static String groupsPath(JsonNode item) {
        return CATALOG + "/items/" + id(item) + "/menu-groups";
    }

    private static CatalogSnapshot.Item item(CatalogSnapshot snapshot, UUID id) {
        return snapshot.items().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private CatalogFixtures fixtures() {
        return new CatalogFixtures(mvc);
    }

    private Publications publications() {
        return new Publications(mvc, jdbc);
    }
}
