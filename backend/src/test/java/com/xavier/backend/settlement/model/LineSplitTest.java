package com.xavier.backend.settlement.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.orders.LineAmounts;
import com.xavier.backend.settlement.PartitionAmounts.Share;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.LineSplit.Piece;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One order line split by LIVE partitions (D68 amended): pool, shares, and the SPLIT_ROUNDING_LIMIT guard. */
class LineSplitTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final BigDecimal VAT = new BigDecimal("16");
    static final UUID LINE = UUID.randomUUID();

    @Test
    void aShareLeavesThePoolAtExactlyTheLineAmountsOfWhatIsLeft() {
        LineSplit split = line("1.00", "3", List.of());

        Share share = split.take(new BigDecimal("2"));

        LineSplit after = with(split, share, new BigDecimal("2"));
        LineAmounts left = LineAmounts.of(money("1.00"), BigDecimal.ONE, VAT);
        assertThat(after.pool()).isEqualTo(new LineSplit.Pool(new BigDecimal("1"), left.gross(), left.tax()));
    }

    @Test
    void moreThanThePoolIsAlreadyPartitionedAndMoreThanTheNetExceedsIt() {
        LineSplit split = line("1.00", "3", List.of());
        LineSplit after = with(split, split.take(new BigDecimal("2")), new BigDecimal("2"));

        assertRule(() -> after.take(new BigDecimal("2")), SettlementRule.QUANTITY_ALREADY_PARTITIONED);
        assertRule(() -> after.take(new BigDecimal("4")), SettlementRule.QUANTITY_EXCEEDS_NET);
    }

    @Test
    void aShareAfterACancelThatDriftsBeyondFiscalsBoundIsRefused() {
        // 2.093 x 4.23 at 16 %: take 1.037, take 0.991, cancel the first, take 0.843 -> 3.55 vs round(U q) = 3.57
        LineSplit split = line("4.23", "2.093", List.of());
        UUID first = UUID.randomUUID();
        Share a = split.take(new BigDecimal("1.037"));
        split = with(split, first, a, new BigDecimal("1.037"));
        Share b = split.take(new BigDecimal("0.991"));
        split = with(split, UUID.randomUUID(), b, new BigDecimal("0.991"));
        split.requireCancellable(first);
        LineSplit cancelled = without(split, first);

        assertRule(() -> cancelled.take(new BigDecimal("0.843")), SettlementRule.SPLIT_ROUNDING_LIMIT);
        assertThatCode(() -> cancelled.take(new BigDecimal("1.102"))).as("the whole pool is always fine")
                .doesNotThrowAnyException();
    }

    @Test
    void aVoidOfQuantityInAPartitionIsRefused() {
        LineSplit split = line("1.00", "3", List.of());
        LineSplit after = with(split, split.take(new BigDecimal("2")), new BigDecimal("2"));

        assertRule(() -> after.voidReduces(new BigDecimal("2")), SettlementRule.QUANTITY_PARTITIONED);
    }

    @Test
    void aVoidThatEmptiesThePoolLeavesNoGrossAndAtMostACentOfTaxTheDocumentsDoNotCarry() {
        // 3 x 1.00 @16 %: a partition of 2 takes tax 0.27; voiding the 1 left makes the line 2 x 1.00, tax 0.28
        LineSplit split = line("1.00", "3", List.of());
        Share share = split.take(new BigDecimal("2"));
        LineSplit after = with(split, share, new BigDecimal("2"));

        assertThat(after.voidReduces(BigDecimal.ONE)).isEqualTo(money("1.00"));

        LineAmounts voided = LineAmounts.of(money("1.00"), new BigDecimal("2"), VAT);
        LineSplit emptied = new LineSplit(LINE, money("1.00"), VAT, new BigDecimal("2"), voided.gross(), voided.tax(),
                after.live());
        assertThat(share.tax()).isEqualTo(money("0.27"));
        assertThat(emptied.pool()).isEqualTo(new LineSplit.Pool(BigDecimal.ZERO.setScale(0), money("0.00"),
                money("0.01")));
        assertThat(emptied.taxDrift()).as("the order's tax minus what documents will carry")
                .isEqualTo(money("0.01"));
    }

    static void assertRule(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, SettlementRule rule) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SettlementRuleException.class,
                e -> assertThat(e.rule()).isEqualTo(rule));
    }

    static LineSplit line(String unitPrice, String quantity, List<Piece> live) {
        LineAmounts amounts = LineAmounts.of(money(unitPrice), new BigDecimal(quantity), VAT);
        return new LineSplit(LINE, money(unitPrice), VAT, new BigDecimal(quantity), amounts.gross(), amounts.tax(),
                live);
    }

    static LineSplit with(LineSplit split, Share share, BigDecimal quantity) {
        return with(split, UUID.randomUUID(), share, quantity);
    }

    static LineSplit with(LineSplit split, UUID partition, Share share, BigDecimal quantity) {
        List<Piece> live = new ArrayList<>(split.live());
        live.add(new Piece(partition, quantity, share.gross(), share.tax()));
        return rebuilt(split, live);
    }

    static LineSplit without(LineSplit split, UUID partition) {
        return rebuilt(split, split.live().stream().filter(p -> !p.partitionId().equals(partition)).toList());
    }

    static LineSplit rebuilt(LineSplit split, List<Piece> live) {
        LineAmounts amounts = LineAmounts.of(split.unitPrice(), split.netQty(), split.ratePercent());
        return new LineSplit(LINE, split.unitPrice(), split.ratePercent(), split.netQty(), amounts.gross(),
                amounts.tax(), live);
    }

    static Money money(String amount) {
        return Money.of(amount, MZN);
    }
}
