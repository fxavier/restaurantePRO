package com.xavier.backend.cash.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.cash.MethodCount;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.IllegalCashStateException;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** spec §8.3 CashSession: OPEN → CLOSED; the close records the blind count per method. */
class CashSessionTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void closingRecordsOneCountPerMethod() {
        CashSession session = open();
        UUID closer = UUID.randomUUID();

        List<CashSessionCount> counts = session.close(List.of(count("CASH", "150.00"), count("CARD", "40.00")),
                closer, NOW);

        assertThat(session.getStatus()).isEqualTo(CashSessionStatus.CLOSED);
        assertThat(counts).extracting(CashSessionCount::getMethodCode).containsExactly("CASH", "CARD");
        assertThat(counts.getFirst().getAmount()).isEqualTo(Money.of("150.00", MZN));
    }

    @Test
    void aClosedSessionCannotCloseAgainNorTakeMovements() {
        CashSession session = open();
        session.close(List.of(), UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> session.close(List.of(), UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalCashStateException.class);
        assertThatThrownBy(session::requireOpen).isInstanceOfSatisfying(CashRuleViolationException.class,
                e -> assertThat(e.rule()).isEqualTo(CashRule.SESSION_NOT_OPEN));
    }

    @Test
    void aMethodIsCountedOnceAndNeverNegative() {
        assertThatThrownBy(() -> open().close(List.of(count("CASH", "1.00"), count("CASH", "2.00")),
                UUID.randomUUID(), NOW))
                .isInstanceOfSatisfying(CashRuleViolationException.class,
                        e -> assertThat(e.rule()).isEqualTo(CashRule.DUPLICATE_METHOD));
        assertThatThrownBy(() -> open().close(List.of(count("CASH", "-1.00")), UUID.randomUUID(), NOW))
                .isInstanceOfSatisfying(CashRuleViolationException.class,
                        e -> assertThat(e.rule()).isEqualTo(CashRule.INVALID_AMOUNT));
    }

    @Test
    void theOpeningFloatIsNeverNegative() {
        assertThatThrownBy(() -> CashSession.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Money.of("-0.01", MZN), NOW))
                .isInstanceOfSatisfying(CashRuleViolationException.class,
                        e -> assertThat(e.rule()).isEqualTo(CashRule.INVALID_AMOUNT));
    }

    static CashSession open() {
        return CashSession.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Money.of("100.00", MZN), NOW);
    }

    static MethodCount count(String method, String amount) {
        return new MethodCount(method, Money.of(amount, MZN));
    }
}
