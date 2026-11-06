package com.xavier.backend.settlement.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.settlement.model.TaxRoundingPlacement.Candidate;
import com.xavier.backend.settlement.model.TaxRoundingPlacement.Drift;
import com.xavier.backend.settlement.model.TaxRoundingPlacement.Placement;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * D96 rule 2: each drifting line places its δ on one residual draft line of the same rate, its own lines first, then
 * the others in draft order, the first that stays within fiscal's bound with {@code tax + δ}; none → unplaced.
 */
class TaxRoundingPlacementTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final BigDecimal VAT23 = new BigDecimal("23");
    static final BigDecimal VAT16 = new BigDecimal("16");
    static final UUID L = UUID.fromString("0190a000-0000-7000-8000-00000000000a");
    static final UUID OTHER = UUID.fromString("0190a000-0000-7000-8000-00000000000b");
    static final UUID A = UUID.fromString("0190a000-0000-7000-8000-0000000000a1");
    static final UUID B = UUID.fromString("0190a000-0000-7000-8000-0000000000b1");

    static Money m(String amount) {
        return Money.of(amount, MZN);
    }

    /** 1 x 1.00 at 23 %: gross 1.00, its share's tax as given. */
    static Candidate unit(int index, UUID line, UUID partition, BigDecimal rate, String tax) {
        return new Candidate(index, line, partition, BigDecimal.ONE, m("1.00"), rate, m("1.00"), m(tax));
    }

    @Test
    void theD96ExampleLandsOnTheLineOfTheShareOfA() {
        // 2 x 1.00 at 23 %, one unit in A (share tax 0.37 - 0.19 = 0.18), the other voided: the line's tax is 0.19
        Drift drift = new Drift(L, VAT23, m("0.01"));
        Candidate share = unit(0, L, A, VAT23, "0.18");

        List<Placement> placements = TaxRoundingPlacement.place(List.of(drift), List.of(share));

        assertThat(placements).containsExactly(new Placement(drift, 0));
        Candidate absorbed = TaxRoundingPlacement.apply(placements, List.of(share)).getFirst();
        assertThat(absorbed.tax()).isEqualTo(m("0.19"));
        assertThat(absorbed.gross().minus(absorbed.tax())).isEqualTo(m("0.81"));
    }

    @Test
    void theDriftingLinesOwnLineIsTriedBeforeEarlierLinesOfTheRate() {
        Drift drift = new Drift(L, VAT23, m("0.01"));
        Candidate earlier = unit(0, OTHER, null, VAT23, "0.19");
        Candidate own = unit(1, L, A, VAT23, "0.18");

        assertThat(TaxRoundingPlacement.place(List.of(drift), List.of(earlier, own)))
                .containsExactly(new Placement(drift, 1));
    }

    @Test
    void aLineOfAnotherRateNeverTakesIt() {
        Drift drift = new Drift(L, VAT23, m("0.01"));

        assertThat(TaxRoundingPlacement.place(List.of(drift), List.of(unit(0, OTHER, null, VAT16, "0.14"))))
                .containsExactly(new Placement(drift, null));
    }

    @Test
    void aCandidateTheCentWouldTakeOutOfBoundGivesWayToTheNext() {
        // 1.00 at 23 %: the exact net is 0.81; tax 0.21 leaves net 0.79, at the bound; one more cent is out of it
        Drift drift = new Drift(L, VAT23, m("0.01"));
        Candidate atTheBound = unit(0, L, A, VAT23, "0.21");
        Candidate next = unit(1, OTHER, null, VAT23, "0.19");

        assertThat(TaxRoundingPlacement.place(List.of(drift), List.of(atTheBound, next)))
                .containsExactly(new Placement(drift, 1));
    }

    @Test
    void withNoCandidateWithinTheBoundTheDriftIsUnplaced() {
        Drift drift = new Drift(L, VAT23, m("0.01"));

        assertThat(TaxRoundingPlacement.place(List.of(drift), List.of(unit(0, L, A, VAT23, "0.21"))))
                .containsExactly(new Placement(drift, null));
        assertThat(TaxRoundingPlacement.place(List.of(drift), List.of())).containsExactly(new Placement(drift, null));
    }

    @Test
    void aNegativeDriftLowersTheTaxAndRaisesTheNet() {
        Drift drift = new Drift(L, VAT16, m("-0.01"));
        Candidate share = new Candidate(0, L, A, BigDecimal.ONE, m("12.50"), VAT16, m("12.50"), m("1.73"));

        List<Placement> placements = TaxRoundingPlacement.place(List.of(drift), List.of(share));

        assertThat(placements).containsExactly(new Placement(drift, 0));
        assertThat(TaxRoundingPlacement.apply(placements, List.of(share)).getFirst().tax()).isEqualTo(m("1.72"));
    }

    @Test
    void twoDriftsOnOneCandidateCarryItsRunningTax() {
        Drift first = new Drift(L, VAT23, m("0.01"));
        Drift second = new Drift(OTHER, VAT23, m("0.01"));
        Candidate only = unit(0, L, A, VAT23, "0.19"); // 0.20 (net 0.80) is within; 0.21 (net 0.79) still is
        Candidate tight = unit(0, L, A, VAT23, "0.20");

        assertThat(TaxRoundingPlacement.place(List.of(first, second), List.of(only)))
                .containsExactly(new Placement(first, 0), new Placement(second, 0));
        assertThat(TaxRoundingPlacement.apply(TaxRoundingPlacement.place(List.of(first, second), List.of(only)),
                List.of(only)).getFirst().tax()).isEqualTo(m("0.21"));
        assertThat(TaxRoundingPlacement.place(List.of(first, second), List.of(tight)))
                .as("the second sees the first's cent").containsExactly(new Placement(first, 0),
                        new Placement(second, null));
    }

    @Test
    void theSameInputInAnyCollectionOrderGivesTheSamePlacements() {
        List<Drift> drifts = List.of(new Drift(L, VAT23, m("0.01")), new Drift(OTHER, VAT23, m("-0.01")));
        List<Candidate> candidates = new ArrayList<>(List.of(unit(0, OTHER, null, VAT23, "0.19"),
                unit(1, L, A, VAT23, "0.20"), unit(2, L, B, VAT23, "0.18"), unit(3, OTHER, B, VAT23, "0.19"),
                unit(4, OTHER, null, VAT16, "0.14")));
        List<Placement> expected = TaxRoundingPlacement.place(drifts, candidates);
        Random random = new Random(96);

        for (int i = 0; i < 50; i++) {
            Collections.shuffle(candidates, random);
            assertThat(TaxRoundingPlacement.place(drifts, candidates)).isEqualTo(expected);
        }
        assertThat(expected).extracting(Placement::draftLineIndex).containsExactly(1, 0);
    }
}
