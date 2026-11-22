package com.xavier.backend.production.repository;

import static com.xavier.backend.AppRoleSession.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.orders.BootstrapSeed;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.AppRoleSession;
import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.shared.persistence.StoreScopeViolations.Kind;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A71/A72 for production (D167, D168, D171): as the application role with a hand-written store scope (no
 * {@code AccessPolicy} in front), a store sees and writes only its own printers, routes, terminal printers, tickets
 * and print jobs; no scope moves one to another store; an unset scope fails closed; the D42 probe still finds another
 * store's printer, ticket and job. Seeded as the owner; the jobs are already SENT and the printers on HOLD, so no
 * worker of another test ever picks them up.
 */
class ProductionRowLevelSecurityTest {

    static final List<String> STORE_TABLES = List.of("destination_route", "kitchen_progress", "print_job",
            "printer", "production_ticket", "terminal_printer");

    final UUID storeA = UUID.randomUUID();
    final UUID storeB = UUID.randomUUID();
    final JdbcClient owner = BootstrapSeed.jdbc();

    UUID printerA;
    UUID printerB;
    UUID ticketB;
    UUID jobA;
    UUID jobB;
    AppRoleSession app;

    @BeforeEach
    void seed() throws SQLException {
        printerA = printer(owner, storeA);
        printerB = printer(owner, storeB);
        jobA = job(storeA, printerA, ticket(storeA, printerA));
        ticketB = ticket(storeB, printerB);
        jobB = job(storeB, printerB, ticketB);
        app = AppRoleSession.open();
    }

    @AfterEach
    void close() throws SQLException {
        app.close();
    }

