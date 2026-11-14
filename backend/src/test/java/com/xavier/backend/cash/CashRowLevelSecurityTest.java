package com.xavier.backend.cash;

import static com.xavier.backend.cash.CashFixtures.account;
import static com.xavier.backend.cash.CashFixtures.idem;
import static com.xavier.backend.cash.CashFixtures.pos;
import static com.xavier.backend.settlement.AppRoleSession.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.settlement.AppRoleSession;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A71, A72 and §14 #9 (closings) for cash (V8011, D167): 6 STORE tables and 2 CHILD tables. Through the seam of
 * {@link AppRoleSession}: a store-A scope reads only store A's sessions and closings, cannot write into store B nor
 * change its closings, and no scope moves a row to another store; an unset scope fails closed; the cash guards
 * ({@code require_open_session}, {@code guard_cash_closing_change}) still hold under a store scope. Through the API,
 * another store's closing, register, session and day answer 403 (D42, {@code shared.store_of}), not 404. Seeded per
 * store once through the POS and BackOffice APIs.
 */
@CashModuleTest
class CashRowLevelSecurityTest {

    record Seed(UUID store, UUID register, UUID closedSession, UUID day, UUID closing, UUID openSession) {
    }

    static Seed a;
    static Seed b;
    static AppRoleSession app;

