package com.xavier.backend.organization.controller;

import static com.xavier.backend.organization.Actors.admin;
import static com.xavier.backend.organization.Actors.inStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.organization.OrganizationModuleTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@OrganizationModuleTest
class StoreHttpTest {

    static final String STORES = "/api/v1/backoffice/stores";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void anAdminCreatesAStoreThatUsesTheCompanyCurrencyAndIsAudited() throws Exception {
        String code = newCode();

        String id = mvc.perform(post(STORES).with(admin(Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "Baixa", "timezone": "Africa/Maputo"}""".formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.businessDayCutoff").value("05:00:00"))
                .andExpect(jsonPath("$.currency").value("MZN"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event
                        WHERE action = 'STORE_CREATED' AND entity_id = :id AND store_id = :store AND actor_id = :actor
                        """).param("id", id).param("store", UUID.fromString(id))
                .param("actor", com.xavier.backend.organization.Actors.USER_ID).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void aStoreManagerOfThatStoreReadsItButNotAnotherStore() throws Exception {
        UUID mine = createStore();
        UUID other = createStore();

        mvc.perform(get(STORES + "/" + mine).with(inStore(mine, Permission.STORE_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(mine.toString()))
                .andExpect(jsonPath("$.timezone").value("Africa/Maputo"));
        mvc.perform(get(STORES + "/" + other).with(inStore(mine, Permission.STORE_MANAGE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void readingAStoreNeedsStoreManage() throws Exception {
        UUID store = createStore();

        mvc.perform(get(STORES + "/" + store).with(inStore(store, Permission.CATALOG_VIEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownStoreIsNotFound() throws Exception {
        mvc.perform(get(STORES + "/" + UUID.randomUUID()).with(admin(Permission.STORE_MANAGE)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listingStoresNeedsInstallationWideStoreManage() throws Exception {
        UUID store = createStore();

        mvc.perform(get(STORES).param("size", "200").with(admin(Permission.STORE_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(store)).exists())
                .andExpect(jsonPath("$.totalElements").isNumber());
        mvc.perform(get(STORES).with(inStore(store, Permission.STORE_MANAGE)))
                .andExpect(status().isForbidden());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void sortingByAnUnknownPropertyIsABadRequestNotAnError(CapturedOutput output) throws Exception {
        mvc.perform(get(STORES).param("sort", "nope").with(admin(Permission.STORE_MANAGE)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Unknown sort property: nope"));
        assertThat(output.getAll()).doesNotContain("Unhandled exception");
    }

    @Test
    void anAdminUpdatesAStoreAndTheChangeIsAudited() throws Exception {
        UUID store = createStore();

        mvc.perform(put(STORES + "/" + store).with(admin(Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update(newCode(), "Europe/Lisbon", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Europe/Lisbon"))
                .andExpect(jsonPath("$.businessDayCutoff").value("06:30:00"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'STORE_UPDATED' AND store_id = :id")
                .param("id", store).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void twoConcurrentUpdatesOfTheSameVersionGiveOneSuccessAndOneConflict() throws Exception {
        // criterion §7.5
        UUID store = createStore();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> update = () -> {
            start.await();
            return mvc.perform(put(STORES + "/" + store).with(admin(Permission.STORE_MANAGE)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(update(newCode(), "Africa/Maputo", 0)))
                    .andReturn().getResponse().getStatus();
        };

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(update);
            Future<Integer> second = pool.submit(update);
            start.countDown();
            statuses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.sql("SELECT version FROM organization.store WHERE id = :id").param("id", store)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void storeCodesAreUnique() throws Exception {
        String code = newCode();
        mvc.perform(post(STORES).with(admin(Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(create(code, "Africa/Maputo")))
                .andExpect(status().isCreated());

        mvc.perform(post(STORES).with(admin(Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(create(code, "Africa/Maputo")))
                .andExpect(status().isConflict());
        UUID other = createStore();
        mvc.perform(put(STORES + "/" + other).with(admin(Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update(code, "Africa/Maputo", 0)))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnknownTimezoneBreaksABusinessRule() throws Exception {
        mvc.perform(post(STORES).with(admin(Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(create(newCode(), "Mars/Olympus")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aMissingNameIsAValidationError() throws Exception {
        mvc.perform(post(STORES).with(admin(Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\", \"timezone\": \"Africa/Maputo\"}".formatted(newCode())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void creatingOrEditingStoresNeedsInstallationWideStoreManage() throws Exception {
        UUID store = createStore();

        mvc.perform(put(STORES + "/" + store).with(inStore(store, Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update(newCode(), "Africa/Maputo", 0)))
                .andExpect(status().isForbidden());
        mvc.perform(post(STORES).with(inStore(store, Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(create(newCode(), "Africa/Maputo")))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentCreatesWithTheSameCodeGiveOneStoreAndOneConflict() throws Exception {
        for (int round = 0; round < 10; round++) { // several rounds, so both requests pass the pre-check at least once
            String code = newCode();
            List<Integer> statuses = race(() -> mvc.perform(post(STORES).with(admin(Permission.STORE_MANAGE))
                            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(create(code, "Africa/Maputo")))
                    .andReturn().getResponse().getStatus());

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(201, 409);
        }
    }

    @Test
    void concurrentUpdatesToTheSameNewCodeGiveOneSuccessAndOneConflict() throws Exception {
        for (int round = 0; round < 10; round++) {
            UUID first = createStore();
            UUID second = createStore();
            String code = newCode();
            List<UUID> targets = List.of(first, second);
            java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
            List<Integer> statuses = race(() -> mvc.perform(put(STORES + "/" + targets.get(next.getAndIncrement()))
                            .with(admin(Permission.STORE_MANAGE)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(update(code, "Africa/Maputo", 0)))
                    .andReturn().getResponse().getStatus());

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 409);
        }
    }

    /** Runs {@code request} twice at the same moment and returns both HTTP statuses. */
    static List<Integer> race(Callable<Integer> request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> gated = () -> {
            start.await();
            return request.call();
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(gated);
            Future<Integer> second = pool.submit(gated);
            start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }

    static String create(String code, String timezone) {
        return """
                {"code": "%s", "name": "Store", "timezone": "%s"}""".formatted(code, timezone);
    }

    static String update(String code, String timezone, long version) {
        return """
                {"code": "%s", "name": "Renamed", "timezone": "%s", "businessDayCutoff": "06:30",
                 "active": true, "version": %d}""".formatted(code, timezone, version);
    }

    UUID createStore() throws Exception {
        String json = mvc.perform(post(STORES).with(admin(Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create(newCode(), "Africa/Maputo")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonMapper.builder().build().readTree(json).get("id").asString());
    }

    static String newCode() {
        return "S" + UUID.randomUUID().toString().substring(0, 8);
    }
}
