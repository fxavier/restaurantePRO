package com.xavier.backend.customers;

import static com.xavier.backend.customers.CustomersFixtures.mzn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.customers.exception.CustomerRuleException;
import com.xavier.backend.customers.exception.CustomersConflictException;
import com.xavier.backend.organization.MutableClock;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.exception.NotFoundException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The credit ledger behind {@link CustomersApi} (D73): reservations hold the limit until the debit consumes them, a
 * debt payment settles debits FIFO or the named ones, a refund reopens them latest first, a repeated post adds
 * nothing, and the database checks the settlement sums at commit.
 */
@CustomersModuleTest
class CreditLedgerTest {

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

    UUID store;
    UUID customer;

    @BeforeEach
    void aCustomerWithALimitOf500() throws Exception {
        store = UUID.randomUUID();
        customer = fixtures.creditCustomer("500.00");
    }

    @Test
    void aReservationHoldsTheLimitUntilItsDebitConsumesIt() {
        UUID charge = UUID.randomUUID();

        ReservationView reservation = inTx(() -> customers.reserve(customer, mzn("60.00"), charge));
        assertThat(reservation.status()).isEqualTo("ACTIVE");
        assertThat(credit()).isEqualTo(new CreditView(mzn("500.00"), mzn("0.00"), mzn("60.00"), mzn("440.00"), false));

        LedgerEntryView debit = inTx(() -> customers.postDebit(charge, UUID.randomUUID(), store, DAY, mzn("60.00")));
        assertThat(debit.kind()).isEqualTo("DEBIT");
        assertThat(debit.sourceType()).isEqualTo("CREDIT_CHARGE");
        assertThat(debit.sourceId()).isEqualTo(charge);
        assertThat(credit()).isEqualTo(new CreditView(mzn("500.00"), mzn("60.00"), mzn("0.00"), mzn("440.00"), false));
        assertThat(inTx(() -> customers.debtOf(customer))).isEqualTo(mzn("60.00"));
    }

    @Test
    void aRepeatedReserveOrDebitAddsNothing() {
        UUID charge = UUID.randomUUID();
        UUID document = UUID.randomUUID();

        UUID first = inTx(() -> customers.reserve(customer, mzn("60.00"), charge)).id();
        UUID again = inTx(() -> customers.reserve(customer, mzn("60.00"), charge)).id();
        UUID debit = inTx(() -> customers.postDebit(charge, document, store, DAY, mzn("60.00"))).id();
        UUID debitAgain = inTx(() -> customers.postDebit(charge, document, store, DAY, mzn("60.00"))).id();

        assertThat(again).isEqualTo(first);
        assertThat(debitAgain).isEqualTo(debit);
        assertThat(credit().balance()).isEqualTo(mzn("60.00"));
        assertThat(entries()).isEqualTo(1);
    }

