package com.xavier.backend.customers;

import static com.xavier.backend.customers.CustomersFixtures.mzn;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.customers.exception.CustomerRuleException;
import com.xavier.backend.organization.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two debt payments of the same customer at once (D73, D75: a debt payment starts at {@code credit_account FOR
 * UPDATE}): they never settle the same open amount twice, and together never more than the debt. Whatever order
 * the two take, the outcome is the same.
 */
@CustomersModuleTest
class CreditPaymentRaceTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 5);

    @Autowired
    CustomersApi customers;

    @Autowired
    CustomersFixtures fixtures;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    UUID customer;
    UUID store;
    UUID older;
    UUID newer;

    @BeforeEach
    void aDebtOf30Then20() throws Exception {
        customer = fixtures.creditCustomer("500.00");
        store = UUID.randomUUID();
        older = sale("30.00");
        newer = sale("20.00");
    }

    @Test
    void twoPaymentsAtOnceSettleEachOpenAmountOnce() throws Exception {
        List<Object> outcomes = together(pay("30.00"), pay("20.00"));

        assertThat(outcomes).allMatch(LedgerEntryView.class::isInstance);
        assertThat(openOn(older)).isEqualByComparingTo("0");
        assertThat(openOn(newer)).isEqualByComparingTo("0");
        assertThat(customers.debtOf(customer)).isEqualTo(mzn("0.00"));
    }

    @Test
    void twoPaymentsAtOnceNeverPayMoreThanTheDebt() throws Exception {
        List<Object> outcomes = together(pay("30.00"), pay("30.00"));

        assertThat(outcomes).filteredOn(LedgerEntryView.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(CustomerRuleException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((CustomerRuleException) e).code()).isEqualTo("AMOUNT_EXCEEDS_DEBT"));
        assertThat(openOn(older)).isEqualByComparingTo("0");
        assertThat(openOn(newer)).isEqualByComparingTo("20.00");
        assertThat(customers.debtOf(customer)).isEqualTo(mzn("20.00"));
    }

    private Callable<Object> pay(String amount) {
        return () -> {
            try {
                return tx.execute(status -> customers.postCredit(UUID.randomUUID(), customer, mzn(amount), List.of(),
                        store, DAY));
            } catch (CustomerRuleException refused) {
                return refused;
            }
        };
    }

    private static List<Object> together(Callable<Object> first, Callable<Object> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Object> a = pool.submit(() -> {
                start.await();
                return first.call();
            });
            Future<Object> b = pool.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    private UUID sale(String amount) {
        clock.advance(Duration.ofSeconds(1));
        UUID charge = UUID.randomUUID();
        return tx.execute(s -> {
            customers.reserve(customer, mzn(amount), charge);
            return customers.postDebit(charge, UUID.randomUUID(), store, DAY, mzn(amount));
        }).id();
    }

    private BigDecimal openOn(UUID debit) {
        return jdbc.sql("""
                        SELECT d.amount - coalesce(sum(CASE WHEN s.reversal_entry_id IS NULL THEN s.amount
                                                            ELSE -s.amount END), 0)
                        FROM customers.credit_ledger_entry d
                        LEFT JOIN customers.credit_settlement s ON s.debit_entry_id = d.id
                        WHERE d.id = ? GROUP BY d.amount""")
                .param(debit).query(BigDecimal.class).single();
    }
}
