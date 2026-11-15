package com.xavier.backend.e2e;

import static com.xavier.backend.e2e.Installation.MANAGER_ROLE;
import static com.xavier.backend.e2e.Installation.id;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * D170 end to end: an installation started with {@code RESTAURANTPRO_STORE_SCOPE_ENFORCEMENT=PERMISSIVE} (the
 * emergency switch) starts and serves, raises the CRITICAL {@code STORE_SCOPE_PERMISSIVE} alert, has one system audit
 * row {@code STORE_SCOPE_PERMISSIVE_STARTED} that the installation-wide auditor reads, and {@code AccessPolicy} still
 * decides: a store-A manager still gets 403 on store B's price list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Installation.FreshDatabase.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RlsPermissiveAcceptanceTest {

    static final String INITIAL_PASSWORD = "initial " + UUID.randomUUID();
    static final String NEW_PASSWORD = "changed " + UUID.randomUUID();
    static final String BO = "/api/v1/backoffice";
    static final Instant STARTED = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    @DynamicPropertySource
    static void permissive(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, "Restaurante Permissivo", INITIAL_PASSWORD);
        registry.add("restaurantpro.store-scope.enforcement", () -> "PERMISSIVE");
    }

    @LocalServerPort
    int port;

    private final List<String> journey = new ArrayList<>();

    @Test
    void thePermissiveSwitchStartsAlertsAndAuditsAndAccessPolicyStillDecides() throws Exception {
        try {
            Browser office = new Browser("backoffice", port, journey);
            Installation.firstLogin(office, INITIAL_PASSWORD, NEW_PASSWORD);

            JsonNode permissive = null;
            for (JsonNode alert : office.get(BO + "/alerts", 200).get("alerts")) {
                if (alert.get("code").asString().equals("STORE_SCOPE_PERMISSIVE")) {
                    permissive = alert;
                }
            }
            assertThat(permissive).as("the STORE_SCOPE_PERMISSIVE alert").isNotNull();
            assertThat(permissive.get("severity").asString()).isEqualTo("CRITICAL");

            JsonNode audits = office.get(BO + "/audit/events?action=STORE_SCOPE_PERMISSIVE_STARTED&from=" + STARTED
                    + "&to=" + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS), 200);
            assertThat(audits.get("content")).singleElement().satisfies(row -> {
                assertThat(row.get("actorKind").asString()).isEqualTo("SYSTEM");
                assertThat(row.get("storeId").isNull()).as("store-less").isTrue();
            });

            UUID storeA = Installation.store(office, "A");
            UUID storeB = Installation.store(office, "B");
            UUID listB = id(office.post(Installation.CATALOG + "/price-lists", Map.of("storeId", storeB, "name",
                    "Sala B"), 201));
            UUID employee = id(office.post(BO + "/employees", Map.of("code", "GA", "name", "Ana", "pin", "9182",
                    "storeId", storeA, "roleId", MANAGER_ROLE), 201));
            String password = "pw " + UUID.randomUUID();
            office.post(BO + "/users", Map.of("username", "ana", "initialPassword", password, "employeeId",
                    employee), 201);
            Browser managerA = office.sibling("ana");
            managerA.get("/api/v1/auth/session", 401);
            managerA.post("/api/v1/auth/login", Map.of("username", "ana", "password", password), 200);
            managerA.post("/api/v1/auth/password", Map.of("currentPassword", password, "newPassword",
                    password + " 2"), 200);

            managerA.get(Installation.CATALOG + "/price-lists/" + listB, 403);
            managerA.get(Installation.CATALOG + "/price-lists?storeId=" + storeB, 403);
        } finally {
            System.out.println("RLS PERMISSIVE journey (step -> HTTP status):\n  " + String.join("\n  ", journey));
        }
    }
}