    @Test
    void aReleasedReservationFreesTheLimitAndIsNeverDebited() {
        UUID charge = UUID.randomUUID();
        inTx(() -> customers.reserve(customer, mzn("60.00"), charge));

        inTx(() -> {
            customers.release(charge);
            customers.release(charge);
            return null;
        });

        assertThat(credit().available()).isEqualTo(mzn("500.00"));
        assertThatThrownBy(() -> inTx(() -> customers.postDebit(charge, UUID.randomUUID(), store, DAY,
                mzn("60.00")))).isInstanceOf(CustomersConflictException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_NOT_ACTIVE");
        assertThatThrownBy(() -> inTx(() -> {
            customers.release(UUID.randomUUID());
            return null;
        })).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aReservationBeyondTheAvailableCreditIsRefusedWithTheAvailableAmount() {
        sale("450.00");

        assertThatThrownBy(() -> inTx(() -> customers.reserve(customer, mzn("60.00"), UUID.randomUUID())))
                .isInstanceOf(CustomerRuleException.class)
                .hasFieldOrPropertyWithValue("code", "CREDIT_LIMIT_EXCEEDED")
                .satisfies(e -> assertThat(((CustomerRuleException) e).properties())
                        .containsEntry("available", new BigDecimal("50.00")));
        assertThat(inTx(() -> customers.reserve(customer, mzn("50.00"), UUID.randomUUID())).status())
                .isEqualTo("ACTIVE");
    }

    @Test
    void aBlockedAnInactiveOrAnAccountlessCustomerGetsNoReservation() throws Exception {
        UUID noAccount = fixtures.newCustomer(CustomersFixtures.pos(fixtures.newStore()), "No credit", null, null);
        assertRefused(noAccount, "CREDIT_ACCOUNT_MISSING");

        jdbc.sql("UPDATE customers.credit_account SET credit_blocked = true, blocked_reason = 'x', blocked_at = now() "
                + "WHERE customer_id = ?").param(customer).update();
        assertRefused(customer, "CREDIT_BLOCKED");

        jdbc.sql("UPDATE customers.customer SET active = false WHERE id = ?").param(customer).update();
        assertRefused(customer, "CUSTOMER_INACTIVE");
        assertThatThrownBy(() -> inTx(() -> customers.reserve(UUID.randomUUID(), mzn("1.00"), UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aDebtPaymentSettlesTheOldestDebitsFirst() {
        UUID oldest = sale("30.00");
        UUID middle = sale("50.00");
        UUID newest = sale("20.00");

        LedgerEntryView credit = pay("60.00", List.of());

        assertThat(credit.kind()).isEqualTo("CREDIT");
        assertThat(settledOn(oldest)).isEqualByComparingTo("30.00");
        assertThat(settledOn(middle)).isEqualByComparingTo("30.00");
        assertThat(settledOn(newest)).isEqualByComparingTo("0");
        assertThat(credit().balance()).isEqualTo(mzn("40.00"));
    }

    @Test
    void aDebtPaymentSettlesTheNamedDebitsInTheGivenOrder() {
        UUID oldest = sale("30.00");
        UUID middle = sale("50.00");
        UUID newest = sale("20.00");

        pay("40.00", List.of(newest, middle));

        assertThat(settledOn(newest)).isEqualByComparingTo("20.00");
        assertThat(settledOn(middle)).isEqualByComparingTo("20.00");
        assertThat(settledOn(oldest)).isEqualByComparingTo("0");
    }

    @Test
    void aDebtPaymentNeverExceedsTheDebtNorTheNamedDebits() {
        sale("30.00");
        UUID newest = sale("20.00");

        assertThatThrownBy(() -> pay("50.01", List.of())).isInstanceOf(CustomerRuleException.class)
                .hasFieldOrPropertyWithValue("code", "AMOUNT_EXCEEDS_DEBT")
                .satisfies(e -> assertThat(((CustomerRuleException) e).properties())
                        .containsEntry("debt", new BigDecimal("50.00")));
        assertThatThrownBy(() -> pay("25.00", List.of(newest))).isInstanceOf(CustomerRuleException.class)
                .hasFieldOrPropertyWithValue("code", "AMOUNT_EXCEEDS_DEBT");
        assertThatThrownBy(() -> pay("5.00", List.of(UUID.randomUUID()))).isInstanceOf(NotFoundException.class);
        assertThat(credit().balance()).isEqualTo(mzn("50.00"));
    }

    @Test
    void aRepeatedDebtPaymentAddsNothing() {
        sale("30.00");
        UUID payment = UUID.randomUUID();

        UUID first = inTx(() -> customers.postCredit(payment, customer, mzn("10.00"), List.of(), store, DAY)).id();
        UUID again = inTx(() -> customers.postCredit(payment, customer, mzn("10.00"), List.of(), store, DAY)).id();

        assertThat(again).isEqualTo(first);
        assertThat(credit().balance()).isEqualTo(mzn("20.00"));
    }

    @Test
    void aRefundOfADebtPaymentReopensItsDebitsLatestFirst() {
        UUID oldest = sale("30.00");
        UUID newest = sale("50.00");
        UUID payment = UUID.randomUUID();
        inTx(() -> customers.postCredit(payment, customer, mzn("60.00"), List.of(), store, DAY));
        UUID refund = UUID.randomUUID();

        LedgerEntryView reversal = inTx(() -> customers.reverseCredit(refund, payment, mzn("40.00")));
        LedgerEntryView again = inTx(() -> customers.reverseCredit(refund, payment, mzn("40.00")));

        assertThat(reversal.kind()).isEqualTo("DEBIT");
        assertThat(reversal.sourceType()).isEqualTo("REFUND");
        assertThat(again.id()).isEqualTo(reversal.id());
        assertThat(settledOn(newest)).isEqualByComparingTo("0");
        assertThat(settledOn(oldest)).isEqualByComparingTo("20.00");
        assertThat(credit().balance()).isEqualTo(mzn("60.00"));

        assertThatThrownBy(() -> inTx(() -> customers.reverseCredit(UUID.randomUUID(), payment, mzn("20.01"))))
                .isInstanceOf(CustomerRuleException.class)
                .hasFieldOrPropertyWithValue("code", "AMOUNT_EXCEEDS_CREDIT");
        pay("10.00", List.of());
        assertThat(settledOn(oldest)).isEqualByComparingTo("30.00");
    }

    @Test
    void amountsInAnotherCurrencyThanTheAccountsAreRefused() {
        sale("30.00");
        Money euros = Money.of("10.00", Currency.getInstance("EUR"));

        assertThatThrownBy(() -> inTx(() -> customers.reserve(customer, euros, UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTx(() -> customers.postCredit(UUID.randomUUID(), customer, euros, List.of(), store,
                DAY))).isInstanceOf(IllegalArgumentException.class);
        assertThat(credit().reserved()).isEqualTo(mzn("0.00"));
    }

    @Test
    void noNamedDebitsMeansFifo() {
        UUID oldest = sale("30.00");

        inTx(() -> customers.postCredit(UUID.randomUUID(), customer, mzn("10.00"), null, store, DAY));

        assertThat(settledOn(oldest)).isEqualByComparingTo("10.00");
    }

    @Test
    void theDatabaseRefusesACreditThatIsNotFullySettledAtCommit() {
        UUID debit = sale("30.00");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID credit = insertCredit("30.00");
            insertSettlement(credit, debit, "20.00");
        })).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("settles 20");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> insertCredit("5.00")))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("settles 0");
    }

    @Test
    void theDatabaseRefusesSettlingADebitBeyondItsAmount() {
        UUID debit = sale("30.00");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID credit = insertCredit("40.00");
            insertSettlement(credit, debit, "40.00");
        })).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("would be settled by 40");
    }

