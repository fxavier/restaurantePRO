package com.xavier.backend.fiscal.controller;

import static com.xavier.backend.fiscal.FiscalActors.admin;
import static com.xavier.backend.fiscal.FiscalActors.inStore;
import static com.xavier.backend.fiscal.FiscalActors.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.fiscal.FiscalModuleTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@FiscalModuleTest
class SeriesHttpTest {

    static final String SERIES = "/api/v1/backoffice/fiscal/series";

    @Autowired
    MockMvc mvc;

    @Autowired
    FiscalFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aFiscalManagerCreatesASeriesThatStartsAtItsFirstNumberAndIsAudited() throws Exception {
        UUID store = fixtures.newStore();

        String id = create(store, "A", 101).andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.documentType").value("SALE"))
                .andExpect(jsonPath("$.code").value("A"))
                .andExpect(jsonPath("$.firstNumber").value(101))
                .andExpect(jsonPath("$.nextNumber").value(101))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event WHERE action = 'FISCAL_SERIES_CREATED'
                        AND entity_id = :id AND store_id = :store AND details ->> 'firstNumber' = '101'""")
                .param("id", FiscalFixtures.id(id).toString()).param("store", store).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void theFirstNumberDefaultsToOne() throws Exception {
        mvc.perform(post(SERIES).with(admin(Permission.FISCAL_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId": "%s", "code": "A"}""".formatted(fixtures.newStore())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nextNumber").value(1));
    }

    @Test
    void noRequestCanSetTheNextNumber() throws Exception {
        UUID store = fixtures.newStore();
        String id = FiscalFixtures.id(mvc.perform(post(SERIES).with(admin(Permission.FISCAL_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId": "%s", "code": "A", "firstNumber": 5, "nextNumber": 999}""".formatted(store)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nextNumber").value(5))
                .andReturn().getResponse().getContentAsString()).toString();

        for (var request : List.of(put(SERIES + "/" + id), patch(SERIES + "/" + id))) {
            mvc.perform(request.with(admin(Permission.FISCAL_MANAGE)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"nextNumber\": 999}"))
                    .andExpect(status().is4xxClientError());
        }
        assertThat(jdbc.sql("SELECT next_number FROM fiscal.fiscal_series WHERE id = :id")
                .param("id", UUID.fromString(id)).query(Long.class).single()).isEqualTo(5);
    }

    @Test
    void aStoreHasOneActiveSeriesAndDeactivatingOneMakesRoomForTheNext() throws Exception {
        UUID store = fixtures.newStore();
        String first = FiscalFixtures.id(create(store, "A", 1).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).toString();

        create(store, "B", 1).andExpect(status().isConflict());
        mvc.perform(post(SERIES + "/" + first + "/deactivate").with(admin(Permission.FISCAL_MANAGE)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        create(store, "B", 1).andExpect(status().isCreated());

        mvc.perform(post(SERIES + "/" + first + "/deactivate").with(admin(Permission.FISCAL_MANAGE)).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(fixtures.audits("FISCAL_SERIES_DEACTIVATED", first)).isEqualTo(1);
    }

    @Test
    void aSeriesCodeIsUniqueInItsStore() throws Exception {
        UUID store = fixtures.newStore();
        String first = FiscalFixtures.id(create(store, "A", 1).andReturn().getResponse().getContentAsString())
                .toString();
        mvc.perform(post(SERIES + "/" + first + "/deactivate").with(admin(Permission.FISCAL_MANAGE)).with(csrf()))
                .andExpect(status().isOk());

        create(store, "A", 50).andExpect(status().isConflict());
        create(fixtures.newStore(), "A", 1).andExpect(status().isCreated());
    }

    @Test
    void twoConcurrentCreatesForOneStoreGiveOneSeries() throws Exception {
        UUID store = fixtures.newStore();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> createB = () -> {
            start.await();
            return create(store, "C" + UUID.randomUUID().toString().substring(0, 6), 1)
                    .andReturn().getResponse().getStatus();
        };

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> one = pool.submit(createB);
            Future<Integer> two = pool.submit(createB);
            start.countDown();
            statuses = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    }

    @Test
    void managingSeriesNeedsInstallationWideFiscalManage() throws Exception {
        UUID store = fixtures.newStore();

        mvc.perform(post(SERIES).with(inStore(store, Permission.FISCAL_DOCUMENT_VIEW)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId": "%s", "code": "A"}""".formatted(store)))
                .andExpect(status().isForbidden());
        mvc.perform(get(SERIES).with(pos(store, UUID.randomUUID(), UUID.randomUUID(), Permission.ORDER_EDIT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownStoreOrSeriesIsNotFound() throws Exception {
        create(UUID.randomUUID(), "A", 1).andExpect(status().isNotFound());
        mvc.perform(post(SERIES + "/" + UUID.randomUUID() + "/deactivate").with(admin(Permission.FISCAL_MANAGE))
                .with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void anInvalidRequestIsABadRequest() throws Exception {
        mvc.perform(post(SERIES).with(admin(Permission.FISCAL_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId": "%s", "code": " ", "firstNumber": 0}""".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void seriesAreListedPerStore() throws Exception {
        UUID store = fixtures.newStore();
        create(store, "A", 7).andExpect(status().isCreated());

        mvc.perform(get(SERIES).param("storeId", store.toString()).with(admin(Permission.FISCAL_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].code").value("A"))
                .andExpect(jsonPath("$.content[0].nextNumber").value(7));
    }

    private ResultActions create(UUID store, String code, long firstNumber) throws Exception {
        return mvc.perform(post(SERIES).with(admin(Permission.FISCAL_MANAGE)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"storeId": "%s", "code": "%s", "firstNumber": %d}""".formatted(store, code, firstNumber)));
    }
}
