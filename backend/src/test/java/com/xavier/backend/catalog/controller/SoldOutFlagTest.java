package com.xavier.backend.catalog.controller;

import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.Actors.pos;
import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.catalog.CatalogModuleTest;
import com.xavier.backend.catalog.ItemKey;
import com.xavier.backend.catalog.Race;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * D109: the sold-out flag is operational, per store × item or variant, outside the release. A POS session sets it for
 * its own store only; it is audited, and the store's POS clients get a {@code CATALOG} notice once it commits.
 */
@CatalogModuleTest
@Import(CatalogNoticeHttpTest.NoticeStream.class) // the same context as CatalogNoticeHttpTest
class SoldOutFlagTest {

    static final String AVAILABILITY = "/api/v1/pos/catalog/availability";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CatalogApi catalogApi;

    @Test
    void aPosSessionMarksAnItemSoldOutInItsOwnStoreOnly() throws Exception {
        UUID store = fixtures().store();
        UUID other = fixtures().store();
        JsonNode item = fixtures().item();

        setSoldOut(store, item, null, true, "no more fish")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value(id(item).toString()))
                .andExpect(jsonPath("$.soldOut").value(true))
                .andExpect(jsonPath("$.reason").value("no more fish"))
                .andExpect(jsonPath("$.soldOutAt").isNotEmpty());

        assertThat(catalogApi.soldOut(store)).containsExactly(new ItemKey(id(item), null));
        assertThat(catalogApi.soldOut(other)).isEmpty();
        mvc.perform(get(AVAILABILITY).with(pos(store, Permission.CATALOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].itemId").value(id(item).toString()))
                .andExpect(jsonPath("$[0].variantId").doesNotExist());
        mvc.perform(get(AVAILABILITY).with(pos(other, Permission.CATALOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aVariantIsFlaggedOnItsOwnAndAvailableAgainRemovesTheFlag() throws Exception {
        UUID store = fixtures().store();
        JsonNode item = fixtures().item();
        JsonNode large = fixtures().variant(id(item));

        setSoldOut(store, item, large, true, null).andExpect(status().isOk());
        assertThat(catalogApi.soldOut(store)).containsExactly(new ItemKey(id(item), id(large)));

        setSoldOut(store, item, large, false, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soldOut").value(false))
                .andExpect(jsonPath("$.soldOutAt").doesNotExist());
        assertThat(catalogApi.soldOut(store)).isEmpty();
        assertThat(audits(store, id(item))).containsExactly("ITEM_SOLD_OUT", "ITEM_BACK_IN_STOCK");
    }

    @Test
    void settingTheSameStateTwiceChangesNothingAndAuditsOnce() throws Exception {
        UUID store = fixtures().store();
        JsonNode item = fixtures().item();

        setSoldOut(store, item, null, true, "first").andExpect(status().isOk());
        setSoldOut(store, item, null, true, "second")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("first"));
        setSoldOut(store, item, null, false, null).andExpect(status().isOk());
        setSoldOut(store, item, null, false, null).andExpect(status().isOk());

        assertThat(audits(store, id(item))).containsExactly("ITEM_SOLD_OUT", "ITEM_BACK_IN_STOCK");
    }

    @Test
    void concurrentSoldOutRequestsLeaveOneFlagAndOneAudit() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID store = fixtures().store();
            JsonNode item = fixtures().item();

            List<Integer> statuses = Race.twice(() -> setSoldOut(store, item, null, true, null).andReturn()
                    .getResponse().getStatus());

            assertThat(statuses).as("round %d", round).containsExactly(200, 200);
            assertThat(catalogApi.soldOut(store)).containsExactly(new ItemKey(id(item), null));
            assertThat(audits(store, id(item))).containsExactly("ITEM_SOLD_OUT");
        }
    }

    @Test
    void theStoreComesFromTheSessionAndNeedsCatalogAvailability() throws Exception {
        UUID store = fixtures().store();
        JsonNode item = fixtures().item();
        String body = "{\"soldOut\": true}";

        mvc.perform(put(AVAILABILITY + "/" + id(item)).with(pos(store, Permission.CATALOG_VIEW)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put(AVAILABILITY + "/" + id(item)).with(inStore(store, Permission.CATALOG_AVAILABILITY))
                        .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()); // a BackOffice session is not a POS session
        mvc.perform(put(AVAILABILITY + "/" + id(item)).with(pos(store, Permission.CATALOG_AVAILABILITY)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()); // no Idempotency-Key
        mvc.perform(put(AVAILABILITY + "/" + UUID.randomUUID()).with(pos(store, Permission.CATALOG_AVAILABILITY))
                        .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        JsonNode foreignVariant = fixtures().variant(id(fixtures().item()));
        setSoldOut(store, item, foreignVariant, true, null).andExpect(status().isNotFound());
        assertThat(catalogApi.soldOut(store)).isEmpty();
    }

    @Test
    void aChangeNotifiesTheStoresPosAfterCommit() throws Exception {
        UUID store = fixtures().store();
        UUID other = fixtures().store();
        JsonNode item = fixtures().item();
        MockHttpServletResponse stream = subscribe(store);
        MockHttpServletResponse otherStream = subscribe(other);

        setSoldOut(store, item, null, true, null).andExpect(status().isOk());

        eventually(() -> assertThat(content(stream)).contains(CatalogNoticeHttpTest.CATALOG_NOTICE));
        assertThat(content(otherStream)).doesNotContain("CATALOG");
    }

    private ResultActions setSoldOut(UUID store, JsonNode item, JsonNode variant, boolean soldOut, String reason)
            throws Exception {
        return mvc.perform(put(AVAILABILITY + "/" + id(item)).with(pos(store, Permission.CATALOG_AVAILABILITY))
                .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"variantId\": %s, \"soldOut\": %s, \"reason\": %s}"
                        .formatted(variant == null ? "null" : "\"" + id(variant) + "\"", soldOut,
                                reason == null ? "null" : "\"" + reason + "\"")));
    }

    private List<String> audits(UUID store, UUID item) {
        return jdbc.sql("""
                        SELECT action FROM audit.audit_event WHERE store_id = :store AND entity_id = :item
                        ORDER BY occurred_at, id""")
                .param("store", store).param("item", item.toString()).query(String.class).list();
    }

    private MockHttpServletResponse subscribe(UUID store) throws Exception {
        return mvc.perform(get(CatalogNoticeHttpTest.NoticeStream.PATH + store)
                        .with(pos(store, Permission.CATALOG_VIEW)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn().getResponse();
    }

    private static String content(MockHttpServletResponse response) {
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** Notices are sent off the committing thread; poll until a deadline (as CatalogNoticeHttpTest). */
    private static void eventually(Runnable assertion) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(20);
            }
        }
    }

    private CatalogFixtures fixtures() {
        return new CatalogFixtures(mvc);
    }
}