    @Test
    void theDatabaseRefusesASaleDebitWithoutItsConsumedReservation() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.sql("""
                        INSERT INTO customers.credit_ledger_entry (id, customer_id, kind, amount, currency, source_type,
                            source_id, document_id, store_id, business_date, occurred_at)
                        VALUES (uuidv7(), ?, 'DEBIT', 10, 'MZN', 'CREDIT_CHARGE', ?, ?, ?, ?, now())""")
                .params(customer, UUID.randomUUID(), UUID.randomUUID(), store, DAY).update()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("no consumed reservation");
    }

    private UUID sale(String amount) {
        clock.advance(Duration.ofSeconds(1));
        UUID charge = UUID.randomUUID();
        return inTx(() -> {
            customers.reserve(customer, mzn(amount), charge);
            return customers.postDebit(charge, UUID.randomUUID(), store, DAY, mzn(amount));
        }).id();
    }

    private LedgerEntryView pay(String amount, List<UUID> debits) {
        clock.advance(Duration.ofSeconds(1));
        return inTx(() -> customers.postCredit(UUID.randomUUID(), customer, mzn(amount), debits, store, DAY));
    }

    private void assertRefused(UUID who, String code) {
        assertThatThrownBy(() -> inTx(() -> customers.reserve(who, mzn("1.00"), UUID.randomUUID())))
                .isInstanceOf(CustomerRuleException.class).hasFieldOrPropertyWithValue("code", code);
    }

    private CreditView credit() {
        return customers.credit(customer);
    }

    private long entries() {
        return jdbc.sql("SELECT count(*) FROM customers.credit_ledger_entry WHERE customer_id = ?").param(customer)
                .query(Long.class).single();
    }

    /** Net amount settled on a debit: settlements minus reopenings. */
    private BigDecimal settledOn(UUID debit) {
        return jdbc.sql("""
                        SELECT coalesce(sum(CASE WHEN reversal_entry_id IS NULL THEN amount ELSE -amount END), 0)
                        FROM customers.credit_settlement WHERE debit_entry_id = ?""")
                .param(debit).query(BigDecimal.class).single();
    }

    private UUID insertCredit(String amount) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customers.credit_ledger_entry (id, customer_id, kind, amount, currency, source_type,
                            source_id, store_id, business_date, occurred_at)
                        VALUES (?, ?, 'CREDIT', ?, 'MZN', 'PAYMENT', ?, ?, ?, now())""")
                .params(id, customer, new BigDecimal(amount), UUID.randomUUID(), store, DAY).update();
        return id;
    }

    private void insertSettlement(UUID credit, UUID debit, String amount) {
        jdbc.sql("INSERT INTO customers.credit_settlement (id, credit_entry_id, debit_entry_id, amount) "
                + "VALUES (uuidv7(), ?, ?, ?)").params(credit, debit, new BigDecimal(amount)).update();
    }

    private <T> T inTx(Supplier<T> action) {
        return tx.execute(status -> action.get());
    }

}
