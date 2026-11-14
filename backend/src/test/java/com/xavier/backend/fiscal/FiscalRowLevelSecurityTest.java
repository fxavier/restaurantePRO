package com.xavier.backend.fiscal;

import static com.xavier.backend.fiscal.FiscalActors.inStore;
import static com.xavier.backend.fiscal.FiscalActors.pos;
import static com.xavier.backend.fiscal.FiscalFixtures.asEmployee;
import static com.xavier.backend.settlement.AppRoleSession.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.settlement.AppRoleSession;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A71, A72 and §14 #9 (fiscal) for fiscal (V10012, D167): 2 STORE tables and 4 CHILD tables. Through the seam of
 * {@link AppRoleSession}: a store-A scope reads only store A's series and documents, cannot add to or change store B's
 * documents, and no scope moves a document to another store; an unset scope fails closed; the fiscal guards (issued
 * documents and their parts immutable, the series advancing by one) still hold under a store scope. Through the API,
 * another store's document answers 403 (D42, {@code shared.store_of}), not 404. Seeded per store once: a series, an
 * issued document (issued by a POS employee of the store) and a draft.
 */
@FiscalModuleTest
class FiscalRowLevelSecurityTest {

    record Seed(UUID store, UUID series, UUID issued, UUID draft) {
    }

    static Seed a;
    static Seed b;
    static AppRoleSession app;

    @Autowired
    FiscalFixtures fixtures;

    @Autowired
    FiscalApi fiscal;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void seedOnce() throws Exception {
        if (a == null) {
            a = seed();
            b = seed();
            app = AppRoleSession.open();
        }
    }

    @AfterAll
    static void close() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    void theTablesAreClassifiedAsD167Says() {
        assertThat(AppRoleSession.classification("fiscal")).containsOnly(
                entry("fiscal_series", "STORE"), entry("fiscal_document", "STORE"),
                entry("fiscal_document_line", "CHILD:fiscal.fiscal_document"),
                entry("fiscal_document_tax", "CHILD:fiscal.fiscal_document"),
                entry("fiscal_document_payment", "CHILD:fiscal.fiscal_document"),
                entry("fiscal_document_print", "CHILD:fiscal.fiscal_document"));
        RowLevelSecurityCoverageTest.assertModuleClassified("fiscal");
    }

    @Test
    void aStoreScopeReadsOnlyItsStoresSeriesDocumentsAndTheirParts() {
        app.stores(a.store());

        assertThat(ids("SELECT id FROM fiscal.fiscal_series WHERE id IN (:ids)", a.series(), b.series()))
                .containsExactly(a.series());
        assertThat(ids("SELECT id FROM fiscal.fiscal_document WHERE id IN (:ids)", a.issued(), b.issued()))
                .containsExactly(a.issued());
        for (String part : List.of("fiscal_document_line", "fiscal_document_tax", "fiscal_document_payment")) {
            assertThat(ids("SELECT DISTINCT document_id FROM fiscal." + part + " WHERE document_id IN (:ids)",
                    a.issued(), b.issued())).as(part).containsExactly(a.issued());
        }

        app.allStores();
        assertThat(ids("SELECT id FROM fiscal.fiscal_document WHERE id IN (:ids)", a.issued(), b.issued()))
                .containsExactlyInAnyOrder(a.issued(), b.issued());
        app.stores();
        assertThat(ids("SELECT id FROM fiscal.fiscal_document WHERE id IN (:ids)", a.issued(), b.issued())).isEmpty();
    }

