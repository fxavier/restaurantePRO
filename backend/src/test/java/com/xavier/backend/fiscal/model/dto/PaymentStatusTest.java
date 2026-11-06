package com.xavier.backend.fiscal.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.shared.Money;
import java.util.Currency;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** D97 rule 3: the one status function, over gross G, paid P and credit C, at the boundaries of G = P + C. */
class PaymentStatusTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @ParameterizedTest(name = "G={0} P={1} C={2} -> {3}")
    @CsvSource({
            "50.00, 0.00, 0.00, UNPAID",
            "50.00, 0.01, 0.00, PARTIALLY_PAID",
            "50.00, 49.99, 0.00, PARTIALLY_PAID",
            "50.00, 50.00, 0.00, PAID",
            "50.00, 0.00, 50.00, ON_ACCOUNT",
            "50.00, 0.00, 30.00, MIXED",
            "50.00, 20.00, 30.00, MIXED",
            "50.00, 49.99, 0.01, MIXED",
            "50.00, 0.00, 49.99, MIXED",
            "50.00, 0.00, 0.01, MIXED",
            // a zero total is settled, as FinancialState says (§8.3)
            "0.00, 0.00, 0.00, PAID",
            // the live view may see more than the gross (an overpayment awaiting its refund, D77)
            "50.00, 60.00, 0.00, PAID",
    })
    void theStatusFollowsTheD97Table(String gross, String paid, String credit, PaymentStatus expected) {
        assertThat(PaymentStatus.of(Money.of(gross, MZN), Money.of(paid, MZN), Money.of(credit, MZN)))
                .isEqualTo(expected);
    }
}
