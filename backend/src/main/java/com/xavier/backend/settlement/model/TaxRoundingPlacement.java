package com.xavier.backend.settlement.model;

import com.xavier.backend.settlement.PartitionAmounts;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Where the tax cent of a settled void goes (D96 rule 2). Pure: no state, no I/O. A drifting order line L (a settled
 * void emptied its pool, so its recomputed tax differs from Σ its LIVE shares by δ) places δ on one line of the
 * residual draft with the same rate: first a line sourced from L itself, then any other line of the rate, each group
 * in draft line order (the residual lists L's shares by partition {@code created_at}, then id). The first candidate
 * whose {@code tax + δ} stays within fiscal's bound for allocated lines ({@link PartitionAmounts#withinFiscalBound})
 * takes it; gross never moves. A candidate's running tax carries over to the next drift, so a line may take several
 * while it stays within the bound. A drift nothing can take is unplaced: a pending fiscal correction (rule 4).
 */
public final class TaxRoundingPlacement {

    private TaxRoundingPlacement() {
    }

    /** A drifting line's δ: its recomputed tax minus Σ its LIVE shares' (signed, never zero). */
    public record Drift(UUID sourceLineId, BigDecimal ratePercent, Money amount) {

        public Drift {
            Objects.requireNonNull(sourceLineId, "sourceLineId");
            Objects.requireNonNull(ratePercent, "ratePercent");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** A residual draft line that may take a drift; {@code partitionId} is {@code null} for a pool line. */
    public record Candidate(int draftLineIndex, UUID sourceLineId, UUID partitionId, BigDecimal quantity,
                            Money unitPrice, BigDecimal ratePercent, Money gross, Money tax) {

        Candidate withTax(Money newTax) {
            return new Candidate(draftLineIndex, sourceLineId, partitionId, quantity, unitPrice, ratePercent, gross,
                    newTax);
        }

        boolean sameRate(Drift drift) {
            return ratePercent.compareTo(drift.ratePercent()) == 0;
        }
    }

    /** Where a drift went: the draft line index, or {@code null} when nothing could absorb it. */
    public record Placement(Drift drift, Integer draftLineIndex) {

        public boolean absorbed() {
            return draftLineIndex != null;
        }
    }

    /** The placement of each drift, in the drifts' order. */
    public static List<Placement> place(List<Drift> drifts, List<Candidate> candidates) {
        Map<Integer, Candidate> running = new HashMap<>();
        candidates.forEach(c -> running.put(c.draftLineIndex(), c));
        List<Candidate> inDraftOrder = candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::draftLineIndex)).toList();
        List<Placement> placements = new ArrayList<>();
        for (Drift drift : drifts) {
            Integer index = inDraftOrder.stream().filter(c -> c.sameRate(drift))
                    .sorted(Comparator.comparing((Candidate c) -> !c.sourceLineId().equals(drift.sourceLineId())))
                    .map(c -> running.get(c.draftLineIndex()))
                    .filter(c -> takes(c, drift.amount()))
                    .map(Candidate::draftLineIndex).findFirst().orElse(null);
            if (index != null) {
                running.compute(index, (i, c) -> c.withTax(c.tax().plus(drift.amount())));
            }
            placements.add(new Placement(drift, index));
        }
        return List.copyOf(placements);
    }

    /** The candidates, in their given order, with every absorbed drift added to the tax of the line that took it. */
    public static List<Candidate> apply(List<Placement> placements, List<Candidate> candidates) {
        Map<Integer, Money> added = new HashMap<>();
        placements.stream().filter(Placement::absorbed)
                .forEach(p -> added.merge(p.draftLineIndex(), p.drift().amount(), Money::plus));
        return candidates.stream().map(c -> added.containsKey(c.draftLineIndex())
                ? c.withTax(c.tax().plus(added.get(c.draftLineIndex()))) : c).toList();
    }

    private static boolean takes(Candidate candidate, Money drift) {
        return PartitionAmounts.withinFiscalBound(candidate.quantity(), candidate.unitPrice(),
                candidate.ratePercent(), candidate.gross(), candidate.tax().plus(drift));
    }
}
