package com.xavier.backend.e2e;

import static com.xavier.backend.e2e.Installation.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.config.security.TestJwtFactory;
import com.xavier.backend.config.security.Devices;
import com.xavier.backend.tenancy.RecordingIdentityProvider;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * R6 end to end (PRD r6-multitenancy S76, multitenancy-decisions A80–A92): one central installation on its own
 * PostgreSQL, the pool as {@code restaurantpro_app}, the real HTTP API and security chain (D176: bearer tokens of the
 * tenant, ops and devices realms, signed by the test keys the test {@code TrustedRealms} trusts; the real IdP is
 * {@code KeycloakLoginIT}'s), and the Keycloak admin port faked at the module boundary
 * ({@link RecordingIdentityProvider}).
 * <ol>
 *   <li>A platform operator onboards tenants A and B (D181: one command each, the event chain makes them ACTIVE).
 *       Each administrator sets up the same codes: stores {@code LOJA-1}, {@code LOJA-2}, table {@code M1}, catalog
 *       {@code PREGO}/{@code SUMO}/{@code AGUA}, cashier {@code CX1}, manager {@code GA1}, register {@code CX1}, fiscal
 *       series {@code FR}, terminal {@code POS1} and print bridge {@code BR1}, both enrolled with one-time codes
 *       (D182, D187). A80.</li>
 *   <li>Each POS sells (A two pregos, 300.00; B one, 150.00) with a document; A's round runs the listener chain (round
 *       → production ticket and print job → sale consumption → reporting) on listener threads, every row in A
 *       (D185).</li>
 *   <li>A's print job is claimed only by A's bridge, B's only by B's (A89).</li>
 *   <li>Every route family and the sales report show each caller only its tenant; another tenant's id answers 404,
 *       another store of the same tenant 403 (A80, A82); a forged {@code X-Tenant-Id} and a body {@code tenantId}
 *       select nothing, with one 403 body for a foreign and a random tenant (A81).</li>
 *   <li>An accountant with memberships in both chooses with {@code X-Tenant-Id} and sees exactly the chosen one; a
 *       support person with a 1 h grant in A acts in A only (D177, D183).</li>
 *   <li>The platform API refuses a tenant token and a platform token reaches no business route (D181).</li>
 *   <li>B is suspended mid-test: the next request of each of B's callers (manager, POS, bridge, accountant) is 403
 *       {@code TENANT_SUSPENDED} while A keeps working, and the reach-ALL jobs stop taking leases for B while they
 *       keep taking A's (A90).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({Installation.FreshDatabase.class, RecordingIdentityProvider.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R6AcceptanceTest {

    static final String BO = "/api/v1/backoffice";
    static final String POS = "/api/v1/pos";
    static final String INV = BO + "/inventory";
    static final String PLATFORM = "/api/v1/platform/tenants";
    static final String CASHIER_PIN = "5172";
    static final Duration ASYNC = Duration.ofSeconds(60);
    static final String RUN = UUID.randomUUID().toString().substring(0, 6);

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, "Plataforma R6", "unused");
        registry.add("restaurantpro.security.issuers.devices", () -> TestJwtFactory.DEVICES_ISSUER);
        // the bridges of this journey claim their jobs themselves: no in-process stand-in takes them
        registry.add("restaurantpro.test.print-bridge", () -> "false");
    }

    @Autowired
    PostgreSQLContainer freshPostgres;

    @LocalServerPort
    int port;

    private final List<String> journey = new ArrayList<>();

    /** One tenant as its people set it up: the same codes in both. */
    static final class Company {
        final String label;
        UUID tenant;
        Browser admin;
        Browser manager;
        Browser pos;
        Browser bridge;
        UUID store1;
        UUID store2;
        UUID table;
        UUID register;
        UUID kitchenPrinter;
        UUID printer2;
        UUID store2Job;
        UUID priceList1;
        UUID priceList2;
        UUID managerRole;
        UUID cashierRole;
        Map<String, UUID> items;
        UUID order;
        UUID batch;

        Company(String label) {
            this.label = label;
        }
    }

    @Test
    void twoTenantsWithIdenticalCodesEachSeeOnlyTheirOwnThroughEveryRouteFamily() throws Exception {
        try {
            run();
        } finally {
            System.out.println("R6 acceptance journey (step -> HTTP status):\n  " + String.join("\n  ", journey));
        }
    }

    private void run() throws Exception {
        JdbcClient owner = Installation.owner(freshPostgres);
        Browser platform = new Browser("platform", port, journey).withFixtures(owner, Instant::now)
                .signedIn(() -> operatorToken().encode());

        // ---- 1. onboarding and identical set-up (A80) ----
        Company a = onboard(platform, owner, "A");
        Company b = onboard(platform, owner, "B");
        assertThat(a.tenant).isNotEqualTo(b.tenant);
        setUp(a, owner);
        setUp(b, owner);
        inventorySetUp(a);
        assertThat(owner.sql("""
                        SELECT count(DISTINCT tenant_id) FROM organization.store WHERE code = 'LOJA-1'
                          AND tenant_id IN (?, ?)""").param(a.tenant).param(b.tenant).query(Long.class).single())
                .as("the same store code in both tenants").isEqualTo(2);

        // ---- 2. each POS sells; A's listener chain runs in A ----
        sell(a, 2, "300.00");
        sell(b, 1, "150.00");

        Awaitility.await().atMost(ASYNC).until(() -> count(owner, """
                SELECT count(*) FROM reporting.consumption_record WHERE tenant_id = ? AND store_id = ?""",
                a.tenant, a.store1) == 1);
        assertThat(count(owner, "SELECT count(*) FROM production.production_ticket WHERE source_id = ? "
                + "AND tenant_id = ? AND store_id = ?", a.batch, a.tenant, a.store1)).isEqualTo(1);
        assertThat(count(owner, "SELECT count(*) FROM production.production_ticket WHERE source_id = ? "
                + "AND tenant_id <> ?", a.batch, a.tenant)).isZero();
        assertThat(count(owner, """
                SELECT count(*) FROM inventory.stock_movement WHERE type = 'SALE_CONSUMPTION' AND tenant_id = ?
                  AND store_id = ? AND quantity = -2""", a.tenant, a.store1)).isEqualTo(1);
        assertThat(count(owner, "SELECT count(*) FROM inventory.stock_movement WHERE tenant_id = ?", b.tenant))
                .as("B set up no inventory: nothing consumed in B").isZero();
        Awaitility.await().atMost(ASYNC).until(() -> count(owner,
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL") == 0);

        // ---- 3. A89: each bridge claims only its own store's jobs: a test ticket queued in store 2 of each tenant
        // (the same printer code) stays for store 2's bridge, never claimed by store 1's ----
        for (Company c : List.of(a, b)) {
            c.store2Job = id(c.admin.command(BO + "/printers/" + c.printer2 + "/test", null, 201,
                    "test ticket in store 2 of " + c.label));
        }
        List<UUID> claimedByA = drain(a.bridge);
        List<UUID> claimedByB = drain(b.bridge);
        assertThat(claimedByA).as("A's bridge claimed A's kitchen ticket").isNotEmpty();
        assertThat(claimedByB).as("B's bridge claimed B's kitchen ticket").isNotEmpty();
        assertThat(jobsOf(owner, claimedByA)).as("every job A's bridge got").containsOnly(a.tenant + "|" + a.store1);
        assertThat(jobsOf(owner, claimedByB)).as("every job B's bridge got").containsOnly(b.tenant + "|" + b.store1);
        assertThat(count(owner, """
                SELECT count(*) FROM production.print_job j JOIN production.printer p ON p.id = j.printer_id
                WHERE p.tenant_id IN (?, ?) AND p.store_id IN (?, ?) AND j.status = 'QUEUED'""", a.tenant, b.tenant,
                a.store1, b.store1)).as("nothing of store 1 left behind for either bridge").isZero();
        for (Company c : List.of(a, b)) {
            assertThat(owner.sql("SELECT status || '|' || attempts FROM production.print_job WHERE id = ?")
                    .param(c.store2Job).query(String.class).single())
                    .as("store 2's ticket of %s: never claimed by store 1's bridge", c.label).isEqualTo("QUEUED|0");
        }

        // ---- 4. every route family and the report: only the caller's tenant (A80, A82) ----
        String days = "&from=" + LocalDate.now(ZoneId.of(Installation.ZONE)).minusDays(1) + "&to="
                + LocalDate.now(ZoneId.of(Installation.ZONE)).plusDays(1);
        assertThat(salesOfPrego(a.manager, a.store1, days)).isEqualByComparingTo("300.00");
        assertThat(salesOfPrego(b.manager, b.store1, days)).isEqualByComparingTo("150.00");
        for (Company[] pair : new Company[][] {{a, b}, {b, a}}) {
            Company self = pair[0];
            Company other = pair[1];
            onlyOwn(owner, self, self.admin, BO + "/stores", "organization.store");
            onlyOwn(owner, self, self.admin, BO + "/employees", "identity.employee");
            onlyOwn(owner, self, self.admin, BO + "/roles", "identity.role");
            onlyOwn(owner, self, self.admin, Installation.CATALOG + "/items", "catalog.item");
            onlyOwn(owner, self, self.admin, Installation.CATALOG + "/price-lists?storeId=" + self.store1,
                    "catalog.price_list");
            onlyOwn(owner, self, self.admin, BO + "/fiscal/series", "fiscal.fiscal_series");
            onlyOwn(owner, self, self.admin, BO + "/terminals?storeId=" + self.store1, "organization.terminal");
            onlyOwn(owner, self, self.pos, POS + "/tables?storeId=" + self.store1, "dining.restaurant_table");

            // another tenant's resource by id: 404, as if it did not exist (D179, rule 5)
            self.admin.get(Installation.CATALOG + "/price-lists/" + other.priceList1, 404);
            self.admin.get(BO + "/stores/" + other.store1, 404);
            self.manager.get(Installation.CATALOG + "/price-lists/" + other.priceList1, 404);
            self.pos.get(POS + "/orders/" + other.order, 404);
            // a store id of the other tenant as a filter: answered exactly as a random id (no existence hint):
            // 403 for a store-scoped caller (outside its stores, D42), 404 for an all-stores one
            sameAsRandom(self.manager, BO + "/reports/sales?groupBy=item" + days + "&storeId=", other.store1, 403);
            sameAsRandom(self.admin, BO + "/reports/sales?groupBy=item" + days + "&storeId=", other.store1, 404);
            sameAsRandom(self.admin, BO + "/stores/", other.store1, 404);
            sameAsRandom(self.pos, POS + "/tables?storeId=", other.store1, 403);
            // another store of the same tenant: 403 (D42)
            self.manager.get(Installation.CATALOG + "/price-lists/" + self.priceList1, 200);
            self.manager.get(Installation.CATALOG + "/price-lists/" + self.priceList2, 403);
            self.manager.get(BO + "/reports/sales?storeId=" + self.store2 + days + "&groupBy=item", 403);
            self.pos.get(POS + "/orders/" + self.order, 200);
        }

        // ---- A81: no tenant from the request ----
        JsonNode foreign = problem(a.manager, BO + "/stores", Map.of("X-Tenant-Id", b.tenant.toString()), 403);
        JsonNode random = problem(a.manager, BO + "/stores", Map.of("X-Tenant-Id", UUID.randomUUID().toString()),
                403);
        assertThat(foreign.get("code").asString()).isEqualTo("TENANT_FORBIDDEN");
        assertThat(comparable(foreign)).as("one body for a foreign and a random tenant").isEqualTo(comparable(random));
        UUID family = id(a.admin.post(Installation.CATALOG + "/families", Map.of("code", "FORJ", "name", "Forjada",
                "tenantId", b.tenant.toString()), 201, "a body tenantId naming B"));
        assertThat(owner.sql("SELECT tenant_id FROM catalog.family WHERE id = ?").param(family).query(UUID.class)
                .single()).as("the body's tenantId is ignored").isEqualTo(a.tenant);

        // ---- the accountant: memberships in both, chooses with X-Tenant-Id (D177) ----
        String accountantSubject = "kc-accountant-" + RUN;
        account(owner, a, accountantSubject, a.managerRole, "contabilista");
        account(owner, b, accountantSubject, b.managerRole, "contabilista");
        Browser accountant = new Browser("accountant", port, journey).withFixtures(owner, Instant::now)
                .signedIn(() -> TestJwtFactory.account(accountantSubject).encode());
        assertThat(problem(accountant, BO + "/stores", Map.of(), 400).get("code").asString())
                .isEqualTo("TENANT_SELECTION_REQUIRED");
        Set<UUID> listed = new TreeSet<>();
        accountant.get("/api/v1/auth/tenants", 200).forEach(choice ->
                listed.add(UUID.fromString(choice.get("tenantId").asString())));
        assertThat(listed).as("the accountant's companies, and only them").containsExactlyInAnyOrder(a.tenant,
                b.tenant);
        onlyOwn(owner, a, accountant, Installation.CATALOG + "/items", "catalog.item",
                Map.of("X-Tenant-Id", a.tenant.toString()));
        onlyOwn(owner, b, accountant, Installation.CATALOG + "/items", "catalog.item",
                Map.of("X-Tenant-Id", b.tenant.toString()));
        assertThat(prego(accountant.send("GET", BO + "/reports/sales?storeId=" + b.store1 + days + "&groupBy=item",
                null, Browser.Csrf.OMIT, Map.of("X-Tenant-Id", b.tenant.toString())))).isEqualByComparingTo("150.00");
        assertThat(accountant.send("GET", BO + "/reports/sales?storeId=" + b.store1 + days + "&groupBy=item",
                null, Browser.Csrf.OMIT, Map.of("X-Tenant-Id", a.tenant.toString())).statusCode())
                .as("B's store while working in A").isEqualTo(404);

        // ---- support: a 1 h grant in A, ops-realm token (D183) ----
        String supporter = "ops-" + RUN;
        UUID supportAccount = id(a.admin.post(BO + "/users", Map.of("username", "suporte-" + RUN, "roleIds",
                List.of(a.managerRole), "support", true), 201, "A's support account"));
        a.admin.command(BO + "/support-grants", Map.of("accountId", supportAccount, "subject", supporter,
                "durationMinutes", 60, "reason", "ticket R6"), 201, "A grants support for 1 h");
        Browser support = new Browser("support", port, journey).withFixtures(owner, Instant::now)
                .signedIn(() -> TestJwtFactory.support(supporter, Instant.now()).encode());
        onlyOwn(owner, a, support, Installation.CATALOG + "/items", "catalog.item",
                Map.of("X-Tenant-Id", a.tenant.toString()));
        JsonNode supportInB = problem(support, BO + "/stores", Map.of("X-Tenant-Id", b.tenant.toString()), 403);
        assertThat(supportInB.get("code").asString()).as("support in B").isEqualTo("TENANT_FORBIDDEN");
        assertThat(comparable(supportInB)).as("support in B: as a random tenant")
                .isEqualTo(comparable(problem(support, BO + "/stores",
                        Map.of("X-Tenant-Id", UUID.randomUUID().toString()), 403)));

        // ---- the platform family (D181) ----
        assertThat(problem(a.admin, PLATFORM + "/" + b.tenant + "/suspend", "POST", Map.of("reason", "x"), 403)
                .get("code").asString()).isEqualTo("PLATFORM_FORBIDDEN");
        assertThat(platform.send("GET", BO + "/stores", null, Browser.Csrf.OMIT,
                Map.of("X-Tenant-Id", a.tenant.toString())).statusCode())
                .as("a platform token reaches no business data").isEqualTo(403);

        // ---- A90: B suspended mid-test ----
        platform.post(PLATFORM + "/" + b.tenant + "/suspend", Map.of("reason", "R6 acceptance"), 200, "suspend B");
        OffsetDateTime suspended = owner.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        assertThat(problem(b.manager, BO + "/stores", Map.of(), 403).get("code").asString())
                .isEqualTo("TENANT_SUSPENDED");
        assertThat(problem(b.admin, BO + "/stores", Map.of(), 403).get("code").asString())
                .isEqualTo("TENANT_SUSPENDED");
        assertThat(problem(b.pos, POS + "/tables?storeId=" + b.store1, Map.of(), 403).get("code").asString())
                .isEqualTo("TENANT_SUSPENDED");
        assertThat(b.bridge.send("GET", "/api/v1/bridge/jobs", null, Browser.Csrf.OMIT, Map.of()).statusCode())
                .as("B's bridge").isEqualTo(403);
        assertThat(problem(accountant, BO + "/stores", Map.of("X-Tenant-Id", b.tenant.toString()), 403).get("code")
                .asString()).isEqualTo("TENANT_SUSPENDED");
        a.manager.get(Installation.CATALOG + "/price-lists/" + a.priceList1, 200);
        a.pos.get(POS + "/tables?storeId=" + a.store1, 200);
        onlyOwn(owner, a, accountant, Installation.CATALOG + "/items", "catalog.item",
                Map.of("X-Tenant-Id", a.tenant.toString()));
        // jobs (D185): a run releases its lease at its end (lease_until = its end). A run of A that ended 3 s after
        // the suspension proves the reach-ALL jobs ticked since (PrintWorker every second); no run of B ended later
        // than 2 s after it (a run in flight at the suspension lasts milliseconds), while B's had run before.
        Awaitility.await().atMost(ASYNC).until(() -> count(owner, "SELECT count(*) FROM shared.job_lease "
                + "WHERE tenant_id = ? AND lease_until > CAST(? AS timestamptz) + interval '3 seconds'",
                a.tenant, suspended) > 0);
        assertThat(count(owner, "SELECT count(*) FROM shared.job_lease WHERE tenant_id = ? "
                + "AND lease_until > CAST(? AS timestamptz) + interval '2 seconds'", b.tenant, suspended))
                .as("no run of suspended B after its suspension").isZero();
        assertThat(count(owner, "SELECT count(*) FROM shared.job_lease WHERE tenant_id = ?", b.tenant))
                .as("B's jobs had run while it was active").isPositive();
    }

    // ---- set-up ----

    /** D181: one platform command; the event chain ends with the administrator's membership and the tenant ACTIVE. */
    private Company onboard(Browser platform, JdbcClient owner, String label) throws Exception {
        Company company = new Company(label);
        String code = "r6-" + label.toLowerCase() + "-" + RUN;
        String email = "admin@" + code + ".example";
        company.tenant = id(platform.post(PLATFORM, Map.of("code", code, "legalName", "Restaurante " + label + " Lda",
                "taxId", "400000002", "country", "MZ", "currency", "MZN", "firstStoreName", "Loja Central",
                "adminEmail", email), 202, "onboard " + label));
        Awaitility.await().atMost(ASYNC).until(() -> "ACTIVE".equals(owner.sql(
                "SELECT status FROM tenancy.tenant WHERE id = ?").param(company.tenant).query(String.class).single()));
        String subject = "user-" + email; // RecordingIdentityProvider's invited user
        company.admin = new Browser("admin " + label, port, journey).withFixtures(owner, Instant::now)
                .signedIn(() -> TestJwtFactory.account(subject).encode());
        return company;
    }

    /** The same codes in every tenant. */
    private void setUp(Company c, JdbcClient owner) throws Exception {
        Browser office = c.admin;
        c.managerRole = role(office, owner, Installation.MANAGER_ROLE);
        c.cashierRole = role(office, owner, Installation.CASHIER_ROLE);
        c.store1 = Installation.store(office, "1");
        c.store2 = Installation.store(office, "2");
        UUID area = id(office.post(BO + "/areas", Map.of("storeId", c.store1, "code", "SALA", "name", "Sala"), 201));
        c.table = id(office.post(BO + "/tables", Map.of("storeId", c.store1, "areaId", area, "code", "M1",
                "seats", 4), 201));
        c.items = Installation.publishCatalog(office, c.store1);
        c.priceList1 = id(office.get(Installation.CATALOG + "/price-lists?storeId=" + c.store1, 200).get(0));
        c.priceList2 = id(office.post(Installation.CATALOG + "/price-lists", Map.of("storeId", c.store2, "name",
                "Sala 2"), 201));
        c.kitchenPrinter = id(office.command(BO + "/printers", Map.of("storeId", c.store1, "code", "COZ", "name",
                "Cozinha", "host", "192.168.1.50", "port", 9100, "codePage", "CP860"), 201, "printer"));
        office.put(BO + "/production-routes", Map.of("storeId", c.store1, "routes", List.of(
                Map.of("destinationCode", "COZINHA", "printerId", c.kitchenPrinter),
                Map.of("destinationCode", "BAR", "printerId", c.kitchenPrinter))), 200, "routes");
        c.printer2 = id(office.command(BO + "/printers", Map.of("storeId", c.store2, "code", "COZ", "name",
                "Cozinha 2", "host", "192.168.2.50", "port", 9100, "codePage", "CP860"), 201, "printer store 2"));
        c.register = id(office.command(BO + "/cash/registers", Map.of("storeId", c.store1, "code", "CX1",
                "name", "Caixa 1"), 201, "register"));
        office.command(BO + "/fiscal/series", Map.of("storeId", c.store1, "code", "FR", "firstNumber", 1), 201,
                "series FR");
        String cashier = Installation.employee(office, c.store1, "CX1", "Carla " + c.label, CASHIER_PIN,
                c.cashierRole);
        c.manager = manager(owner, c, "GA1");

        UUID terminal = id(office.post(BO + "/terminals", Map.of("storeId", c.store1, "code", "POS1", "name",
                "POS1"), 201));
        UUID bridge = id(office.post(BO + "/terminals", Map.of("storeId", c.store1, "code", "BR1", "name", "BR1",
                "kind", "PRINT_BRIDGE"), 201));
        Supplier<String> terminalTokens = enrol(office, terminal, Devices.POS_ENROLMENT);
        c.bridge = new Browser("bridge " + c.label, port, journey).withFixtures(owner, Instant::now)
                .signedIn(enrol(office, bridge, Devices.BRIDGE_ENROLMENT));
        c.pos = new Browser("pos " + c.label, port, journey).withFixtures(owner, Instant::now)
                .enrolled(terminalTokens);
        String session = c.pos.post("/api/v1/auth/pos-login", Map.of("employeeCode", cashier, "pin", CASHIER_PIN),
                200, Browser.Csrf.SEND, c.pos.terminalAuthorization(), "pos-login " + c.label).get("posSession")
                .asString();
        c.pos.posSession(session);
    }

    /**
     * D182/D187 over HTTP: the administrator's one-time code, the device's enrolment with a fresh public key (the
     * devices realm faked: the client's service account is {@code service-account-<clientId>}).
     *
     * @return the device's access tokens
     */
    private Supplier<String> enrol(Browser office, UUID device, String path) throws Exception {
        String code = office.post(BO + "/terminals/" + device + "/enrollment-code", null, 200).get("code").asString();
        Browser anonymous = new Browser("device " + device, port, journey);
        String clientId = anonymous.post(path, Map.of("code", code, "publicJwk", Devices.publicJwk()), 200,
                "enrol " + device).get("clientId").asString();
        String subject = "service-account-" + clientId;
        return () -> TestJwtFactory.device(clientId, subject).encode();
    }

    /** The tenant's copy of the standard role {@code bootstrapRole} (same name, its own id). */
    private UUID role(Browser office, JdbcClient owner, UUID bootstrapRole) throws Exception {
        String name = owner.sql("SELECT name FROM identity.role WHERE id = ?").param(bootstrapRole)
                .query(String.class).single();
        for (JsonNode role : elements(office.get(BO + "/roles", 200))) {
            if (name.equals(role.get("name").asString())) {
                return id(role);
            }
        }
        throw new AssertionError("no role " + name + " in the tenant");
    }

    /** An employee of store 1 with the manager role there only, with a BackOffice account, signed in. */
    private Browser manager(JdbcClient owner, Company c, String code) throws Exception {
        UUID employee = id(c.admin.post(BO + "/employees", Map.of("code", code, "name", "Gerente " + c.label,
                "pin", "9182", "storeId", c.store1, "roleId", c.managerRole), 201));
        UUID account = id(c.admin.post(BO + "/users", Map.of("username", "gerente-" + c.label.toLowerCase() + RUN,
                "employeeId", employee), 201));
        String subject = "kc-manager-" + c.label + "-" + RUN;
        membership(owner, c.tenant, subject, account);
        return new Browser("manager " + c.label, port, journey).withFixtures(owner, Instant::now)
                .signedIn(() -> TestJwtFactory.account(subject).encode());
    }

    /** A tenant-wide account of {@code role} in {@code c}, linked to the Keycloak user {@code subject}. */
    private void account(JdbcClient owner, Company c, String subject, UUID role, String username) throws Exception {
        UUID account = id(c.admin.post(BO + "/users", Map.of("username", username + "-" + c.label.toLowerCase()
                + RUN, "roleIds", List.of(role)), 201));
        membership(owner, c.tenant, subject, account);
    }

    /** What Keycloak's invitation and the identity module leave behind for an account (D177), as the owner. */
    private static void membership(JdbcClient owner, UUID tenant, String subject, UUID account) {
        owner.sql("INSERT INTO tenancy.tenant_membership (id, tenant_id, issuer, subject, kind, principal_id, status, "
                        + "valid_from, created_at) VALUES (?, ?, ?, ?, 'ACCOUNT', ?, 'ACTIVE', "
                        + "now() - interval '1 minute', now())")
                .param(UUID.randomUUID()).param(tenant).param(TestJwtFactory.TENANT_ISSUER).param(subject)
                .param(account).update();
    }

    /** As in the RLS journey: a kitchen warehouse, a steak, a prego's recipe, an opening count of 20 steaks. */
    private void inventorySetUp(Company c) throws Exception {
        Browser office = c.admin;
        UUID kitchen = id(office.command(INV + "/warehouses", Map.of("storeId", c.store1, "code", "COZ", "name",
                "Cozinha"), 201, "warehouse"));
        office.command(INV + "/consumption-routes", Map.of("storeId", c.store1, "warehouseId", kitchen), 201,
                "default route");
        UUID steak = id(office.command(INV + "/stock-items", Map.of("code", "BIFE", "name", "Bife", "baseUnit", "un",
                "kind", "PURCHASED"), 201, "stock item"));
        UUID recipe = id(office.command(INV + "/recipes", Map.of("itemId", c.items.get("PREGO"), "name",
                "Ficha prego"), 201, "recipe"));
        office.command(INV + "/recipes/" + recipe + "/versions", Map.of("yieldQuantity", BigDecimal.ONE,
                "yieldUnit", "un", "ingredients", List.of(Map.of("stockItemId", steak, "quantity", BigDecimal.ONE,
                        "unit", "un", "kind", "INGREDIENT"))), 201, "recipe version");
        office.command(INV + "/recipes/" + recipe + "/versions/1/activate", Map.of(), 200, "activate recipe");
        UUID count = id(office.command(INV + "/counts", Map.of("warehouseId", kitchen, "kind", "OPENING", "scope",
                "ALL"), 201, "opening count"));
        office.command(INV + "/counts/" + count + "/start", Map.of(), 200, "start the opening");
        office.put(INV + "/counts/" + count + "/lines/" + steak, Map.of("countedQuantity", new BigDecimal("20"),
                "countedAt", Instant.now().toString(), "unitCost", new BigDecimal("10.00")), 200, "count 20 steaks");
        office.command(INV + "/counts/" + count + "/submit", Map.of(), 200, "submit the opening");
        c.manager.command(INV + "/counts/" + count + "/approve", Map.of(), 200, "the manager approves");
    }

    /** A cash session, table M1, {@code quantity} pregos confirmed, paid in cash, a document, the order closed. */
    private void sell(Company c, int quantity, String amount) throws Exception {
        Browser pos = c.pos;
        pos.command(POS + "/cash/sessions", Map.of("registerId", c.register, "openingFloat",
                new BigDecimal("100.00")), 201, "open session " + c.label);
        long catalogVersion = pos.get(POS + "/catalog?storeId=" + c.store1, 200).get("version").asLong();
        c.order = id(pos.command(POS + "/tables/" + c.table + "/open", Map.of("guests", 2), 201, "open M1"));
        UUID line = id(pos.command(POS + "/orders/" + c.order + "/lines", Map.of("itemId", c.items.get("PREGO"),
                "quantity", new BigDecimal(quantity)), 201, "draft pregos"));
        c.batch = id(pos.command(POS + "/orders/" + c.order + "/batches", Map.of("orderVersion",
                pos.get(POS + "/orders/" + c.order, 200).get("version").asLong(), "catalogVersion", catalogVersion,
                "lineIds", List.of(line)), 201, "confirm round " + c.label));
        pos.command(POS + "/payment-attempts", Map.of("orderId", c.order, "methodCode", "CASH", "amount",
                new BigDecimal(amount), "tendered", new BigDecimal(amount)), 201, "pay in cash " + c.label);
        JsonNode document = pos.command(POS + "/orders/" + c.order + "/documents", Map.of(), 201, "document");
        assertThat(document.get("status").asString()).isEqualTo("ISSUED");
        pos.command(POS + "/orders/" + c.order + "/close", Map.of("orderVersion", pos.get(POS + "/orders/"
                + c.order + "/settlement", 200).get("orderVersion").asLong()), 200, "close order " + c.label);
    }

    // ---- reads and assertions ----

    /** Every job the bridge gets until none is due (one per claim), each reported as accepted. */
    private static List<UUID> drain(Browser bridge) throws Exception {
        List<UUID> claimed = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            HttpResponse<String> answer = bridge.send("GET", "/api/v1/bridge/jobs", null, Browser.Csrf.OMIT,
                    Map.of());
            bridge.record(bridge.name() + ": claim", answer);
            if (answer.statusCode() == 204) {
                return claimed;
            }
            assertThat(answer.statusCode()).as("claim: %s", answer.body()).isEqualTo(200);
            for (JsonNode job : Browser.JSON.readTree(answer.body())) {
                UUID job1 = id(job);
                claimed.add(job1);
                bridge.post("/api/v1/bridge/jobs/" + job1 + "/outcome", Map.of("outcome", "ACCEPTED",
                        "paperNearEnd", false), 204, "printed");
            }
        }
        throw new AssertionError("the bridge kept getting jobs");
    }

    private static List<String> jobsOf(JdbcClient owner, List<UUID> jobs) {
        List<String> rows = new ArrayList<>();
        for (UUID job : jobs) {
            rows.add(owner.sql("SELECT p.tenant_id || '|' || p.store_id FROM production.print_job j "
                    + "JOIN production.printer p ON p.id = j.printer_id WHERE j.id = ?").param(job)
                    .query(String.class).single());
        }
        return rows;
    }

    /** {@code path} lists, for {@code caller} of {@code self}, some rows of {@code table}, all of them {@code self}'s. */
    private void onlyOwn(JdbcClient owner, Company self, Browser caller, String path, String table) throws Exception {
        onlyOwn(owner, self, caller, path, table, Map.of());
    }

    /** {@link #onlyOwn} with request {@code headers} (a tenant selection). */
    private void onlyOwn(JdbcClient owner, Company self, Browser caller, String path, String table,
                         Map<String, String> headers) throws Exception {
        List<UUID> listed = ids(caller.send("GET", path, null, Browser.Csrf.OMIT, headers));
        assertThat(listed).as("%s lists something for %s", path, self.label).isNotEmpty();
        for (UUID row : listed) {
            assertThat(owner.sql("SELECT tenant_id FROM " + table + " WHERE id = ?").param(row).query(UUID.class)
                    .single()).as("%s row %s listed to %s", table, row, self.label).isEqualTo(self.tenant);
        }
    }

    /** {@code caller} gets {@code expected} for {@code foreign} and the same answer for a random id. */
    private static void sameAsRandom(Browser caller, String path, UUID foreign, int expected) throws Exception {
        JsonNode forForeign = problem(caller, path + foreign, Map.of(), expected);
        JsonNode forRandom = problem(caller, path + UUID.randomUUID(), Map.of(), expected);
        assertThat(comparable(forForeign, "detail")).as("%s: foreign as random", path)
                .isEqualTo(comparable(forRandom, "detail"));
    }

    private BigDecimal salesOfPrego(Browser manager, UUID store, String days) throws Exception {
        String path = BO + "/reports/sales?storeId=" + store + days + "&groupBy=item";
        Awaitility.await().atMost(ASYNC).until(() -> manager.get(path, 200).get("pendingFacts").asLong() == 0);
        return prego(manager.send("GET", path, null, Browser.Csrf.OMIT, Map.of()));
    }

    private static BigDecimal prego(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode rows = Browser.JSON.readTree(response.body()).get("rows");
        assertThat(rows).singleElement().satisfies(row -> assertThat(row.get("key").asString()).isEqualTo("PREGO"));
        return new BigDecimal(rows.get(0).get("gross").get("amount").asString());
    }

    private static List<UUID> ids(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s answered %s", response.request().uri(), response.body())
                .isEqualTo(200);
        List<UUID> ids = new ArrayList<>();
        elements(Browser.JSON.readTree(response.body())).forEach(node -> ids.add(id(node)));
        return ids;
    }

    /** The elements of a list answer: a JSON array, a page's {@code content}, or the tables of a floor map. */
    private static List<JsonNode> elements(JsonNode body) {
        if (body.has("areas")) {
            List<JsonNode> tables = new ArrayList<>();
            body.get("areas").forEach(area -> area.get("tables").forEach(tables::add));
            return tables;
        }
        JsonNode list = body.isArray() ? body : body.get("content");
        assertThat(list).as("a list answer: %s", body).isNotNull();
        List<JsonNode> elements = new ArrayList<>();
        list.forEach(elements::add);
        return elements;
    }

    private static JsonNode problem(Browser caller, String path, Map<String, String> headers, int expected)
            throws Exception {
        return problem(caller, path, "GET", null, headers, expected);
    }

    private static JsonNode problem(Browser caller, String path, String method, Object body, int expected)
            throws Exception {
        return problem(caller, path, method, body, Map.of(), expected);
    }

    private static JsonNode problem(Browser caller, String path, String method, Object body,
                                    Map<String, String> headers, int expected) throws Exception {
        HttpResponse<String> response = caller.send(method, path, body, Browser.Csrf.SEND, headers);
        caller.record(caller.name() + ": " + headers, response);
        assertThat(response.statusCode()).as("%s %s (%s): %s", method, path, caller.name(), response.body())
                .isEqualTo(expected);
        return Browser.JSON.readTree(response.body());
    }

    /** A problem body without what differs per request (instance, trace, time) and the {@code also} fields. */
    private static JsonNode comparable(JsonNode problem, String... also) {
        ObjectNode copy = (ObjectNode) problem.deepCopy();
        copy.remove(List.of("instance", "traceId", "timestamp", "requestId"));
        copy.remove(List.of(also));
        return copy;
    }

    private static TestJwtFactory operatorToken() {
        return TestJwtFactory.support("operator-" + RUN, Instant.now()).claim("realm_access",
                Map.of("roles", List.of("platform-operator")));
    }

    private static long count(JdbcClient owner, String sql, Object... params) {
        JdbcClient.StatementSpec statement = owner.sql(sql);
        for (Object param : params) {
            statement = statement.param(param);
        }
        return statement.query(Long.class).single();
    }
}
