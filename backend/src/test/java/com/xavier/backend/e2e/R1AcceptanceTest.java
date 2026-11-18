package com.xavier.backend.e2e;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import com.xavier.backend.DatabaseRoles;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.config.security.TestJwtFactory;
import com.xavier.backend.e2e.Browser.Csrf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * R1 end to end (PRD S6, decisions §7): the whole Foundation + Stage 1 journey through the real HTTP API of a
 * real embedded server, with the real security chain, sessions in PostgreSQL and CSRF, and no mocks.
 *
 * <p>It runs against its own, empty PostgreSQL (same pinned image as production), not the database shared by
 * the other tests, so the first start is real: the company and the administrator come from the bootstrap
 * settings (D30), and the catalog holds only what this journey creates. The container is a bean of this
 * context, and the context is closed after the class, so the container goes with it.
 *
 * <p>The clients are {@link Browser}s: each sends its own credentials (a BackOffice access token, a POS session).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(R1AcceptanceTest.FreshDatabase.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R1AcceptanceTest {

    @Autowired
    PostgreSQLContainer freshPostgres;

    /** The installation's owner connection, for what onboarding and Keycloak would have created. */
    private JdbcClient owner() {
        return Installation.owner(freshPostgres);
    }

    private static final JsonMapper JSON = Browser.JSON;
    private static final String ADMIN = "owner";
    private static final String PIN = "4831";
    private static final UUID WAITER_ROLE = UUID.fromString("01900000-0000-7000-8000-000000000303");

    @TestConfiguration(proxyBeanMethods = false)
    static class FreshDatabase {

        /** Stopped when this test's context closes (Spring Boot manages a container bean's lifecycle). */
        @Bean
        PostgreSQLContainer freshPostgres() {
            PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE_NAME)
                    .withUrlParam("sslmode", "disable");
            postgres.start();
            // ADR 0039 (D163): the roles exist before Flyway and the pool connect, as db-provision does in production
            DatabaseRoles.provision(postgres);
            return postgres;
        }

        /** The pool as the application role. */
        @Bean
        JdbcConnectionDetails freshPostgresConnection(PostgreSQLContainer freshPostgres) {
            return DatabaseRoles.applicationConnection(freshPostgres);
        }

        /** Flyway as the owner. */
        @Bean
        FlywayConnectionDetails freshPostgresFlyway(PostgreSQLContainer freshPostgres) {
            return DatabaseRoles.ownerConnection(freshPostgres);
        }
    }

    @DynamicPropertySource
    static void bootstrapSettings(DynamicPropertyRegistry registry) throws IOException {
        registry.add("restaurantpro.security.issuers.tenant", () -> TestJwtFactory.TENANT_ISSUER);
        registry.add("restaurantpro.security.issuers.ops", () -> TestJwtFactory.OPS_ISSUER);
        registry.add("restaurantpro.bootstrap.company.name", () -> "Restaurante Acceptance");
        registry.add("restaurantpro.bootstrap.company.tax-id", () -> "400000001");
        registry.add("restaurantpro.bootstrap.company.country", () -> "MZ");
        registry.add("restaurantpro.bootstrap.company.currency", () -> "MZN");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private final List<String> journey = new ArrayList<>();

    @Test
    void foundationAndStage1FromFirstStartToARevokedTerminal() throws Exception {
        try {
            run();
        } finally {
            System.out.println("R1 acceptance journey (step -> HTTP status):\n  " + String.join("\n  ", journey));
        }
    }

    private void run() throws Exception {
        // 1. First start: exactly one company (criterion 1), and its administrator from the bootstrap settings
        assertThat(jdbc.sql("SELECT count(*) FROM organization.company_profile").query(Long.class).single())
                .isEqualTo(1);
        assertThat(audits("COMPANY_BOOTSTRAPPED")).isEqualTo(1);

        Browser office = new Browser("backoffice", port, journey);
        // criterion 2 (D176): no access token, no access; a write without one is refused before anything runs
        office.get("/api/v1/auth/session", 401);
        office.post("/api/v1/backoffice/stores", store("NO-TOKEN"), 401);

        // the administrator as onboarding leaves it (D181); it logs in through Keycloak (here a test token)
        UUID adminId = Installation.account(owner(), ADMIN, Installation.ADMINISTRATOR_ROLE);
        Installation.signIn(office, owner(), adminId);
        JsonNode login = office.get("/api/v1/auth/session", 200);
        assertThat(login.get("userId").asString()).isEqualTo(adminId.toString());
        assertThat(login.get("permissions").valueStream().map(JsonNode::asString))
                .contains("PERM_STORE_MANAGE", "PERM_CATALOG_PUBLISH");
        office.sibling("forger").post("/api/v1/backoffice/stores", store("FORGED"), 401, Csrf.SEND,
                Map.of("Authorization", "Bearer " + TestJwtFactory.account("kc-" + adminId).unsigned().encode()),
                "an unsigned token of the administrator");

        // 3. Store A (the journey's) and store B (for the store-scope checks)
        UUID storeA = id(office.post("/api/v1/backoffice/stores", store("A"), 201));
        UUID storeB = id(office.post("/api/v1/backoffice/stores", store("B"), 201));
        assertThat(auditsBy(adminId, "STORE_CREATED", storeA)).isEqualTo(1);

        // 4. Terminal + one-time enrolment code; the POS device enrols into its own Keycloak client (D182: what the
        // enrolment leaves behind is written as the owner, the enrolment itself is TerminalEnrolmentThrottleTest's)
        UUID terminal = id(office.command("/api/v1/backoffice/terminals",
                Map.of("storeId", storeA, "code", "POS1", "name", "Bar"), 201, "register the terminal"));
        office.post("/api/v1/backoffice/terminals/" + terminal + "/enrollment-code", null, 200);
        Browser pos = new Browser("pos", port, journey);
        pos.get("/api/v1/auth/session", 401);
        pos.enrolled(Installation.enrolled(office, terminal));
        assertThat(auditsBy(adminId, "TERMINAL_CREATED", terminal)).isEqualTo(1);
        assertThat(auditsBy(adminId, "TERMINAL_ENROLLMENT_CODE_ISSUED", terminal)).isEqualTo(1);

        // 5. A waiter (Empregado de sala) of store A, with a PIN
        String waiterCode = "W" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode waiter = office.post("/api/v1/backoffice/employees", Map.of("code", waiterCode, "name", "Ana",
                "pin", PIN, "storeId", storeA, "roleId", WAITER_ROLE), 201);
        JsonNode assignments = office.get("/api/v1/backoffice/employees/" + id(waiter) + "/assignments", 200);
        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).get("storeId").asString()).isEqualTo(storeA.toString());
        assertThat(assignments.get(0).get("roleId").asString()).isEqualTo(WAITER_ROLE.toString());
        assertThat(auditedEntity(adminId, id(waiter))).isPositive();

        // 6. Dining: one area and one table in store A, so the POS table map has content
        UUID area = id(office.post("/api/v1/backoffice/areas",
                Map.of("storeId", storeA, "code", "SALA", "name", "Sala"), 201));
        UUID table = id(office.post("/api/v1/backoffice/tables",
                Map.of("storeId", storeA, "areaId", area, "code", "M1", "seats", 4), 201));

        // 7. Catalog working copy: family, unit, tax category + rate, item, store A's price list + price
        String catalog = "/api/v1/backoffice/catalog";
        UUID family = id(office.post(catalog + "/families", Map.of("code", "BEB", "name", "Bebidas"), 201));
        UUID unit = id(office.post(catalog + "/units", Map.of("code", "UN", "name", "Unidade"), 201));
        UUID tax = id(office.post(catalog + "/tax-categories", Map.of("code", "IVA16", "name", "IVA"), 201));
        office.post(catalog + "/tax-categories/" + tax + "/rates",
                Map.of("ratePercent", new BigDecimal("16"), "effectiveFrom", "2000-01-01T00:00:00Z"), 201);
        Map<String, Object> itemBody = new LinkedHashMap<>();
        itemBody.put("code", "COCA33");
        itemBody.put("name", "Coca-Cola 33cl");
        itemBody.put("familyId", family);
        itemBody.put("unitId", unit);
        itemBody.put("taxCategoryId", tax);
        itemBody.put("productionDestination", "BAR");
        itemBody.put("soldByWeight", false);
        UUID item = id(office.post(catalog + "/items", itemBody, 201));
        UUID priceList = id(office.post(catalog + "/price-lists", Map.of("storeId", storeA, "name", "Sala"), 201));
        office.post(catalog + "/price-lists/" + priceList + "/prices",
                Map.of("itemId", item, "amount", new BigDecimal("45.00")), 201);
        assertThat(auditsBy(adminId, "ITEM_CREATED", item)).isEqualTo(1);

        // 8. Release: DRAFT -> VALIDATED -> PUBLISHED, publish idempotent (criterion 7)
        String releases = catalog + "/releases";
        UUID release = id(office.post(releases, Map.of("storeId", storeA), 201));
        office.post(releases + "/" + release + "/publish", Map.of(), 409, Csrf.SEND,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()), "publish a DRAFT");
        assertThat(office.post(releases + "/" + release + "/validate", null, 200).get("status").asString())
                .isEqualTo("VALIDATED");
        String key = UUID.randomUUID().toString();
        Map<String, String> idempotent = Map.of("Idempotency-Key", key);
        HttpResponse<String> first = office.send("POST", releases + "/" + release + "/publish",
                Map.of("note", "v1"), Csrf.SEND, idempotent);
        office.record("publish", first);
        assertThat(first.statusCode()).isEqualTo(200);
        HttpResponse<String> replay = office.send("POST", releases + "/" + release + "/publish",
                Map.of("note", "v1"), Csrf.SEND, idempotent);
        office.record("publish replay (same key, same body)", replay);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(replay.body())).isEqualTo(JSON.readTree(first.body()));
        office.post(releases + "/" + release + "/publish", Map.of("note", "other"), 422, Csrf.SEND, idempotent,
                "publish (same key, different body)");
        JsonNode published = JSON.readTree(first.body());
        assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(published.get("version").asLong()).isEqualTo(1);
        assertThat(published.get("publishedBy").asString()).isEqualTo(adminId.toString());
        assertThat(auditsBy(adminId, "CATALOG_RELEASE_PUBLISHED", release)).isEqualTo(1);

        // 9. POS: PIN login on the enrolled terminal; the session is bound to the terminal's store
        pos.post("/api/v1/auth/pos-login", Map.of("employeeCode", waiterCode, "pin", PIN), 401, Csrf.SEND,
                Map.of(), "pos-login without the terminal's token");
        JsonNode posSession = pos.post("/api/v1/auth/pos-login", Map.of("employeeCode", waiterCode, "pin", PIN),
                200, Csrf.SEND, pos.terminalAuthorization(), "pos-login");
        pos.posSession(posSession.get("posSession").asString());
        assertThat(posSession.get("storeId").asString()).isEqualTo(storeA.toString());
        assertThat(posSession.get("terminalId").asString()).isEqualTo(terminal.toString());
        assertThat(posSession.get("permissions").valueStream().map(JsonNode::asString))
                .containsExactlyInAnyOrder("PERM_DINING_VIEW@" + storeA, "PERM_CATALOG_VIEW@" + storeA,
                        // the D62, D92 and D134 defaults of the seeded waiter role (V3005, V3010, V3012)
                        "PERM_ORDER_VIEW@" + storeA, "PERM_ORDER_EDIT@" + storeA, "PERM_ORDER_VOID@" + storeA,
                        "PERM_ORDER_TRANSFER@" + storeA, "PERM_BILL_SPLIT@" + storeA,
                        "PERM_ORDER_DISCOUNT@" + storeA, "PERM_ORDER_OFFER@" + storeA, "PERM_LOYALTY_VIEW@" + storeA,
                        "PERM_LOYALTY_REDEEM@" + storeA, "PERM_RESERVATION_VIEW@" + storeA,
                        "PERM_RESERVATION_MANAGE@" + storeA);

        // 10. GET /pos/catalog: the published version, with the item, its price and its tax schedule
        JsonNode posCatalog = pos.get("/api/v1/pos/catalog?storeId=" + storeA, 200);
        assertThat(posCatalog.get("releaseId").asString()).isEqualTo(release.toString());
        assertThat(posCatalog.get("version").asLong()).isEqualTo(1);
        JsonNode snapshot = posCatalog.get("catalog");
        assertThat(snapshot.get("currency").asString()).isEqualTo("MZN");
        assertThat(snapshot.get("items")).hasSize(1);
        JsonNode sold = snapshot.get("items").get(0);
        assertThat(sold.get("id").asString()).isEqualTo(item.toString());
        assertThat(sold.get("code").asString()).isEqualTo("COCA33");
        assertThat(new BigDecimal(sold.get("price").get("amount").asString())).isEqualByComparingTo("45.00");
        assertThat(sold.get("price").get("currency").asString()).isEqualTo("MZN");
        assertThat(sold.get("taxCategoryId").asString()).isEqualTo(tax.toString());
        JsonNode schedule = snapshot.get("taxCategories").get(0);
        assertThat(schedule.get("id").asString()).isEqualTo(tax.toString());
        assertThat(schedule.get("rates")).hasSize(1);
        assertThat(new BigDecimal(schedule.get("rates").get(0).get("ratePercent").asString()))
                .isEqualByComparingTo("16");

        // 11. GET /pos/tables for the terminal's store
        JsonNode tables = pos.get("/api/v1/pos/tables?storeId=" + storeA, 200);
        assertThat(tables.get("areas")).hasSize(1);
        assertThat(tables.get("areas").get(0).get("id").asString()).isEqualTo(area.toString());
        assertThat(tables.get("areas").get(0).get("tables").get(0).get("id").asString())
                .isEqualTo(table.toString());
        assertThat(tables.get("areas").get(0).get("tables").get(0).get("status").asString()).isEqualTo("FREE");

        // 12. Criterion 4 against the real endpoints: the waiter's session is refused BackOffice catalog writes
        //     and anything of store B
        // a POS session is no BackOffice credential (D176, D182): the BackOffice routes answer 401 to it
        itemBody.put("code", "SNEAKY");
        pos.post(catalog + "/items", itemBody, 401);
        pos.post(catalog + "/price-lists/" + priceList + "/prices",
                Map.of("itemId", item, "amount", new BigDecimal("1.00")), 401);
        pos.post(releases, Map.of("storeId", storeA), 401);
        pos.post(releases + "/" + release + "/publish", Map.of(), 401, Csrf.SEND,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()), "waiter publish");
        pos.get("/api/v1/pos/catalog?storeId=" + storeB, 403);
        pos.get("/api/v1/pos/tables?storeId=" + storeB, 403);
        pos.get("/api/v1/backoffice/stores/" + storeB, 401);
        assertThat(jdbc.sql("SELECT count(*) FROM catalog.item WHERE code = 'SNEAKY'").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM catalog.configuration_release WHERE store_id = :s")
                .param("s", storeA).query(Long.class).single()).isEqualTo(1);

        // 13. Revoking the terminal refuses its very next request (its membership, D182), whatever token it holds
        assertThat(office.command("/api/v1/backoffice/terminals/" + terminal + "/revoke", null, 200,
                "revoke the terminal")
                .get("status").asString()).isEqualTo("REVOKED");
        pos.get("/api/v1/auth/pos-session", 403);
        pos.get("/api/v1/pos/catalog?storeId=" + storeA, 403);
        pos.post("/api/v1/auth/pos-login", Map.of("employeeCode", waiterCode, "pin", PIN), 403, Csrf.SEND,
                pos.terminalAuthorization(), "pos-login with the revoked terminal's token");
        assertThat(auditsBy(adminId, "TERMINAL_REVOKED", terminal)).isEqualTo(1);

        // The BackOffice user is untouched, and the store list answers (what the backup drill compares)
        JsonNode stores = office.get("/api/v1/backoffice/stores", 200);
        assertThat(stores.get("content").valueStream().map(s -> s.get("id").asString()))
                .containsExactlyInAnyOrder(storeA.toString(), storeB.toString());
    }

    private static Map<String, Object> store(String suffix) {
        return Map.of("code", "LOJA-" + suffix, "name", "Loja " + suffix, "timezone", "Africa/Maputo");
    }

    private long audits(String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = :a").param("a", action)
                .query(Long.class).single();
    }

    private long audits(String action, String entityId) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = :a AND entity_id = :e")
                .param("a", action).param("e", entityId).query(Long.class).single();
    }

    private long auditsBy(UUID actor, String action, UUID entityId) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE actor_id = :u AND action = :a AND entity_id = :e")
                .param("u", actor).param("a", action).param("e", entityId.toString()).query(Long.class).single();
    }

    private long auditedEntity(UUID actor, UUID entityId) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE actor_id = :u AND entity_id = :e")
                .param("u", actor).param("e", entityId.toString()).query(Long.class).single();
    }

    private static UUID id(JsonNode body) {
        return UUID.fromString(body.get("id").asString());
    }
}
