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
 * §14 #4, D68 amended, D70 (the pure half of SplitPreservesTotalsTest): random orders (whole and 3-decimal
 * quantities, five rates), random partition creates and cancels (the guard refuses some, changing nothing), a random
 * subset of partitions invoiced, and the residual document of the rest. Every document passes fiscal's own check
 * for allocated drafts ({@link FiscalDocument#draft}), and together they give each line its net quantity, the
 * order its gross, and each rate its tax.
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

    @Test
    void everySplitPreservesQuantitiesGrossAndTaxPerRateAndPassesFiscal() {
        Random random = new Random(20261105);
        int refused = 0;
        for (int order = 0; order < 20000; order++) {
            refused += splitOneOrder(random);
        }
        assertThat(refused).as("SPLIT_ROUNDING_LIMIT refusals (the guard was exercised)").isPositive();
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
        int refused = 0;
        int steps = 1 + random.nextInt(12);
        for (int step = 0; step < steps; step++) {
            try {
                if (!live.isEmpty() && random.nextInt(5) < 2) {
                    cancel(live, live.get(random.nextInt(live.size())), lines);
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
        for (Line line : lines) {
            LineSplit split = split(line, live);
            for (Piece piece : split.live()) {
                if (!invoiced.get(piece.partitionId())) {
                    residual.add(draftLine(line, piece.quantity(), piece.gross(), piece.tax()));
                }
            }
            if (split.pool().quantity().signum() > 0) {
                residual.add(draftLine(line, split.pool().quantity(), split.pool().gross(), split.pool().tax()));
            }
        }
        if (!residual.isEmpty()) {
            documents.add(residual);
        }
        documents.forEach(SplitPropertyTest::fiscalAccepts);
        assertPreserved(lines, documents);
        return refused;
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

    private static void assertPreserved(List<Line> lines, List<List<DraftLine>> documents) {
        List<DraftLine> all = documents.stream().flatMap(List::stream).toList();
        for (Line line : lines) {
            BigDecimal quantity = all.stream().filter(l -> l.sourceLineId().equals(line.id()))
                    .map(DraftLine::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(quantity).isEqualByComparingTo(line.quantity());
        }
        Money due = lines.stream().map(l -> l.amounts().gross()).reduce(zero(), Money::plus);
        assertThat(sum(all, DraftLine::gross)).isEqualTo(due);
        for (BigDecimal rate : RATES) {
            Money orderTax = lines.stream().filter(l -> l.rate().compareTo(rate) == 0).map(l -> l.amounts().tax())
                    .reduce(zero(), Money::plus);
            Money documentTax = all.stream().filter(l -> l.taxRatePercent().compareTo(rate) == 0)
                    .map(DraftLine::tax).reduce(zero(), Money::plus);
            assertThat(documentTax).as("tax at %s %%", rate).isEqualTo(orderTax);
        }
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
