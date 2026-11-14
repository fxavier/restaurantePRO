package com.xavier.backend.e2e;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import com.xavier.backend.DatabaseRoles;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.e2e.Browser.Csrf;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * A first start of one installation through the BackOffice and POS HTTP APIs, as its people do it: the bootstrap
 * settings, the administrator's first login, stores, terminals enrolled for PIN logins, employees and a published
 * catalog. Shared by the R2 journey and the latency smoke.
 */
final class Installation {

    static final String ADMIN = "owner";
    static final String ZONE = "Africa/Maputo";
    static final String CATALOG = "/api/v1/backoffice/catalog";
    static final UUID MANAGER_ROLE = UUID.fromString("01900000-0000-7000-8000-000000000302");
    static final UUID CASHIER_ROLE = UUID.fromString("01900000-0000-7000-8000-000000000304");

    private Installation() {
    }

    /** An empty PostgreSQL of the production pin, a bean of the test's context (closed with it). */
    @TestConfiguration(proxyBeanMethods = false)
    static class FreshDatabase {

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

    /** The bootstrap settings of a company in MZN whose administrator {@link #ADMIN} starts with {@code password}. */
    static void bootstrap(DynamicPropertyRegistry registry, String company, String password) throws IOException {
        Path secret = Files.createTempFile("admin-initial-password", "");
        secret.toFile().deleteOnExit();
        Files.writeString(secret, password + "\n");
        registry.add("restaurantpro.bootstrap.company.name", () -> company);
        registry.add("restaurantpro.bootstrap.company.tax-id", () -> "400000002");
        registry.add("restaurantpro.bootstrap.company.country", () -> "MZ");
        registry.add("restaurantpro.bootstrap.company.currency", () -> "MZN");
        registry.add("restaurantpro.bootstrap.admin.username", () -> ADMIN);
        registry.add("restaurantpro.bootstrap.admin.initial-password-file", secret::toString);
        // An open change stream is an active request until its next write fails, so a graceful shutdown would wait
        // the full phase timeout (30 s) for it when the context closes.
        registry.add("server.shutdown", () -> "immediate");
    }

    /** The administrator's first login (the account comes from a listener, so it is polled) and password change. */
    static void firstLogin(Browser office, String initial, String changed) throws Exception {
        office.get("/api/v1/auth/session", 401);
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> office.tryPost("/api/v1/auth/login",
                Map.of("username", ADMIN, "password", initial)), r -> r != null);
        office.post("/api/v1/auth/password", Map.of("currentPassword", initial, "newPassword", changed), 200);
    }

    /** A store whose cutoff is 12 h away, so the business day does not turn over while a test runs. */
    static UUID store(Browser office, String suffix) throws Exception {
        LocalTime cutoff = LocalTime.now(ZoneId.of(ZONE)).plusHours(12).truncatedTo(ChronoUnit.HOURS);
        return id(office.post("/api/v1/backoffice/stores", Map.of("code", "LOJA-" + suffix, "name", "Loja " + suffix,
                "timezone", ZONE, "businessDayCutoff", cutoff.toString()), 201));
    }

    static UUID terminal(Browser office, UUID store, String code) throws Exception {
        return id(office.post("/api/v1/backoffice/terminals", Map.of("storeId", store, "code", code, "name", code),
                201));
    }

    /** An employee of {@code store} with {@code role}; returns the code they log in with. */
    static String employee(Browser office, UUID store, String code, String name, String pin, UUID role)
            throws Exception {
        office.post("/api/v1/backoffice/employees", Map.of("code", code, "name", name, "pin", pin, "storeId", store,
                "roleId", role), 201);
        return code;
    }

    /** A new POS tab on {@code terminal}: enrolled with a fresh code, then the employee's PIN login. */
    static Browser posLogin(Browser office, UUID terminal, String code, String pin, String name) throws Exception {
        String enrollment = office.post("/api/v1/backoffice/terminals/" + terminal + "/enrollment-code", null, 200)
                .get("code").asString();
        Browser pos = office.sibling(name);
        pos.get("/api/v1/auth/session", 401);
        String token = pos.post("/api/v1/auth/terminals/enroll", Map.of("code", enrollment), 200)
                .get("deviceToken").asString();
        pos.post("/api/v1/auth/pos-login", Map.of("employeeCode", code, "pin", pin), 200, Csrf.SEND,
                Map.of("X-Terminal-Token", token), "pos-login");
        return pos;
    }

