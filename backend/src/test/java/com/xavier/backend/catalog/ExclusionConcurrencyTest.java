package com.xavier.backend.catalog;

import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A PUT and a DELETE of the same exclusion started together behind a latch (Race): the PUT always answers 200 with
 * the exclusion, the DELETE 204 or 404, never a 500, and the table ends consistent with the answers.
 */
@CatalogModuleTest
class ExclusionConcurrencyTest {

    static final int ROUNDS = 25;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aPutRacingADeleteNeverFails() throws Exception {
        CatalogFixtures fixtures = new CatalogFixtures(mvc);
        UUID store = fixtures.store();
        UUID item = id(fixtures.item());
        Exclusions exclusions = new Exclusions(mvc);

        for (int round = 0; round < ROUNDS; round++) {
            exclusions.exclude(store, item, "same").andExpect(status().isOk());
            Callable<Integer> put = () -> exclusions.exclude(store, item, "same").andReturn().getResponse()
                    .getStatus();
            Callable<Integer> delete = () -> exclusions.include(store, item).andReturn().getResponse().getStatus();

            List<Integer> statuses = Race.run(List.of(put, delete));

            assertThat(statuses.get(0)).as("PUT in round " + round).isEqualTo(200);
            assertThat(statuses.get(1)).as("DELETE in round " + round).isIn(204, 404);
            if (statuses.get(1) == 404) {
                assertThat(jdbc.sql("""
                                SELECT count(*) FROM catalog.store_item_exclusion WHERE store_id = :s AND item_id = :i""")
                        .param("s", store).param("i", item).query(Long.class).single())
                        .as("a 404 DELETE means the row the PUT answered with is there").isEqualTo(1);
            }
        }
    }
}