    @Test
    void everyProductionTableIsAStoreTable() {
        RowLevelSecurityCoverageTest.assertModuleClassified("production");

        Map<String, String> policies = owner.sql("""
                        SELECT c.relname, p.polname FROM pg_catalog.pg_policy p
                            JOIN pg_catalog.pg_class c ON c.oid = p.polrelid
                        WHERE c.relnamespace = 'production'::regnamespace AND p.polname <> 'tenant_scope'""")
                .query((row, n) -> Map.entry(row.getString(1), row.getString(2))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(owner.sql("SELECT count(*) FROM pg_catalog.pg_policy p JOIN pg_catalog.pg_class c ON c.oid = p.polrelid "
                + "WHERE c.relnamespace = 'production'::regnamespace AND p.polname = 'tenant_scope' AND NOT p.polpermissive")
                .query(Long.class).single()).as("R6: every production table also carries the restrictive tenant policy")
                .isEqualTo((long) policies.size());
        assertThat(policies.keySet()).containsExactlyInAnyOrderElementsOf(STORE_TABLES);
        assertThat(policies.values()).containsOnly("store_scope");
    }

    @Test
    void aStoreScopeReadsOnlyItsStoresPrintersAndJobs() {
        assertThat(printersSeen(app.stores(storeA))).containsExactly(printerA);
        assertThat(jobsSeen(app.stores(storeA))).containsExactly(jobA);
        assertThat(jobsSeen(app.stores(storeA, storeB))).containsExactlyInAnyOrder(jobA, jobB);
        assertThat(printersSeen(app.allStores())).containsExactlyInAnyOrder(printerA, printerB);
        assertThat(jobsSeen(app.stores())).as("an explicit empty scope sees nothing, without an error").isEmpty();
    }

    /** ADR 0044: a KDS of store A neither sees nor queues store B's rounds. */
    @Test
    void aStoreScopeSeesAndWritesOnlyItsStoresKitchenProgress() {
        UUID ticketA = ticket(storeA, printerA);
        String queue = """
                INSERT INTO production.kitchen_progress (ticket_id, store_id, destination, status, version, queued_at)
                VALUES (?, ?, 'BAR', 'QUEUED', 0, now())""";
        owner.sql(queue).param(ticketA).param(storeA).update();
        owner.sql(queue).param(ticketB).param(storeB).update();
        JdbcClient jdbc = app.stores(storeA).jdbc();

        assertThat(jdbc.sql("SELECT ticket_id FROM production.kitchen_progress WHERE ticket_id IN (?, ?)")
                .param(ticketA).param(ticketB).query(UUID.class).list()).containsExactly(ticketA);
        assertThat(jdbc.sql("UPDATE production.kitchen_progress SET version = version + 1 WHERE ticket_id = ?")
                .param(ticketB).update()).as("another store's round is not there to move").isZero();
        assertRefused(() -> jdbc.sql(queue).param(ticket(storeB, printerB)).param(storeB).update(),
                Kind.WRITE_REFUSED);
    }

    @Test
    void anUnsetScopeFailsClosed() {
        app.unset();

        assertRefused(() -> printersSeen(app), Kind.SCOPE_MISSING);
        assertRefused(() -> jobsSeen(app), Kind.SCOPE_MISSING);
    }

    @Test
    void aStoreScopeWritesNothingOfAnotherStore() {
        JdbcClient jdbc = app.stores(storeA).jdbc();

        assertRefused(() -> printer(jdbc, storeB), Kind.WRITE_REFUSED);
        assertRefused(() -> jdbc.sql("INSERT INTO production.terminal_printer VALUES (gen_random_uuid(), ?, ?)")
                .param(storeB).param(printerB).update(), Kind.WRITE_REFUSED);
        assertThat(jdbc.sql("UPDATE production.print_job SET version = version + 1 WHERE id = ?").param(jobB)
                .update()).as("another store's job is not there to update").isZero();
        assertThat(jdbc.sql("UPDATE production.print_job SET version = version + 1 WHERE id = ?").param(jobA)
                .update()).isOne();
    }

    @Test
    void noScopeMovesAPrinterToAnotherStore() {
        String move = "UPDATE production.printer SET store_id = ? WHERE id = ?";

        assertRefused(() -> app.stores(storeA).jdbc().sql(move).param(storeB).param(printerA).update(),
                Kind.STORE_CHANGE);
        assertRefused(() -> app.allStores().jdbc().sql(move).param(storeB).param(printerA).update(),
                Kind.STORE_CHANGE);
    }

    /** D42 under RLS: hidden from store A's read, yet its store is probed (then 403, not 404). */
    @Test
    void theProbeFindsAnotherStoresPrinterTicketAndJob() {
        StoreProbe probe = new StoreProbe(app.stores(storeA).jdbc());

        assertThat(probe.storeOf("production.printer", printerB)).contains(storeB);
        assertThat(probe.storeOf("production.production_ticket", ticketB)).contains(storeB);
        assertThat(probe.storeOf("production.print_job", jobB)).contains(storeB);
    }

    private List<UUID> printersSeen(AppRoleSession scope) {
        return scope.jdbc().sql("SELECT id FROM production.printer WHERE id IN (?, ?)").param(printerA)
                .param(printerB).query(UUID.class).list();
    }

    private List<UUID> jobsSeen(AppRoleSession scope) {
        return scope.jdbc().sql("SELECT id FROM production.print_job WHERE id IN (?, ?)").param(jobA).param(jobB)
                .query(UUID.class).list();
    }

    static UUID printer(JdbcClient jdbc, UUID store) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO production.printer (id, store_id, code, name, host, port, code_page, active, mode,
                    version, created_at, updated_at)
                VALUES (?, ?, ?, 'Bar', '127.0.0.1', 9100, 'CP860', true, 'HOLD', 0, now(), now())""")
                .param(id).param(store).param("P" + id.toString().substring(0, 8)).update();
        return id;
    }

    private UUID ticket(UUID store, UUID printer) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                INSERT INTO production.production_ticket (id, store_id, source_type, source_id, destination, kind,
                    printer_id, suppressed, content, created_at, routing, content_format)
                VALUES (?, ?, 'BATCH', ?, 'BAR', 'ORIGINAL', ?, false, 'rls', now(), 'ROUTED', 'PLAIN')""")
                .param(id).param(store).param(UUID.randomUUID()).param(printer).update();
        return id;
    }

    private UUID job(UUID store, UUID printer, UUID ticket) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                INSERT INTO production.print_job (id, store_id, ticket_id, printer_id, kind, status, attempts,
                    next_attempt_at, started_at, sent_at, dedupe_key, created_at, version)
                VALUES (?, ?, ?, ?, 'ORIGINAL', 'SENT', 1, now(), now(), now(), ?, now(), 0)""")
                .param(id).param(store).param(ticket).param(printer).param("rls:" + id).update();
        return id;
    }
}
