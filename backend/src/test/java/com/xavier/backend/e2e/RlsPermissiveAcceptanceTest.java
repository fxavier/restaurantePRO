package com.xavier.backend.e2e;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.beans.factory.annotation.Autowired;
import static com.xavier.backend.e2e.Installation.MANAGER_ROLE;
import static com.xavier.backend.e2e.Installation.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.config.security.TestJwtFactory;
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
 * emergency switch) starts and serves, raises the CRITICAL {@code STORE_SCOPE_PERMISSIVE} alert (a platform alert:
 * platform operators see it, a tenant's BackOffice does not), has one system audit
 * row {@code STORE_SCOPE_PERMISSIVE_STARTED} that the installation-wide auditor reads, and {@code AccessPolicy} still
 * decides: a store-A manager still gets 403 on store B's price list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Installation.FreshDatabase.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RlsPermissiveAcceptanceTest {

    @Autowired
    PostgreSQLContainer freshPostgres;

    /** The installation's owner connection, for what onboarding and Keycloak would have created. */
    private JdbcClient owner() {
        return Installation.owner(freshPostgres);
    }

    static final String INITIAL_PASSWORD = "initial " + UUID.randomUUID();
    static final String NEW_PASSWORD = "changed " + UUID.randomUUID();
    static final String BO = "/api/v1/backoffice";
    static final String PLATFORM_ALERTS = "/api/v1/platform/alerts";
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
            Installation.firstLogin(office, owner());

            Browser operator = new Browser("platform-operator", port, journey)
                    .signedIn(() -> TestJwtFactory.support("operator-" + UUID.randomUUID(), Instant.now())
                            .claim("realm_access", Map.of("roles", List.of("platform-operator"))).encode());
            JsonNode permissive = null;
            for (JsonNode alert : operator.get(PLATFORM_ALERTS, 200).get("alerts")) {
                if (alert.get("code").asString().equals("STORE_SCOPE_PERMISSIVE")) {
                    permissive = alert;
                }
            }
            assertThat(permissive).as("the STORE_SCOPE_PERMISSIVE alert, for platform operators").isNotNull();
            assertThat(permissive.get("severity").asString()).isEqualTo("CRITICAL");
            assertThat(office.get(BO + "/alerts", 200).get("alerts").valueStream()
                    .map(alert -> alert.get("code").asString()))
                    .as("a platform alert never reaches a tenant user").doesNotContain("STORE_SCOPE_PERMISSIVE");

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
            Browser managerA = Installation.user(office, owner(), "ana", employee, "ana");

            managerA.get(Installation.CATALOG + "/price-lists/" + listB, 403);
            managerA.get(Installation.CATALOG + "/price-lists?storeId=" + storeB, 403);
        } finally {
            System.out.println("RLS PERMISSIVE journey (step -> HTTP status):\n  " + String.join("\n  ", journey));
        }
    }
}
