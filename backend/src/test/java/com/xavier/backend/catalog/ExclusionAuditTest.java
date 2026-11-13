package com.xavier.backend.catalog;

import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Every real exclusion change is audited in the store with the actor (D20, D41); a repeated request is not. */
@CatalogModuleTest
class ExclusionAuditTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void excludingAndIncludingAreAuditedOnceEach() throws Exception {
        CatalogFixtures fixtures = new CatalogFixtures(mvc);
        UUID store = fixtures.store();
        UUID item = id(fixtures.item());
        Exclusions exclusions = new Exclusions(mvc);

        exclusions.exclude(store, item, "seasonal").andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("seasonal"));
        exclusions.exclude(store, item, "seasonal").andExpect(status().isOk());
        assertThat(audited(store, item)).containsExactly("ITEM_EXCLUDED_FROM_STORE {\"reason\": \"seasonal\"}");

        exclusions.exclude(store, item, "not in this menu").andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("not in this menu"));
        exclusions.include(store, item).andExpect(status().isNoContent());
        exclusions.include(store, item).andExpect(status().isNotFound());

        assertThat(audited(store, item)).containsExactlyInAnyOrder(
                "ITEM_EXCLUDED_FROM_STORE {\"reason\": \"seasonal\"}",
                "ITEM_EXCLUDED_FROM_STORE {\"reason\": \"not in this menu\"}", "ITEM_INCLUDED_IN_STORE {}");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event
                        WHERE entity_id = :item AND store_id = :store AND actor_id = :actor AND entity_type = 'Item'""")
                .param("item", item.toString()).param("store", store).param("actor", Actors.USER_ID)
                .query(Long.class).single()).isEqualTo(3);
    }

    private List<String> audited(UUID store, UUID item) {
        return jdbc.sql("""
                        SELECT action || ' ' || details::text FROM audit.audit_event
                        WHERE entity_id = :item AND store_id = :store ORDER BY occurred_at, id""")
                .param("item", item.toString()).param("store", store).query(String.class).list();
    }
}
