package com.xavier.backend.settlement;

import static com.xavier.backend.settlement.AppRoleSession.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A71, A72 for settlement (V9060, D167): 8 STORE tables, 4 CHILD tables and 2 UNSCOPED ones. Through the seam of
 * {@link AppRoleSession} (the application role with a scope and no {@code AccessPolicy}): a store-A scope reads, writes
 * and updates only store A's rows; no scope moves a row or a child to another store; an unset scope fails closed; and
 * the deferred money triggers ({@code check_payment_allocated}, {@code check_payment_allocation_sum},
 * {@code check_refund_within_payment}) still hold under a store scope. Seeded by the owner, which bypasses RLS.
 */
class SettlementRowLevelSecurityTest {

    static final UUID STORE_A = UUID.randomUUID();
    static final UUID STORE_B = UUID.randomUUID();

    static AppRoleSession app;
    static UUID attemptOfA;
    static UUID paymentOfA;
    static UUID paymentOfB;

    @BeforeAll
    static void seed() {
        attemptOfA = attempt(OwnerDatabase.shared().jdbc(), STORE_A, "10.00");
        OwnerDatabase.shared().tx(owner -> {
            paymentOfA = payment(owner, attemptOfA, STORE_A, "10.00");
            allocation(owner, paymentOfA, "10.00");
            UUID attemptOfB = attempt(owner, STORE_B, "20.00");
            paymentOfB = payment(owner, attemptOfB, STORE_B, "20.00");
            allocation(owner, paymentOfB, "20.00");
        });
        app = AppRoleSession.open();
    }

    @AfterAll
    static void close() {
        app.close();
    }

    @Test
    void theTablesAreClassifiedAsD167Says() {
        assertThat(AppRoleSession.classification("settlement")).containsOnly(
                entry("payment_attempt", "STORE"), entry("payment", "STORE"), entry("refund", "STORE"),
                entry("bill_partition", "STORE"), entry("credit_charge", "STORE"),
                entry("reconciliation_batch", "STORE"), entry("reconciliation_entry", "STORE"),
                entry("tax_rounding_adjustment", "STORE"),
                entry("payment_allocation", "CHILD:settlement.payment"),
                entry("bill_partition_line", "CHILD:settlement.bill_partition"),
                entry("reconciliation_resolution", "CHILD:settlement.reconciliation_entry"),
                entry("tax_correction_resolution", "CHILD:settlement.tax_rounding_adjustment"),
                entry("payment_method", "UNSCOPED"), entry("order_settlement", "UNSCOPED"));
        RowLevelSecurityCoverageTest.assertModuleClassified("settlement");
    }

    @Test
    void aStoreScopeReadsOnlyItsStoresPaymentsAndTheirAllocations() {
        List<UUID> both = List.of(paymentOfA, paymentOfB);

        assertThat(payments(app.stores(STORE_A), both)).containsExactly(paymentOfA);
        assertThat(allocatedPayments(app.stores(STORE_A), both)).containsExactly(paymentOfA);
        assertThat(payments(app.stores(STORE_A, STORE_B), both)).containsExactlyInAnyOrderElementsOf(both);
        assertThat(payments(app.allStores(), both)).containsExactlyInAnyOrderElementsOf(both);
        assertThat(payments(app.stores(), both)).as("an explicit empty scope: nothing, no error").isEmpty();
        assertThat(allocatedPayments(app.stores(), both)).isEmpty();
    }

