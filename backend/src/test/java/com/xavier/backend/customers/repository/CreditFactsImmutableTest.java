package com.xavier.backend.customers.repository;

import com.xavier.backend.OwnerDatabase;
import static com.xavier.backend.customers.CustomersFixtures.mzn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.customers.CustomersModuleTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Confirmed credit facts are never edited or deleted (spec §8, D73): whatever the application does, PostgreSQL
 * refuses to rewrite or remove a limit change, a ledger entry or a settlement, and a reservation only ever closes
 * once. A correction is a new fact (a refund DEBIT).
 */
@CustomersModuleTest
class CreditFactsImmutableTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CustomersFixtures fixtures;

    @Autowired
    CustomersApi customers;

    @Autowired
    TransactionTemplate tx;

    UUID customer;
    UUID openCharge;

    @BeforeEach
    void aSaleAPaymentAndAnOpenReservation() throws Exception {
        customer = fixtures.creditCustomer("500.00");
        UUID charge = UUID.randomUUID();
        openCharge = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 11, 5);
        tx.executeWithoutResult(status -> {
            customers.reserve(customer, mzn("80.00"), charge);
            customers.postDebit(charge, UUID.randomUUID(), store, day, mzn("80.00"));
            customers.postCredit(UUID.randomUUID(), customer, mzn("30.00"), List.of(), store, day);
            customers.reserve(customer, mzn("10.00"), openCharge);
        });
    }

    @Test
    void aLedgerEntryIsNeitherUpdatedNorDeleted() {
        assertRefused("UPDATE customers.credit_ledger_entry SET amount = 1 WHERE customer_id = ?");
        assertRefused("DELETE FROM customers.credit_ledger_entry WHERE customer_id = ?");
        assertRefused("TRUNCATE customers.credit_ledger_entry CASCADE");
        assertThat(count("SELECT count(*) FROM customers.credit_ledger_entry WHERE customer_id = ?")).isEqualTo(2);
    }

    @Test
    void aSettlementIsNeitherUpdatedNorDeleted() {
        String ofCustomer = " WHERE credit_entry_id IN "
                + "(SELECT id FROM customers.credit_ledger_entry WHERE customer_id = ?)";
        assertRefused("UPDATE customers.credit_settlement SET amount = 1" + ofCustomer);
        assertRefused("DELETE FROM customers.credit_settlement" + ofCustomer);
        assertRefused("TRUNCATE customers.credit_settlement");
    }

    @Test
    void aLimitChangeIsNeitherUpdatedNorDeleted() {
        assertRefused("UPDATE customers.credit_limit_change SET reason = 'other' WHERE customer_id = ?");
        assertRefused("DELETE FROM customers.credit_limit_change WHERE customer_id = ?");
        assertRefused("TRUNCATE customers.credit_limit_change");
    }

    @Test
    void aReservationOnlyClosesOnceAndNothingElseOfItChanges() {
        assertRefused("UPDATE customers.credit_reservation SET amount = 1 WHERE customer_id = ? AND status = 'ACTIVE'");
        assertRefused("UPDATE customers.credit_reservation SET status = 'ACTIVE', closed_at = NULL "
                + "WHERE customer_id = ? AND status = 'CONSUMED'");
        assertRefused("DELETE FROM customers.credit_reservation WHERE customer_id = ?");
        assertRefused("TRUNCATE customers.credit_reservation CASCADE");

        tx.executeWithoutResult(status -> customers.release(openCharge));
        assertRefused("UPDATE customers.credit_reservation SET status = 'CONSUMED' "
                + "WHERE customer_id = ? AND status = 'RELEASED'");
    }

    /** {@code TRUNCATE} as the owner: the pool has no TRUNCATE privilege, so only the trigger can stop the owner. */
    private void assertRefused(String sql) {
        if (sql.startsWith("TRUNCATE")) {
            assertThatThrownBy(() -> OwnerDatabase.shared().rolledBack(owner -> owner.sql(sql).update()))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("not allowed");
            return;
        }
        assertThatThrownBy(() -> {
            var statement = jdbc.sql(sql);
            if (sql.contains("?")) {
                statement = statement.param(customer);
            }
            statement.update();
        }).isInstanceOf(DataAccessException.class).hasMessageContaining("not allowed");
    }

    private long count(String sql) {
        return jdbc.sql(sql).param(customer).query(Long.class).single();
    }
}
