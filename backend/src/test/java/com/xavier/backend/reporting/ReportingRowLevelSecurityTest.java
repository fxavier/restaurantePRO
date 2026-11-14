package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StrictStoreScope;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Row-level security on the reporting projections (D167, D168, A71, A72): all 12 tables are STORE. A reporter of store
 * A reads, writes and rebuilds only A's rows, also through SQL that skips {@code AccessPolicy}. Strict: the test thread
 * has no fixture fallback. Rows are seeded by the owner.
 */
@ReportingModuleTest
@StrictStoreScope
class ReportingRowLevelSecurityTest {

    static final String SALES_RECORD = """
            INSERT INTO reporting.sales_record (document_id, store_id, business_date, local_date, local_hour,
                issued_at, terminal_id, on_account, series_code, number, non_fiscal, currency, total_gross, total_net,
                total_tax, projected_at)
            VALUES (?, ?, DATE '2026-01-05', DATE '2026-01-05', 12, now(), gen_random_uuid(), false, 'A', 1, true,
                    'MZN', 30, 25, 5, now())""";

    @Autowired
    JdbcClient jdbc;

    UUID storeA;
    UUID storeB;
    UUID documentA;
    UUID documentB;

    @BeforeEach
    void aSaleInEachOfTwoStores() {
        storeA = UUID.randomUUID();
        storeB = UUID.randomUUID();
        documentA = UUID.randomUUID();
        documentB = UUID.randomUUID();
        OwnerDatabase.shared().jdbc().sql(SALES_RECORD).param(documentA).param(storeA).update();
        OwnerDatabase.shared().jdbc().sql(SALES_RECORD).param(documentB).param(storeB).update();
    }

    @AfterEach
    void noActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theReportingSchemaIsClassified() {
        RowLevelSecurityCoverageTest.assertModuleClassified("reporting");

        assertThat(OwnerDatabase.shared().jdbc().sql("""
                        SELECT table_name::text FROM shared.rls_protected_table
                        WHERE table_name::text LIKE 'reporting.%'""").query(String.class).list())
                .containsExactlyInAnyOrder("reporting.sales_record", "reporting.sales_record_line",
                        "reporting.sales_record_tax", "reporting.payment_record", "reporting.credit_record",
                        "reporting.void_record", "reporting.discount_record", "reporting.offer_cost_record",
                        "reporting.consumption_record", "reporting.consumption_waste_record",
                        "reporting.cost_variance_record", "reporting.rebuild_run");
    }

    @Test
    void aStoreAReporterNeitherReadsNorWritesStoreBsProjections() {
        actor(Permission.REPORT_VIEW.authority(storeA), Permission.REPORT_REBUILD.authority(storeA));

        assertThat(documents()).containsExactly(documentA);
        assertThat(jdbc.sql("DELETE FROM reporting.sales_record WHERE store_id = ?").param(storeB).update())
                .as("a rebuild's clear of another store deletes nothing").isZero();
        assertThatThrownBy(() -> jdbc.sql(SALES_RECORD).param(UUID.randomUUID()).param(storeB).update())
                .rootCause().hasMessageContaining("row-level security");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO reporting.rebuild_run (id, store_id, from_date, to_date, status, started_at)
                VALUES (?, ?, DATE '2026-01-01', DATE '2026-01-31', 'RUNNING', now())""").param(UUID.randomUUID())
                .param(storeB).update()).as("a rebuild of another store").rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void anAllStoresScopeSeesBothButNeverMovesARowToAnotherStore() {
        actor(Permission.REPORT_VIEW.authority());

        assertThat(documents()).containsExactlyInAnyOrder(documentA, documentB);
        assertThatThrownBy(() -> jdbc.sql("UPDATE reporting.sales_record SET store_id = ? WHERE document_id = ?")
                .param(storeB).param(documentA).update()).rootCause().hasMessageContaining("store change refused");
    }

    @Test
    void withNoActorAndNoScopeAReadFailsClosed() {
        assertThatThrownBy(this::documents).rootCause().hasMessageContaining("store scope not set");
    }

    private List<UUID> documents() {
        return jdbc.sql("SELECT document_id FROM reporting.sales_record WHERE document_id IN (?, ?)")
                .param(documentA).param(documentB).query(UUID.class).list();
    }

    private static void actor(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                Actor.account(UUID.randomUUID(), Set.of(authorities)), null, List.of()));
    }
}