    @Test
    void anInsertIntoAnotherStoreIsRefused() {
        app.stores(STORE_A);

        assertThatThrownBy(() -> attempt(app.jdbc(), STORE_B, "5.00"))
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("42501"))
                .hasStackTraceContaining("violates row-level security policy");
    }

    @Test
    void anUpdateOfAnotherStoresRowMatchesNothing() {
        UUID attemptOfB = OwnerDatabase.shared().jdbc().sql("SELECT attempt_id FROM settlement.payment WHERE id = ?")
                .param(paymentOfB).query(UUID.class).single();
        app.stores(STORE_A);

        assertThat(app.jdbc().sql("UPDATE settlement.payment_attempt SET updated_at = now() WHERE id = ?")
                .param(attemptOfB).update()).isZero();
    }

    @Test
    void noScopeMovesARowToAnotherStore() {
        for (AppRoleSession scoped : List.of(app.stores(STORE_A, STORE_B), app.allStores())) {
            assertThatThrownBy(() -> scoped.jdbc().sql("UPDATE settlement.payment_attempt SET store_id = ? "
                    + "WHERE id = ?").param(STORE_B).param(attemptOfA).update())
                    .satisfies(error -> assertThat(sqlState(error)).isEqualTo("RS002"));
        }
    }

    @Test
    void aChildCannotPointAtAnotherStoresParentNorMoveToAnotherParent() {
        app.stores(STORE_A);
        assertThatThrownBy(() -> allocation(app.jdbc(), paymentOfB, "1.00"))
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("42501"));

        app.allStores();
        assertThatThrownBy(() -> app.jdbc().sql("UPDATE settlement.payment_allocation SET payment_id = ? "
                + "WHERE payment_id = ?").param(paymentOfB).param(paymentOfA).update())
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("RS002"));
    }

    @Test
    void anUnsetScopeFailsClosedButTheUnscopedTablesStayReadable() {
        app.unset();

        assertThatThrownBy(() -> payments(app, List.of(paymentOfA)))
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("RS001"));
        assertThatThrownBy(() -> allocatedPayments(app, List.of(paymentOfA)))
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("RS001"));
        assertThat(app.jdbc().sql("SELECT count(*) FROM settlement.payment_method").query(Long.class).single())
                .isNotNegative();
        assertThat(app.jdbc().sql("SELECT count(*) FROM settlement.order_settlement").query(Long.class).single())
                .isNotNegative();
    }

    @Test
    void theD42ProbeStillSeesAnotherStoresPayment() {
        app.stores(STORE_A);

        assertThat(app.jdbc().sql("SELECT shared.store_of('settlement.payment', ?)").param(paymentOfB)
                .query(UUID.class).single()).isEqualTo(STORE_B);
    }

    @Test
    void theAllocationTriggersHoldUnderAStoreScope() {
        app.stores(STORE_A);
        UUID short1 = attempt(app.jdbc(), STORE_A, "10.00");
        UUID bare = attempt(app.jdbc(), STORE_A, "10.00");
        UUID exact = attempt(app.jdbc(), STORE_A, "10.00");

        assertThatThrownBy(() -> app.inTransaction(() -> allocation(app.jdbc(),
                payment(app.jdbc(), short1, STORE_A, "10.00"), "9.99")))
                .as("check_payment_allocation_sum").hasStackTraceContaining("allocates 9.9900 of 10.0000");
        assertThatThrownBy(() -> app.inTransaction(() -> payment(app.jdbc(), bare, STORE_A, "10.00")))
                .as("check_payment_allocated").hasStackTraceContaining("allocates");
        app.inTransaction(() -> allocation(app.jdbc(), payment(app.jdbc(), exact, STORE_A, "10.00"), "10.00"));
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM settlement.payment WHERE attempt_id = ?")
                .param(exact).query(Long.class).single()).isOne();
    }

    @Test
    void theRefundCeilingHoldsUnderAStoreScope() {
        UUID attempt = attempt(OwnerDatabase.shared().jdbc(), STORE_A, "10.00");
        UUID payment = OwnerDatabase.shared().call(owner -> {
            UUID id = payment(owner, attempt, STORE_A, "10.00");
            allocation(owner, id, "10.00");
            return id;
        });
        app.stores(STORE_A);

        app.inTransaction(() -> refund(app.jdbc(), payment, STORE_A, "6.00"));
        assertThatThrownBy(() -> app.inTransaction(() -> refund(app.jdbc(), payment, STORE_A, "4.01")))
                .as("check_refund_within_payment").hasStackTraceContaining("refunds 10.0100 of 10.0000");
    }

    /**
     * Under a scope the trigger cannot see another store's payment; it must refuse the refund, not read the missing
     * payment as no ceiling at all.
     */
    @Test
    void aRefundOfAPaymentOutsideTheScopeIsRefused() {
        app.stores(STORE_A);

        assertThatThrownBy(() -> app.inTransaction(() -> refund(app.jdbc(), paymentOfB, STORE_A, "1.00")))
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("42501"))
                .hasStackTraceContaining("row-level security");
    }

    private static List<UUID> payments(AppRoleSession scoped, List<UUID> ids) {
        return scoped.jdbc().sql("SELECT id FROM settlement.payment WHERE id IN (:ids)").param("ids", ids)
                .query(UUID.class).list();
    }

    private static List<UUID> allocatedPayments(AppRoleSession scoped, List<UUID> ids) {
        return scoped.jdbc().sql("SELECT payment_id FROM settlement.payment_allocation WHERE payment_id IN (:ids)")
                .param("ids", ids).query(UUID.class).list();
    }

    static UUID attempt(JdbcClient jdbc, UUID store, String amount) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO settlement.payment_attempt (id, order_id, store_id, terminal_id, session_id, method_code,
                    method_kind, amount, tendered, change_given, currency, status, created_by_employee_id, created_at,
                    updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'CASH', 'CASH', ?, ?, 0, 'MZN', 'SUCCEEDED', ?, now(), now(), 0)""")
                .params(id, UUID.randomUUID(), store, UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(amount),
                        new BigDecimal(amount), UUID.randomUUID()).update();
        return id;
    }

    /** The payment of {@code attempt}, for the attempt's order. */
    static UUID payment(JdbcClient jdbc, UUID attempt, UUID store, String amount) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO settlement.payment (id, attempt_id, order_id, store_id, session_id, method_code, amount,
                    currency, status, collected_by_employee_id, confirmed_at)
                SELECT ?, a.id, a.order_id, ?, a.session_id, 'CASH', ?, 'MZN', 'CONFIRMED', ?, now()
                FROM settlement.payment_attempt a WHERE a.id = ?""")
                .params(id, store, new BigDecimal(amount), UUID.randomUUID(), attempt).update();
        return id;
    }

    static void allocation(JdbcClient jdbc, UUID payment, String amount) {
        jdbc.sql("""
                INSERT INTO settlement.payment_allocation (id, payment_id, target_type, target_id, amount, currency,
                    created_at) VALUES (?, ?, 'ORDER', ?, ?, 'MZN', now())""")
                .params(UUID.randomUUID(), payment, UUID.randomUUID(), new BigDecimal(amount)).update();
    }

    static void refund(JdbcClient jdbc, UUID payment, UUID store, String amount) {
        jdbc.sql("""
                INSERT INTO settlement.refund (id, payment_id, store_id, target_type, order_id, method_code, amount,
                    currency, reason, session_id, terminal_id, business_date, operator_employee_id,
                    approver_employee_id, confirmed_at)
                VALUES (?, ?, ?, 'ORDER', ?, 'CASH', ?, 'MZN', 'returned', ?, ?, current_date, ?, ?, now())""")
                .params(UUID.randomUUID(), payment, store, UUID.randomUUID(), new BigDecimal(amount),
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).update();
    }
}
