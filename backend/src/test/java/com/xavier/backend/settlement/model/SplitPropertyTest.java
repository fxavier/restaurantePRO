package com.xavier.backend.settlement.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.DraftLine;
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.orders.LineAmounts;
import com.xavier.backend.organization.model.dto.CompanyProfileView;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.settlement.PartitionAmounts;
import com.xavier.backend.settlement.PartitionAmounts.Share;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.LineSplit.Piece;
import com.xavier.backend.settlement.model.TaxRoundingPlacement.Candidate;
import com.xavier.backend.settlement.model.TaxRoundingPlacement.Drift;
import com.xavier.backend.settlement.model.TaxRoundingPlacement.Placement;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * §14 #4, D68 amended, D70, D71 (the pure half of SplitPreservesTotalsTest): random orders (whole and 3-decimal
 * quantities, five rates), random partition creates, cancels and settled voids of pool quantity (the guard refuses
 * some, changing nothing), a random subset of partitions invoiced, and the residual document of the rest. Every
 * document passes fiscal's own check for allocated drafts ({@link FiscalDocument#draft}); together they give each
 * line its net quantity and the order its gross exactly, and each rate the tax of its shares and pools plus the cents
 * the residual absorbed (D96, {@link TaxRoundingPlacement}). The order's recomputed tax per rate then equals the
 * documents', or differs by exactly the cents no residual line could take (the pending corrections).
 */
class SplitPropertyTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final BigDecimal[] RATES = {BigDecimal.ZERO, new BigDecimal("5"), new BigDecimal("16"),
            new BigDecimal("17"), new BigDecimal("23")};
    static final CompanyProfileView COMPANY = new CompanyProfileView("C", "1", "MZ", MZN, 0);
    static final StoreSummary STORE = new StoreSummary(UUID.randomUUID(), "S", "S", ZoneId.of("Africa/Maputo"),
            LocalTime.of(5, 0), MZN, true, 0);

    record Line(UUID id, Money unitPrice, BigDecimal rate, BigDecimal quantity, LineAmounts amounts) {
    }

    record Partition(UUID id, Map<UUID, Piece> pieces) {
    }

    private int drifts;
    private int absorbed;
    private int pending;

    @Test
    void everySplitWithCancelsAndVoidsPreservesQuantitiesGrossAndDocumentTaxAndPassesFiscal() {
        Random random = new Random(20261105);
        int refused = 0;
        for (int order = 0; order < 20000; order++) {
            refused += splitOneOrder(random);
        }
        assertThat(refused).as("SPLIT_ROUNDING_LIMIT refusals (the guard was exercised)").isPositive();
        assertThat(drifts).as("orders whose voids left a tax drift (the documented case was exercised)").isPositive();
        assertThat(absorbed).as("drifts the residual absorbed (D96)").isPositive();
        assertThat(pending).as("drifts left as pending corrections (D96)").isPositive();
    }

    private int splitOneOrder(Random random) {
        List<Line> lines = new ArrayList<>();
        int count = 1 + random.nextInt(4);
        for (int i = 0; i < count; i++) {
            boolean fractional = random.nextBoolean();
            BigDecimal quantity = fractional ? BigDecimal.valueOf(1 + random.nextInt(9999), 3)
                    : BigDecimal.valueOf(1 + random.nextInt(20));
            Money unit = Money.of(BigDecimal.valueOf(1 + random.nextInt(20000), 2), MZN);
            BigDecimal rate = RATES[random.nextInt(RATES.length)];
            lines.add(new Line(UUID.randomUUID(), unit, rate, quantity, LineAmounts.of(unit, quantity, rate)));
        }
        List<Partition> live = new ArrayList<>();
        java.util.Set<UUID> voided = new java.util.HashSet<>();
        int refused = 0;
        int steps = 1 + random.nextInt(12);
        for (int step = 0; step < steps; step++) {
            try {
                int op = random.nextInt(10);
                if (!live.isEmpty() && op < 3) {
                    cancel(live, live.get(random.nextInt(live.size())), lines);
                } else if (op < 5) {
                    voidSome(live, lines, voided, random);
                } else {
                    create(live, lines, random);
                }
            } catch (SettlementRuleException e) {
                assertThat(e.rule()).isEqualTo(SettlementRule.SPLIT_ROUNDING_LIMIT);
                refused++;
            }
        }
        List<List<DraftLine>> documents = new ArrayList<>();
        List<DraftLine> residual = new ArrayList<>();
        Map<UUID, Boolean> invoiced = new HashMap<>();
        for (Partition partition : live) {
            invoiced.put(partition.id(), random.nextBoolean());
        }
        for (Partition partition : live) {
            if (invoiced.get(partition.id())) {
                documents.add(partition.pieces().entrySet().stream()
                        .map(e -> draftLine(line(lines, e.getKey()), e.getValue().quantity(), e.getValue().gross(),
                                e.getValue().tax())).toList());
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Line line : lines) {
            LineSplit split = split(line, live);
            for (Piece piece : split.live()) {
                if (!invoiced.get(piece.partitionId())) {
                    candidates.add(candidate(candidates.size(), line, piece.partitionId(), piece.quantity(),
                            piece.gross(), piece.tax()));
                }
            }
            if (split.pool().quantity().signum() > 0) {
                candidates.add(candidate(candidates.size(), line, null, split.pool().quantity(), split.pool().gross(),
                        split.pool().tax()));
            }
        }
        // D96: the residual draft absorbs each drift within its rate, or the drift becomes a pending correction
        List<Drift> lineDrifts = new ArrayList<>();
        for (Line line : lines) {
            Money drift = split(line, live).taxDrift();
            if (drift.amount().signum() != 0) {
                lineDrifts.add(new Drift(line.id(), line.rate(), drift));
            }
        }
        List<Placement> placements = TaxRoundingPlacement.place(lineDrifts, candidates);
        for (Candidate candidate : TaxRoundingPlacement.apply(placements, candidates)) {
            residual.add(draftLine(line(lines, candidate.sourceLineId()), candidate.quantity(), candidate.gross(),
                    candidate.tax()));
        }
        if (!residual.isEmpty()) {
            documents.add(residual);
        }
        documents.forEach(SplitPropertyTest::fiscalAccepts);
        absorbed += (int) placements.stream().filter(Placement::absorbed).count();
        pending += (int) placements.stream().filter(p -> !p.absorbed()).count();
        if (assertPreserved(lines, documents, live, voided, placements)) {
            drifts++;
        }
        return refused;
    }

    private static Candidate candidate(int index, Line line, UUID partitionId, BigDecimal quantity, Money gross,
                                       Money tax) {
        return new Candidate(index, line.id(), partitionId, quantity, line.unitPrice(), line.rate(), gross, tax);
    }

    /**
     * A settled void of part of one line's pool: the line becomes {@code LineAmounts.of(unit, net - v, rate)}, the
     * LIVE shares stay. The guard may refuse it (SPLIT_ROUNDING_LIMIT), changing nothing.
     */
    private static void voidSome(List<Partition> live, List<Line> lines, java.util.Set<UUID> voided, Random random) {
        if (lines.isEmpty()) {
            return;
        }
        Line line = lines.get(random.nextInt(lines.size()));
        LineSplit split = split(line, live);
        BigDecimal pool = split.pool().quantity();
        if (pool.signum() == 0) {
            return;
        }
        BigDecimal quantity = random.nextBoolean() ? pool
                : BigDecimal.valueOf(1 + random.nextInt(pool.movePointRight(3).intValueExact()), 3).min(pool);
        if (line.quantity().scale() == 0) {
            quantity = quantity.setScale(0, java.math.RoundingMode.CEILING).min(pool);
        }
        split.voidReduces(quantity);
        BigDecimal left = line.quantity().subtract(quantity);
        lines.remove(line);
        if (left.signum() > 0) {
            lines.add(new Line(line.id(), line.unitPrice(), line.rate(), left,
                    LineAmounts.of(line.unitPrice(), left, line.rate())));
        }
        voided.add(line.id());
    }

    private static void create(List<Partition> live, List<Line> lines, Random random) {
        Map<UUID, Piece> pieces = new HashMap<>();
        UUID id = UUID.randomUUID();
        for (Line line : lines) {
            if (random.nextBoolean()) {
                continue;
            }
            LineSplit split = split(line, live);
            BigDecimal pool = split.pool().quantity();
            if (pool.signum() == 0) {
                continue;
            }
            int units = pool.movePointRight(3).intValueExact();
            BigDecimal quantity = random.nextInt(4) == 0 ? pool
                    : BigDecimal.valueOf(1 + random.nextInt(units), 3).min(pool);
            if (line.quantity().scale() == 0) {
                quantity = quantity.setScale(0, java.math.RoundingMode.CEILING).min(pool);
            }
            Share share = split.take(quantity);
            pieces.put(line.id(), new Piece(id, quantity, share.gross(), share.tax()));
        }
        if (!pieces.isEmpty()) {
            live.add(new Partition(id, pieces));
        }
    }

    private static void cancel(List<Partition> live, Partition partition, List<Line> lines) {
        for (UUID lineId : partition.pieces().keySet()) {
            split(line(lines, lineId), live).requireCancellable(partition.id());
        }
        live.remove(partition);
    }

    private static LineSplit split(Line line, List<Partition> live) {
        List<Piece> pieces = live.stream().map(p -> p.pieces().get(line.id())).filter(java.util.Objects::nonNull)
                .toList();
        return new LineSplit(line.id(), line.unitPrice(), line.rate(), line.quantity(), line.amounts().gross(),
                line.amounts().tax(), pieces);
    }

    private static void fiscalAccepts(List<DraftLine> lines) {
        Money gross = sum(lines, DraftLine::gross);
        Money net = sum(lines, DraftLine::net);
        Money tax = sum(lines, DraftLine::tax);
        DraftCommand command = new DraftCommand(STORE.id(), SourceType.PARTITION, UUID.randomUUID(),
                UUID.randomUUID(), null, null, null, false, lines, gross, net, tax, true);
        assertThatCode(() -> FiscalDocument.draft(command, COMPANY, STORE, Instant.now()))
                .as("fiscal accepts %s", lines).doesNotThrowAnyException();
        lines.forEach(line -> assertThat(PartitionAmounts.withinFiscalBound(line.quantity(), line.unitPrice(),
                line.taxRatePercent(), line.gross(), line.tax())).isTrue());
    }

    /**
     * The documents' tax per rate equals the order's, except for exactly the cents no residual line could take (the
     * pending corrections, D96). @return whether some rate drifted
     */
    private static boolean assertPreserved(List<Line> lines, List<List<DraftLine>> documents, List<Partition> live,
                                           java.util.Set<UUID> voided, List<Placement> placements) {
        boolean drifted = false;
        List<DraftLine> all = documents.stream().flatMap(List::stream).toList();
        for (Line line : lines) {
            BigDecimal quantity = all.stream().filter(l -> l.sourceLineId().equals(line.id()))
                    .map(DraftLine::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(quantity).isEqualByComparingTo(line.quantity());
        }
        Money due = lines.stream().map(l -> l.amounts().gross()).reduce(zero(), Money::plus);
        assertThat(sum(all, DraftLine::gross)).isEqualTo(due);
        for (BigDecimal rate : RATES) {
            List<Line> ofRate = lines.stream().filter(l -> l.rate().compareTo(rate) == 0).toList();
            Money orderTax = ofRate.stream().map(l -> l.amounts().tax()).reduce(zero(), Money::plus);
            Money documentTax = all.stream().filter(l -> l.taxRatePercent().compareTo(rate) == 0)
                    .map(DraftLine::tax).reduce(zero(), Money::plus);
            Money sharesAndPools = zero();
            Money drift = zero();
            int emptied = 0;
            for (Line line : ofRate) {
                LineSplit split = split(line, live);
                sharesAndPools = split.live().stream().map(Piece::tax).reduce(sharesAndPools, Money::plus);
                if (split.pool().quantity().signum() > 0) {
                    sharesAndPools = sharesAndPools.plus(split.pool().tax());
                } else {
                    assertThat(split.pool().gross()).as("an emptied pool keeps no gross").isEqualTo(zero());
                    emptied++;
                }
                if (!voided.contains(line.id())) {
                    assertThat(split.taxDrift()).as("no drift without a void").isEqualTo(zero());
                }
                drift = drift.plus(split.taxDrift());
            }
            Money absorbedAtRate = sumOf(placements, rate, true);
            Money pendingAtRate = sumOf(placements, rate, false);
            assertThat(documentTax).as("documents' tax at %s %% = shares + pools + absorbed", rate)
                    .isEqualTo(sharesAndPools.plus(absorbedAtRate));
            assertThat(orderTax.minus(sharesAndPools)).as("drift at %s %%", rate).isEqualTo(drift);
            assertThat(orderTax.minus(documentTax)).as("order tax - document tax at %s %% = pending", rate)
                    .isEqualTo(pendingAtRate);
            assertThat(drift.amount().abs().movePointRight(2).intValueExact()).isLessThanOrEqualTo(emptied);
            drifted |= drift.amount().signum() != 0;
        }
        return drifted;
    }

    private static Money sumOf(List<Placement> placements, BigDecimal rate, boolean absorbed) {
        return placements.stream().filter(p -> p.absorbed() == absorbed
                        && p.drift().ratePercent().compareTo(rate) == 0)
                .map(p -> p.drift().amount()).reduce(zero(), Money::plus);
    }

    private static DraftLine draftLine(Line line, BigDecimal quantity, Money gross, Money tax) {
        return new DraftLine(line.id(), "Item", quantity, line.unitPrice(), line.rate(), gross, gross.minus(tax),
                tax);
    }

    private static Line line(List<Line> lines, UUID id) {
        return lines.stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow();
    }

    private static Money sum(List<DraftLine> lines, java.util.function.Function<DraftLine, Money> amount) {
        return lines.stream().map(amount).reduce(zero(), Money::plus);
    }

    private static Money zero() {
        return Money.of(BigDecimal.ZERO, MZN);
    }
}
