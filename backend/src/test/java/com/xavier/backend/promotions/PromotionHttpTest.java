package com.xavier.backend.promotions;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.promotions.PromotionsFixtures.PROMOTIONS;
import static com.xavier.backend.promotions.PromotionsFixtures.code;
import static com.xavier.backend.promotions.PromotionsFixtures.expect;
import static com.xavier.backend.promotions.PromotionsFixtures.id;
import static com.xavier.backend.promotions.PromotionsFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** The promotions working copy (P43): kind values, validity, scope, windows and stores, with 422 PROMOTION_INVALID. */
@PromotionsModuleTest
class PromotionHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PromotionsFixtures fixtures;

    @Test
    void aManagerCreatesAPromotionAndItIsAudited() throws Exception {
        UUID store = fixtures.storeWithoutCatalog();
        String code = code("AMT");

        JsonNode created = json(expect(fixtures.create(store, """
                {"code": "%s", "name": "Coffee deal", "kind": "AMOUNT_OFF_UNIT", "amount": 1.50, "priority": 2,
                 "validFrom": "2026-11-01T00:00:00Z", "validTo": "2026-12-01T00:00:00Z",
                 "membersOnly": true, "modes": ["TAKEAWAY"]}""".formatted(code)), 201));

        assertThat(created.get("amount").decimalValue()).isEqualByComparingTo("1.50");
        assertThat(created.get("currency").asString()).isEqualTo("MZN");
        assertThat(created.get("active").asBoolean()).isTrue();
        assertThat(created.get("modes").get(0).asString()).isEqualTo("TAKEAWAY");
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'PROMOTION_CREATED'"
                + " AND entity_id = :id").param("id", id(created).toString()).query(Long.class).single()).isEqualTo(1);
        mvc.perform(get(PROMOTIONS + "/" + id(created)).with(inStore(store, Permission.PROMOTION_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(code));
        mvc.perform(get(PROMOTIONS + "/" + id(created)).with(inStore(store, Permission.CATALOG_VIEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    void kindValuesMustMatchTheKind() throws Exception {
        UUID store = fixtures.storeWithoutCatalog();

        for (String terms : new String[] {
                "\"kind\": \"PERCENT_OFF\", \"percent\": 0",
                "\"kind\": \"PERCENT_OFF\", \"percent\": 100.5",
                "\"kind\": \"PERCENT_OFF\", \"percent\": 10, \"amount\": 1.00",
                "\"kind\": \"AMOUNT_OFF_UNIT\", \"amount\": 0",
                "\"kind\": \"AMOUNT_OFF_UNIT\", \"amount\": 1.005",
                "\"kind\": \"FIXED_UNIT_PRICE\", \"amount\": -1",
                "\"kind\": \"FIXED_UNIT_PRICE\""}) {
            invalid(fixtures.create(store, "{\"code\": \"%s\", \"name\": \"X\", \"priority\": 0, %s}"
                    .formatted(code("K"), terms)));
        }
        expect(fixtures.create(store, """
                {"code": "%s", "name": "Free coffee", "kind": "FIXED_UNIT_PRICE", "amount": 0, "priority": 0}"""
                .formatted(code("K"))), 201);
    }

    @Test
    void aValidityMustStartBeforeItEnds() throws Exception {
        UUID store = fixtures.storeWithoutCatalog();

        invalid(fixtures.create(store, """
                {"code": "%s", "name": "X", "kind": "PERCENT_OFF", "percent": 10, "priority": 0,
                 "validFrom": "2026-12-01T00:00:00Z", "validTo": "2026-12-01T00:00:00Z"}""".formatted(code("V"))));
    }

    @Test
    void theScopeIsNeverEmptyAndAWindowNeedsDaysAndADuration() throws Exception {
        UUID store = fixtures.storeWithoutCatalog();
        JsonNode promotion = percentOff(store);
        String path = PROMOTIONS + "/" + id(promotion);

        invalid(fixtures.perform(put(path + "/scope").with(inStore(store, Permission.PROMOTION_MANAGE)),
                "{\"itemIds\": [], \"version\": 0}"));
        invalid(fixtures.perform(put(path + "/windows").with(inStore(store, Permission.PROMOTION_MANAGE)), """
                {"windows": [{"daysOfWeek": [8], "start": "18:00", "end": "20:00"}], "version": 0}"""));
        invalid(fixtures.perform(put(path + "/windows").with(inStore(store, Permission.PROMOTION_MANAGE)), """
                {"windows": [{"daysOfWeek": [1], "start": "18:00", "end": "18:00"}], "version": 0}"""));

        expect(fixtures.perform(put(path + "/windows").with(inStore(store, Permission.PROMOTION_MANAGE)), """
                {"windows": [{"daysOfWeek": [5, 6], "start": "22:00", "end": "02:00"}], "version": 0}"""), 200)
                .andExpect(jsonPath("$.windows[0].daysOfWeek[1]").value(6))
                .andExpect(jsonPath("$.windows[0].end").value("02:00:00"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void aCodeIsTakenOnceAndAStaleVersionIsAConflict() throws Exception {
        UUID store = fixtures.storeWithoutCatalog();
        JsonNode promotion = percentOff(store);

        fixtures.create(store, """
                {"code": "%s", "name": "Again", "kind": "PERCENT_OFF", "percent": 5, "priority": 0}"""
                .formatted(promotion.get("code").asString())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_CODE_TAKEN"));
        expect(update(store, promotion, 0), 200).andExpect(jsonPath("$.priority").value(9));
        update(store, promotion, 0).andExpect(status().isConflict());
    }

    @Test
    void assigningOrRemovingAStoreNeedsThePermissionThere() throws Exception {
        UUID store = fixtures.storeWithoutCatalog();
        UUID other = fixtures.storeWithoutCatalog();
        JsonNode promotion = percentOff(store);
        String stores = PROMOTIONS + "/" + id(promotion) + "/stores";

        fixtures.perform(put(stores).with(inStore(store, Permission.PROMOTION_MANAGE)), """
                {"storeIds": ["%s"], "version": 0}""".formatted(other)).andExpect(status().isForbidden());
        invalid(fixtures.perform(put(stores).with(admin(Permission.PROMOTION_MANAGE)), """
                {"storeIds": ["%s", "%s"], "version": 0}""".formatted(store, UUID.randomUUID())));
        expect(fixtures.perform(put(stores).with(inStore(store, Permission.PROMOTION_MANAGE)), """
                {"storeIds": ["%s"], "version": 0}""".formatted(store)), 200)
                .andExpect(jsonPath("$.storeIds[0]").value(store.toString()));
    }

    @Test
    void aWorkingCopyPromotionCanBeDeletedAtItsVersion() throws Exception {
        UUID store = fixtures.storeWithoutCatalog();
        JsonNode promotion = percentOff(store);
        String path = PROMOTIONS + "/" + id(promotion);

        mvc.perform(delete(path).param("version", "5").with(inStore(store, Permission.PROMOTION_MANAGE)).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(delete(path).param("version", "0").with(inStore(store, Permission.PROMOTION_MANAGE)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(path).with(inStore(store, Permission.PROMOTION_VIEW))).andExpect(status().isNotFound());
    }

    private JsonNode percentOff(UUID store) throws Exception {
        return json(expect(fixtures.create(store, """
                {"code": "%s", "name": "Ten", "kind": "PERCENT_OFF", "percent": 10, "priority": 0}"""
                .formatted(code("P"))), 201));
    }

    private ResultActions update(UUID store, JsonNode promotion, long version) throws Exception {
        return fixtures.perform(put(PROMOTIONS + "/" + id(promotion)).with(inStore(store, Permission.PROMOTION_MANAGE)),
                """
                {"name": "Ten", "kind": "PERCENT_OFF", "percent": 10, "priority": 9, "membersOnly": false,
                 "active": true, "version": %d}""".formatted(version));
    }

    private static void invalid(ResultActions result) throws Exception {
        expect(result, 422).andExpect(jsonPath("$.code").value("PROMOTION_INVALID"));
    }
}
