package com.xavier.backend.promotions.loyalty.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.promotions.EarnLine;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * P45, D111: points = ⌊Σ eligible gross ÷ spend unit⌋ × points per unit, per order. Eligible gross is after
 * discounts (the line's effective gross), only of lines in the version's scope (none = every line), and never of a
 * rewarded line.
 */
class LoyaltyEarnRuleTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    static final UUID BURGER = UUID.fromString("0190a000-0000-7000-8000-00000000b001");
    static final UUID SODA = UUID.fromString("0190a000-0000-7000-8000-00000000b002");
    static final UUID DRINKS = UUID.fromString("0190a000-0000-7000-8000-00000000f001");
    static final UUID MAINS = UUID.fromString("0190a000-0000-7000-8000-00000000f002");

    @Test
    void pointsAreTheWholeSpendUnitsTimesThePointsPerUnit() {
        LoyaltyProgramVersion program = program("10.00", 2, Set.of(), Set.of());

        Earning earning = program.earn(List.of(line(BURGER, MAINS, "25.00", false), line(SODA, DRINKS, "9.99", false)));

        assertThat(earning.eligibleGross()).isEqualTo(mzn("34.99"));
        assertThat(earning.points()).isEqualTo(6);
    }

    @Test
    void lessThanOneSpendUnitEarnsNothing() {
        LoyaltyProgramVersion program = program("10.00", 5, Set.of(), Set.of());

        assertThat(program.earn(List.of(line(BURGER, MAINS, "9.99", false))).points()).isZero();
    }

    @Test
    void onlyLinesInTheScopeEarnByItemOrByFamily() {
        UUID dessert = UUID.randomUUID();
        LoyaltyProgramVersion program = program("1.00", 1, Set.of(BURGER), Set.of(DRINKS));

        Earning earning = program.earn(List.of(line(BURGER, MAINS, "12.00", false),
                line(SODA, DRINKS, "3.00", false), line(dessert, UUID.randomUUID(), "50.00", false)));

        assertThat(earning.eligibleGross()).isEqualTo(mzn("15.00"));
        assertThat(earning.points()).isEqualTo(15);
    }

    @Test
    void aRewardedLineNeverEarnsEvenInScope() {
        LoyaltyProgramVersion program = program("1.00", 1, Set.of(BURGER), Set.of());

        Earning earning = program.earn(List.of(line(BURGER, MAINS, "12.00", true), line(BURGER, MAINS, "8.00", false)));

        assertThat(earning.eligibleGross()).isEqualTo(mzn("8.00"));
        assertThat(earning.points()).isEqualTo(8);
    }

    @Test
    void aLineInAnotherCurrencyIsAProgrammingError() {
        LoyaltyProgramVersion program = program("1.00", 1, Set.of(), Set.of());

        assertThatThrownBy(() -> program.earn(List.of(new EarnLine(BURGER, MAINS,
                Money.of("5.00", Currency.getInstance("EUR")), false))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static LoyaltyProgramVersion program(String spendUnit, int pointsPerUnit, Set<UUID> items, Set<UUID> families) {
        return LoyaltyProgramVersion.draft(1, mzn(spendUnit), pointsPerUnit, items, families, NOW);
    }

    static EarnLine line(UUID item, UUID family, String gross, boolean rewarded) {
        return new EarnLine(item, family, mzn(gross), rewarded);
    }

    static Money mzn(String amount) {
        return Money.of(amount, MZN);
    }
}
