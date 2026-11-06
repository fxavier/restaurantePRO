package com.xavier.backend.orders.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The billed description of a line: what the document prints, within fiscal's 200 characters. */
class LineSnapshotTest {

    static LineSnapshot named(String description, String variantName) {
        return new LineSnapshot("C", description, variantName, Money.of("1.00", Currency.getInstance("MZN")),
                UUID.randomUUID(), "T", BigDecimal.TEN, UUID.randomUUID(), 1, null);
    }

    @Test
    void theBilledDescriptionIsTheNameWithTheVariantInParentheses() {
        assertThat(named("Burger", null).billedDescription()).isEqualTo("Burger");
        assertThat(named("Burger", "Large").billedDescription()).isEqualTo("Burger (Large)");
    }

    @Test
    void aDescriptionLongerThanTheDocumentLimitIsCutWithAnEllipsis() {
        String billed = named("N".repeat(120), "V".repeat(120)).billedDescription();

        assertThat(billed).hasSize(LineSnapshot.MAX_BILLED_DESCRIPTION).endsWith("…").startsWith("N".repeat(120));
    }
}