    @Autowired
    CashFixtures fixtures;

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
        assertThat(AppRoleSession.classification("cash")).containsOnly(
                entry("cash_register", "STORE"), entry("cash_day", "STORE"), entry("cash_day_reopening", "STORE"),
                entry("cash_session", "STORE"), entry("cash_movement", "STORE"), entry("cash_closing", "STORE"),
                entry("cash_session_count", "CHILD:cash.cash_session"),
                entry("cash_closing_line", "CHILD:cash.cash_closing"));
        RowLevelSecurityCoverageTest.assertModuleClassified("cash");
    }

    @Test
    void aStoreScopeReadsOnlyItsStoresSessionsClosingsAndTheirParts() {
        app.stores(a.store());

        assertThat(ids("SELECT id FROM cash.cash_session WHERE id IN (:ids)", a.closedSession(), b.closedSession()))
                .containsExactly(a.closedSession());
        assertThat(ids("SELECT id FROM cash.cash_closing WHERE id IN (:ids)", a.closing(), b.closing()))
                .containsExactly(a.closing());
        assertThat(ids("SELECT DISTINCT closing_id FROM cash.cash_closing_line WHERE closing_id IN (:ids)",
                a.closing(), b.closing())).containsExactly(a.closing());
        assertThat(ids("SELECT DISTINCT session_id FROM cash.cash_session_count WHERE session_id IN (:ids)",
                a.closedSession(), b.closedSession())).containsExactly(a.closedSession());
        assertThat(ids("SELECT DISTINCT session_id FROM cash.cash_movement WHERE session_id IN (:ids)",
                a.closedSession(), b.closedSession())).containsExactly(a.closedSession());

        app.stores(a.store(), b.store());
        assertThat(ids("SELECT id FROM cash.cash_closing WHERE id IN (:ids)", a.closing(), b.closing()))
                .containsExactlyInAnyOrder(a.closing(), b.closing());
        app.stores();
        assertThat(ids("SELECT id FROM cash.cash_closing WHERE id IN (:ids)", a.closing(), b.closing())).isEmpty();
    }

    @Test
    void aStoreScopeCannotWriteIntoAnotherStore() {
        app.stores(a.store());

        assertThatThrownBy(() -> app.jdbc().sql("""
                        INSERT INTO cash.cash_register (id, store_id, code, name, active, version, created_at,
                            updated_at) VALUES (?, ?, ?, 'Caixa', true, 0, now(), now())""")
                .params(UUID.randomUUID(), b.store(), "X" + UUID.randomUUID().toString().substring(0, 8)).update())
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("42501"))
                .hasStackTraceContaining("violates row-level security policy");
        assertThatThrownBy(() -> app.jdbc().sql("""
                        INSERT INTO cash.cash_closing_line (id, closing_id, method_code, expected_amount,
                            counted_amount, difference_amount, currency) VALUES (?, ?, 'MPESA', 0, 0, 0, 'MZN')""")
                .params(UUID.randomUUID(), b.closing()).update())
                .as("a line of another store's closing")
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("42501"));
    }

    /** §14 #9: a store-A scope decides nothing about store B's closing, even with no AccessPolicy in front. */
    @Test
    void aStoreScopeChangesNoneOfAnotherStoresClosings() {
        app.stores(a.store());

        assertThat(app.jdbc().sql("UPDATE cash.cash_closing SET status = 'APPROVED', decided_at = now(), "
                + "decided_by_id = ?, decided_by_kind = 'ACCOUNT' WHERE id = ?").params(UUID.randomUUID(),
                b.closing()).update()).isZero();
        assertThat(app.jdbc().sql("UPDATE cash.cash_closing_line SET justification = 'mine now' WHERE closing_id = ?")
                .param(b.closing()).update()).isZero();
        assertThat(app.jdbc().sql("UPDATE cash.cash_day SET version = version WHERE id = ?").param(b.day()).update())
                .isZero();
        app.allStores();
        assertThat(app.jdbc().sql("SELECT status FROM cash.cash_closing WHERE id = ?").param(b.closing())
                .query(String.class).single()).isEqualTo("SUBMITTED");
    }

    @Test
    void noScopeMovesARowToAnotherStore() {
        for (AppRoleSession scoped : List.of(app.stores(a.store(), b.store()), app.allStores())) {
            assertThatThrownBy(() -> scoped.jdbc().sql("UPDATE cash.cash_register SET store_id = ? WHERE id = ?")
                    .params(b.store(), a.register()).update())
                    .satisfies(error -> assertThat(sqlState(error)).isEqualTo("RS002"));
            assertThatThrownBy(() -> scoped.jdbc().sql("UPDATE cash.cash_closing SET store_id = ? WHERE id = ?")
                    .params(b.store(), a.closing()).update())
                    .satisfies(error -> assertThat(sqlState(error)).isEqualTo("RS002"));
        }
    }

    /** On the tables the seed filled: an empty table never evaluates its policy, so it cannot raise. */
    @Test
    void anUnsetScopeFailsClosed() {
        app.unset();

        for (String table : List.of("cash.cash_register", "cash.cash_day", "cash.cash_session", "cash.cash_movement", "cash.cash_closing", "cash.cash_session_count",
                "cash.cash_closing_line")) {
            assertThatThrownBy(() -> app.jdbc().sql("SELECT count(*) FROM " + table).query(Long.class).single())
                    .as(table).satisfies(error -> assertThat(sqlState(error)).isEqualTo("RS001"));
        }
    }

    @Test
    void theGuardsHoldUnderAStoreScope() {
        app.stores(a.store());

        assertThatThrownBy(() -> movement(a.closedSession(), "CASH_IN", "late"))
                .as("require_open_session").hasStackTraceContaining("is not open");
        movement(a.openSession(), "CASH_IN", "float top-up");
        movement(a.closedSession(), "ADJUSTMENT", "recount");
        assertThatThrownBy(() -> app.jdbc().sql("UPDATE cash.cash_closing SET status = 'DRAFT' WHERE id = ?")
                .param(a.closing()).update())
                .as("guard_cash_closing_change").hasStackTraceContaining("cannot go from SUBMITTED to DRAFT");
    }

    @Test
    void anotherStoresResourcesAnswer403NotFound404() throws Exception {
        Pos operatorOfA = pos(a.store());

        mvc.perform(idem(post("/api/v1/backoffice/cash/closings/" + b.closing() + "/approve"))
                .with(account(a.store(), Permission.CASH_CLOSING_APPROVE))).andExpect(status().isForbidden());
        mvc.perform(idem(put("/api/v1/backoffice/cash/registers/" + b.register()))
                .with(account(a.store(), Permission.CASH_REGISTER_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code": "R1", "name": "Caixa", "active": true, "version": 0}"""))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pos/cash/sessions/" + b.openSession())
                .with(operatorOfA.with(Permission.CASH_OPERATE))).andExpect(status().isForbidden());
        mvc.perform(idem(post("/api/v1/backoffice/cash/days/" + b.day() + "/financial-close"))
                .with(account(a.store(), Permission.CASH_CLOSING_APPROVE))).andExpect(status().isForbidden());
        mvc.perform(idem(post("/api/v1/backoffice/cash/closings/" + UUID.randomUUID() + "/approve"))
                .with(account(a.store(), Permission.CASH_CLOSING_APPROVE))).andExpect(status().isNotFound());
    }

    private Seed seed() throws Exception {
        UUID store = fixtures.newStore();
        UUID register = fixtures.newRegister(store);
        Pos operator = pos(store);
        UUID closed = fixtures.openSession(operator, register, "100.00");
        fixtures.closeSession(operator, closed, "100.00");
        UUID day = fixtures.dayOf(closed);
        UUID closing = fixtures.submittedClosing(operator, register, day);
        UUID open = fixtures.openSession(pos(store), fixtures.newRegister(store), "0");
        return new Seed(store, register, closed, day, closing, open);
    }

    private static List<UUID> ids(String sql, UUID... ids) {
        return app.jdbc().sql(sql).param("ids", List.of(ids)).query(UUID.class).list();
    }

    private static void movement(UUID session, String type, String reason) {
        app.jdbc().sql("""
                        INSERT INTO cash.cash_movement (id, store_id, cash_day_id, session_id, register_id, type,
                            direction, method_code, amount, currency, reason, source_type, source_id,
                            actor_employee_id, occurred_at)
                        SELECT uuidv7(), s.store_id, s.cash_day_id, s.id, s.register_id, :type, 'IN', 'CASH', 1,
                            'MZN', :reason, NULL, NULL, s.operator_employee_id, now()
                        FROM cash.cash_session s WHERE s.id = :session""")
                .param("type", type).param("reason", reason).param("session", session).update();
    }
}