    /**
     * §14 #9: a store-A scope adds nothing to store B's fiscal records and changes none of them. The line insert gets
     * past the immutability trigger (it cannot see the ISSUED parent) and is refused by the parent policy.
     */
    @Test
    void aStoreScopeWritesNothingOfAnotherStore() {
        app.stores(a.store());

        assertThatThrownBy(() -> app.jdbc().sql("""
                        INSERT INTO fiscal.fiscal_series (id, store_id, document_type, code, first_number, next_number,
                            active, version, created_at, updated_at)
                        VALUES (?, ?, 'SALE', 'X', 1, 1, false, 0, now(), now())""")
                .params(UUID.randomUUID(), b.store()).update())
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("42501"))
                .hasStackTraceContaining("violates row-level security policy");
        assertThatThrownBy(() -> app.jdbc().sql("""
                        INSERT INTO fiscal.fiscal_document_line
                            (id, document_id, line_no, description, quantity, unit_price, tax_rate, gross, net, tax)
                        VALUES (gen_random_uuid(), ?, 99, 'extra', 1, 1, 0, 1, 1, 0)""")
                .param(b.issued()).update())
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("42501"));
        assertThat(app.jdbc().sql("UPDATE fiscal.fiscal_document SET last_error = 'mine' WHERE id = ?")
                .param(b.draft()).update()).isZero();
        assertThat(app.jdbc().sql("UPDATE fiscal.fiscal_series SET active = false WHERE id = ?").param(b.series())
                .update()).isZero();
    }

    @Test
    void noScopeMovesADocumentToAnotherStore() {
        for (AppRoleSession scoped : List.of(app.stores(a.store(), b.store()), app.allStores())) {
            assertThatThrownBy(() -> scoped.jdbc().sql("UPDATE fiscal.fiscal_document SET store_id = ? WHERE id = ?")
                    .params(b.store(), a.draft()).update())
                    .satisfies(error -> assertThat(sqlState(error)).isEqualTo("RS002"));
        }
    }

    @Test
    void anUnsetScopeFailsClosed() {
        app.unset();

        for (String table : List.of("fiscal.fiscal_series", "fiscal.fiscal_document", "fiscal.fiscal_document_line",
                "fiscal.fiscal_document_tax", "fiscal.fiscal_document_payment")) {
            assertThatThrownBy(() -> app.jdbc().sql("SELECT count(*) FROM " + table).query(Long.class).single())
                    .as(table).satisfies(error -> assertThat(sqlState(error)).isEqualTo("RS001"));
        }
    }

    @Test
    void theFiscalGuardsHoldUnderAStoreScope() {
        app.stores(a.store());

        assertThatThrownBy(() -> app.jdbc().sql("UPDATE fiscal.fiscal_document SET buyer_name = 'Outro' WHERE id = ?")
                .param(a.issued()).update())
                .as("reject_issued_document_change").hasStackTraceContaining("an ISSUED document is immutable");
        assertThatThrownBy(() -> app.jdbc().sql("UPDATE fiscal.fiscal_document_line SET description = 'x' "
                + "WHERE document_id = ?").param(a.issued()).update())
                .as("reject_issued_document_part_change").hasStackTraceContaining("is ISSUED and immutable");
        assertThatThrownBy(() -> app.jdbc().sql("UPDATE fiscal.fiscal_series SET next_number = next_number + 2 "
                + "WHERE id = ?").param(a.series()).update())
                .as("guard_series_change").hasStackTraceContaining("next_number only advances by one");
        assertThat(app.jdbc().sql("UPDATE fiscal.fiscal_document SET last_error = 'retry' WHERE id = ?")
                .param(a.draft()).update()).as("a draft of the scope still changes").isOne();
    }

    @Test
    void anotherStoresDocumentAnswers403NotFound404() throws Exception {
        mvc.perform(get("/api/v1/backoffice/fiscal/documents/" + b.issued())
                        .with(inStore(a.store(), Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pos/documents/" + b.issued())
                        .with(pos(a.store(), UUID.randomUUID(), UUID.randomUUID(), Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/backoffice/fiscal/documents/" + UUID.randomUUID())
                        .with(inStore(a.store(), Permission.FISCAL_DOCUMENT_VIEW)))
                .andExpect(status().isNotFound());
    }

    private Seed seed() throws Exception {
        UUID store = fixtures.newStore();
        UUID series = fixtures.newSeries(store, "A", 1);
        UUID paid = fixtures.newPaidDraft(store);
        UUID employee = UUID.randomUUID();
        UUID issued = asEmployee(store, employee, UUID.randomUUID(), () -> fiscal.issue(paid, employee)).id();
        return new Seed(store, series, issued, fixtures.newDraft(store));
    }

    private static List<UUID> ids(String sql, UUID... ids) {
        return app.jdbc().sql(sql).param("ids", List.of(ids)).query(UUID.class).list();
    }
}
