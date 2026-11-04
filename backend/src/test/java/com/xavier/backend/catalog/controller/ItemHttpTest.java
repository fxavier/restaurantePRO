package com.xavier.backend.catalog.controller;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.CatalogFixtures.CATALOG;
import static com.xavier.backend.catalog.CatalogFixtures.code;
import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.catalog.CatalogModuleTest;
import com.xavier.backend.catalog.Race;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@CatalogModuleTest
class ItemHttpTest {

    static final String ITEMS = CATALOG + "/items";
    static final String BARCODES = CATALOG + "/barcodes";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aNewItemIsActiveWithItsReferencesAndIsAudited() throws Exception {
        UUID family = fixtures().family();
        UUID unit = fixtures().unit();
        UUID tax = fixtures().taxCategory("16");

        JsonNode item = fixtures().item(family, unit, tax);

        assertThat(item.get("availability").asString()).isEqualTo("ACTIVE");
        assertThat(item.get("familyId").asString()).isEqualTo(family.toString());
        assertThat(item.get("unitId").asString()).isEqualTo(unit.toString());
        assertThat(item.get("taxCategoryId").asString()).isEqualTo(tax.toString());
        assertThat(item.get("productionDestination").asString()).isEqualTo("BAR");
        assertThat(item.get("soldByWeight").asBoolean()).isFalse();
        mvc.perform(get(ITEMS + "/" + id(item)).with(admin(Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(item.get("code").asString()));
        assertThat(audits("ITEM_CREATED", id(item))).isEqualTo(1);
    }

    @Test
    void anItemReferringToAnUnknownFamilyUnitOrTaxCategoryBreaksARule() throws Exception {
        UUID family = fixtures().family();
        UUID unit = fixtures().unit();
        UUID tax = fixtures().taxCategory("16");

        for (UUID[] refs : new UUID[][] {{UUID.randomUUID(), unit, tax}, {family, UUID.randomUUID(), tax},
                {family, unit, UUID.randomUUID()}}) {
            mvc.perform(post(ITEMS).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(item(code("I"), refs[0], refs[1], refs[2])))
                    .andExpect(status().isUnprocessableContent());
        }
    }

    @Test
    void aProductionDestinationIsAnUpperCaseCode() throws Exception {
        String body = item(code("I"), fixtures().family(), fixtures().unit(), fixtures().taxCategory("16"))
                .replace("\"KITCHEN\"", "\"hot kitchen\"");

        mvc.perform(post(ITEMS).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productionDestination"));
    }

    @Test
    void itemCodesAreUniqueAlsoWhenTwoRequestsRace() throws Exception {
        UUID family = fixtures().family();
        UUID unit = fixtures().unit();
        UUID tax = fixtures().taxCategory("16");

        for (int round = 0; round < 5; round++) {
            String code = code("I");
            assertThat(Race.twice(() -> mvc.perform(post(ITEMS).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(item(code, family, unit, tax)))
                    .andReturn().getResponse().getStatus())).containsExactlyInAnyOrder(201, 409);
        }
    }

    @Test
    void anItemIsDeactivatedWithItsCurrentVersionAndAStaleEditIsAConflict() throws Exception {
        JsonNode item = fixtures().item();
        String update = """
                {"code": "%s", "name": "Coke", "familyId": "%s", "unitId": "%s", "taxCategoryId": "%s",
                 "productionDestination": null, "availability": "INACTIVE", "soldByWeight": true, "version": 0}"""
                .formatted(item.get("code").asString(), item.get("familyId").asString(), item.get("unitId").asString(),
                        item.get("taxCategoryId").asString());

        mvc.perform(put(ITEMS + "/" + id(item)).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("INACTIVE"))
                .andExpect(jsonPath("$.soldByWeight").value(true))
                .andExpect(jsonPath("$.productionDestination").isEmpty())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(ITEMS + "/" + id(item)).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isConflict());
        assertThat(audits("ITEM_UPDATED", id(item))).isEqualTo(1);
    }

    @Test
    void variantCodesAreUniquePerItem() throws Exception {
        UUID first = id(fixtures().item());
        UUID second = id(fixtures().item());
        String variant = "{\"code\": \"LARGE\", \"name\": \"Large\"}";

        UUID large = id(fixtures().created(post(ITEMS + "/" + first + "/variants").with(admin(Permission.CATALOG_MANAGE)),
                variant));
        mvc.perform(post(ITEMS + "/" + first + "/variants").with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(variant))
                .andExpect(status().isConflict());
        fixtures().created(post(ITEMS + "/" + second + "/variants").with(admin(Permission.CATALOG_MANAGE)), variant);

        mvc.perform(put(ITEMS + "/" + first + "/variants/" + large).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"L\", \"name\": \"Grande\", \"version\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Grande"));
        mvc.perform(get(ITEMS + "/" + first + "/variants").with(admin(Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("L"));
        mvc.perform(get(ITEMS + "/" + second + "/variants/" + large).with(admin(Permission.CATALOG_MANAGE)))
                .andExpect(status().isNotFound());
        assertThat(audits("ITEM_VARIANT_CREATED", large)).isEqualTo(1);
        assertThat(audits("ITEM_VARIANT_UPDATED", large)).isEqualTo(1);
    }

    @Test
    void aBarcodePointsAtAnItemOrOneOfItsVariants() throws Exception {
        UUID item = id(fixtures().item());
        UUID variant = id(fixtures().variant(item));
        UUID otherItem = id(fixtures().item());

        JsonNode plain = fixtures().created(post(BARCODES).with(admin(Permission.CATALOG_MANAGE)), barcode(code("56"), item, null));
        JsonNode ofVariant = fixtures().created(post(BARCODES).with(admin(Permission.CATALOG_MANAGE)),
                barcode(code("56"), item, variant));

        assertThat(plain.get("variantId").isNull()).isTrue();
        assertThat(ofVariant.get("variantId").asString()).isEqualTo(variant.toString());
        mvc.perform(post(BARCODES).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(barcode(code("56"), otherItem, variant)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get(BARCODES).param("itemId", item.toString()).with(admin(Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(put(BARCODES + "/" + id(plain)).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(barcode(code("57"), item, variant).replace("}", ", \"version\": 0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variantId").value(variant.toString()));
        mvc.perform(get(BARCODES + "/" + id(plain)).with(admin(Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(audits("BARCODE_CREATED", id(plain))).isEqualTo(1);
        assertThat(audits("BARCODE_UPDATED", id(plain))).isEqualTo(1);
    }

    @Test
    void barcodesAreUniqueInTheInstallationAlsoWhenTwoRequestsRace() throws Exception {
        UUID first = id(fixtures().item());
        UUID second = id(fixtures().item());

        for (int round = 0; round < 5; round++) {
            String code = code("56");
            List<Callable<Integer>> requests = List.of(first, second).stream().<Callable<Integer>>map(item -> () ->
                    mvc.perform(post(BARCODES).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON).content(barcode(code, item, null)))
                            .andReturn().getResponse().getStatus()).toList();

            assertThat(Race.run(requests)).containsExactlyInAnyOrder(201, 409);
        }
    }

    @Test
    void itemsAreCompanyWideSoAStoreScopedManagerIsRefused() throws Exception {
        UUID store = fixtures().store();

        mvc.perform(get(ITEMS).with(inStore(store, Permission.CATALOG_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(post(ITEMS).with(inStore(store, Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(item(code("I"), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(get(ITEMS).with(admin(Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk());
    }

    static String item(String code, UUID family, UUID unit, UUID tax) {
        return """
                {"code": "%s", "name": "Steak", "familyId": "%s", "unitId": "%s", "taxCategoryId": "%s",
                 "productionDestination": "KITCHEN", "soldByWeight": false}""".formatted(code, family, unit, tax);
    }

    static String barcode(String code, UUID item, UUID variant) {
        return """
                {"code": "%s", "itemId": "%s", "variantId": %s}""".formatted(code, item,
                variant == null ? "null" : "\"" + variant + "\"");
    }

    private long audits(String action, UUID id) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = :action AND entity_id = :id")
                .param("action", action).param("id", id.toString()).query(Long.class).single();
    }

    private CatalogFixtures fixtures() {
        return new CatalogFixtures(mvc);
    }
}
