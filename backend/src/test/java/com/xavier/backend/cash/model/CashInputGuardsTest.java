package com.xavier.backend.cash.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.cash.MethodCount;
import com.xavier.backend.cash.ReceiptCommand;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What settlement passes through CashApi must fit the cash columns (method_code VARCHAR(30), NUMERIC(19, 4)), else
 * the caller's request would end in a 500 at flush: it is refused up front with a 422 code instead.
 */
class CashInputGuardsTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final Money ONE = Money.of("1.00", MZN);

    @Test
    void aMethodCodeIsNotBlankAndFitsThirtyCharacters() {
        for (String bad : new String[] {" ", "M".repeat(31)}) {
            assertRule(() -> new MethodCount(bad, ONE), CashRule.INVALID_METHOD);
            assertRule(() -> new ReceiptCommand(UUID.randomUUID(), bad, ONE, UUID.randomUUID(), UUID.randomUUID()),
                    CashRule.INVALID_METHOD);
        }
        assertThat(new MethodCount("M".repeat(30), ONE).methodCode()).hasSize(30);
    }

    @Test
    void anAmountBeyondFifteenIntegerDigitsIsRefused() {
        Money huge = Money.of("1" + "0".repeat(15) + ".00", MZN);
        CashSession session = CashSessionTest.open();

        assertRule(() -> session.close(List.of(new MethodCount("CASH", huge)), UUID.randomUUID(), Instant.now()),
                CashRule.INVALID_AMOUNT);
        assertRule(() -> CashMovement.receipt(CashSessionTest.open(), "CARD", huge, UUID.randomUUID(),
                UUID.randomUUID(), Instant.now()), CashRule.INVALID_AMOUNT);
    }

    static void assertRule(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, CashRule rule) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CashRuleViolationException.class,
                e -> assertThat(e.rule()).isEqualTo(rule));
    }
}