    /** Prego (kitchen) 150.00, Sumo and Agua (bar) 60.00 and 40.00, all 16% VAT, published for {@code store}. */
    static Map<String, UUID> publishCatalog(Browser office, UUID store) throws Exception {
        UUID family = id(office.post(CATALOG + "/families", Map.of("code", "REST", "name", "Restaurante"), 201));
        UUID unit = id(office.post(CATALOG + "/units", Map.of("code", "UN", "name", "Unidade"), 201));
        UUID tax = id(office.post(CATALOG + "/tax-categories", Map.of("code", "IVA16", "name", "IVA"), 201));
        office.post(CATALOG + "/tax-categories/" + tax + "/rates",
                Map.of("ratePercent", new BigDecimal("16"), "effectiveFrom", "2000-01-01T00:00:00Z"), 201);
        UUID priceList = id(office.post(CATALOG + "/price-lists", Map.of("storeId", store, "name", "Sala"), 201));
        Map<String, UUID> items = new LinkedHashMap<>();
        String[][] menu = {{"PREGO", "Prego no pao", "COZINHA", "150.00"}, {"SUMO", "Sumo de manga", "BAR", "60.00"},
                {"AGUA", "Agua 50cl", "BAR", "40.00"}};
        for (String[] dish : menu) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", dish[0]);
            item.put("name", dish[1]);
            item.put("familyId", family);
            item.put("unitId", unit);
            item.put("taxCategoryId", tax);
            item.put("productionDestination", dish[2]);
            item.put("soldByWeight", false);
            UUID itemId = id(office.post(CATALOG + "/items", item, 201));
            office.post(CATALOG + "/price-lists/" + priceList + "/prices",
                    Map.of("itemId", itemId, "amount", new BigDecimal(dish[3])), 201);
            items.put(dish[0], itemId);
        }
        UUID release = id(office.post(CATALOG + "/releases", Map.of("storeId", store), 201));
        office.post(CATALOG + "/releases/" + release + "/validate", null, 200);
        office.command(CATALOG + "/releases/" + release + "/publish", Map.of("note", "R2"), 200, "publish");
        return items;
    }

    /**
     * Prego (kitchen) 150.00, Sumo, Agua (bar) 60.00 and 37.00, Bolo (kitchen, dessert) 45.00, all 16 % VAT; Pao and
     * Molho (kitchen) 1.00 and 2.00 at 23 %, published for {@code store}.
     */
    static Map<String, UUID> publishTwoRateCatalog(Browser bo, UUID store) throws Exception {
        String catalog = CATALOG;
        UUID family = id(bo.post(catalog + "/families", Map.of("code", "REST", "name", "Restaurante"), 201));
        UUID unit = id(bo.post(catalog + "/units", Map.of("code", "UN", "name", "Unidade"), 201));
        Map<String, UUID> taxes = new LinkedHashMap<>();
        for (String rate : List.of("16", "23")) {
            UUID tax = id(bo.post(catalog + "/tax-categories", Map.of("code", "IVA" + rate, "name", "IVA " + rate),
                    201));
            bo.post(catalog + "/tax-categories/" + tax + "/rates", Map.of("ratePercent", new BigDecimal(rate),
                    "effectiveFrom", "2000-01-01T00:00:00Z"), 201);
            taxes.put(rate, tax);
        }
        UUID priceList = id(bo.post(catalog + "/price-lists", Map.of("storeId", store, "name", "Sala"), 201));
        Map<String, UUID> menu = new LinkedHashMap<>();
        String[][] dishes = {{"PREGO", "Prego no pao", "COZINHA", "150.00", "16"},
                {"SUMO", "Sumo de manga", "BAR", "60.00", "16"}, {"AGUA", "Agua 50cl", "BAR", "37.00", "16"},
                {"BOLO", "Bolo de coco", "COZINHA", "45.00", "16"}, {"PAO", "Pao", "COZINHA", "1.00", "23"},
                {"MOLHO", "Molho piri-piri", "COZINHA", "2.00", "23"}};
        for (String[] dish : dishes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", dish[0]);
            item.put("name", dish[1]);
            item.put("familyId", family);
            item.put("unitId", unit);
            item.put("taxCategoryId", taxes.get(dish[4]));
            item.put("productionDestination", dish[2]);
            item.put("soldByWeight", false);
            UUID itemId = id(bo.post(catalog + "/items", item, 201));
            bo.post(catalog + "/price-lists/" + priceList + "/prices", Map.of("itemId", itemId,
                    "amount", new BigDecimal(dish[3])), 201);
            menu.put(dish[0], itemId);
        }
        UUID release = id(bo.post(catalog + "/releases", Map.of("storeId", store), 201));
        bo.post(catalog + "/releases/" + release + "/validate", null, 200);
        bo.command(catalog + "/releases/" + release + "/publish", Map.of("note", "R3"), 200, "publish");
        return menu;
    }

    static UUID id(JsonNode body) {
        return UUID.fromString(body.get("id").asString());
    }
}
