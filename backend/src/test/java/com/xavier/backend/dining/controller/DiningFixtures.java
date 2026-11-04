package com.xavier.backend.dining.controller;

import static com.xavier.backend.dining.DiningActors.admin;
import static com.xavier.backend.dining.DiningActors.inStore;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds stores (through the organization HTTP API, as an installation admin would), areas and tables for the
 * dining HTTP tests, and reads the audit trail.
 */
@TestComponent
class DiningFixtures {

    static final String AREAS = "/api/v1/backoffice/areas";
    static final String TABLES = "/api/v1/backoffice/tables";
    static final String POS_TABLES = "/api/v1/pos/tables";
    static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    DiningFixtures(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    UUID newStore() throws Exception {
        String code = "D" + UUID.randomUUID().toString().substring(0, 8);
        String json = mvc.perform(post("/api/v1/backoffice/stores").with(admin(Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "Store", "timezone": "Africa/Maputo"}""".formatted(code)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return id(json);
    }

    UUID createArea(UUID store, String code) throws Exception {
        return id(mvc.perform(post(AREAS).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(newArea(store, code)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    UUID createTable(UUID store, UUID area, String code) throws Exception {
        return id(mvc.perform(post(TABLES).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(newTable(store, area, code, 4)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    long audits(String action, String entityId, UUID store) {
        return jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event
                        WHERE action = :action AND entity_id = :entity AND store_id = :store""")
                .param("action", action).param("entity", entityId).param("store", store)
                .query(Long.class).single();
    }

    static String newArea(UUID store, String code) {
        return """
                {"storeId": "%s", "code": "%s", "name": "Sala"}""".formatted(store, code);
    }

    static String newTable(UUID store, UUID area, String code, int seats) {
        return """
                {"storeId": "%s", "areaId": "%s", "code": "%s", "seats": %d}""".formatted(store, area, code, seats);
    }

    /** Fires {@code request} twice at the same instant (latch) and returns both HTTP statuses. */
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

    private static UUID id(String json) {
        return UUID.fromString(JSON.readTree(json).get("id").asString());
    }
}
