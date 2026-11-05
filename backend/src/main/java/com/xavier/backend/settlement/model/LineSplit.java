package com.xavier.backend.settlement.model;

import com.xavier.backend.orders.LineAmounts;
import com.xavier.backend.settlement.PartitionAmounts;
import com.xavier.backend.settlement.PartitionAmounts.Share;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One order line as its LIVE ITEMS partitions split it (D68, amended in S28): the line's net quantity and amounts,
 * the shares LIVE partitions hold, and the <em>pool</em> no partition holds (line − Σ shares). Every command that
 * changes a share or the line goes through here, so the guard is one rule: after the command, every LIVE share and
 * the pool stay within fiscal's bound for allocated lines ({@link PartitionAmounts#withinFiscalBound}), or the command
 * is refused with {@code SPLIT_ROUNDING_LIMIT}. Pure: the caller holds {@code order_settlement}.
 */
public final class LineSplit {

    private final UUID lineId;
    private final Money unitPrice;
    private final BigDecimal ratePercent;
    private final BigDecimal netQty;
    private final Money gross;
    private final Money tax;
    private final List<Piece> live;

    /**
     * @param netQty the line's billable quantity ({@code 0} once it is fully voided or transferred out)
     * @param gross  the line's gross as orders computes it ({@code LineAmounts.of(unitPrice, netQty, rate)})
     * @param live   the shares LIVE ITEMS partitions hold of this line
     */
    public LineSplit(UUID lineId, Money unitPrice, BigDecimal ratePercent, BigDecimal netQty, Money gross, Money tax,
                     List<Piece> live) {
        this.lineId = Objects.requireNonNull(lineId, "lineId");
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
        this.ratePercent = Objects.requireNonNull(ratePercent, "ratePercent");
        this.netQty = Objects.requireNonNull(netQty, "netQty");
        this.gross = Objects.requireNonNull(gross, "gross");
        this.tax = Objects.requireNonNull(tax, "tax");
        this.live = List.copyOf(live);
    }

    /** One LIVE partition's share of the line. */
    public record Piece(UUID partitionId, BigDecimal quantity, Money gross, Money tax) {
    }

    /** What no LIVE partition holds of the line: quantity, gross and tax (net = gross − tax). */
    public record Pool(BigDecimal quantity, Money gross, Money tax) {

        public Money net() {
            return gross.minus(tax);
        }
    }

    public Pool pool() {
        BigDecimal quantity = netQty;
        Money poolGross = gross;
        Money poolTax = tax;
        for (Piece piece : live) {
            quantity = quantity.subtract(piece.quantity());
            poolGross = poolGross.minus(piece.gross());
            poolTax = poolTax.minus(piece.tax());
        }
        return new Pool(quantity, poolGross, poolTax);
    }

    /**
     * A new share of {@code quantity}, taken from the pool (D68 anchored rule).
     *
     * @throws SettlementRuleException {@code QUANTITY_EXCEEDS_NET}, {@code QUANTITY_ALREADY_PARTITIONED},
     *                                 {@code SPLIT_ROUNDING_LIMIT}
     */
    public Share take(BigDecimal quantity) {
        requirePositive(quantity);
        if (quantity.compareTo(netQty) > 0) {
            throw exceedsNet(quantity);
        }
        Pool pool = pool();
        if (quantity.compareTo(pool.quantity()) > 0) {
            throw SettlementRuleException.quantityAlreadyPartitioned(lineId, pool.quantity());
        }
        Share share = PartitionAmounts.share(unitPrice, ratePercent, pool.quantity(), pool.gross(), pool.tax(),
                quantity);
        requireBound(quantity, share.gross(), share.tax());
        requireBound(pool.quantity().subtract(quantity), pool.gross().minus(share.gross()),
                pool.tax().minus(share.tax()));
        return share;
    }

    /**
     * Whether cancelling {@code partitionId}'s share keeps the pool within the bound.
     *
     * @throws SettlementRuleException {@code SPLIT_ROUNDING_LIMIT}
     */
    public void requireCancellable(UUID partitionId) {
        Pool pool = pool();
        for (Piece piece : live) {
            if (piece.partitionId().equals(partitionId)) {
                requireBound(pool.quantity().add(piece.quantity()), pool.gross().plus(piece.gross()),
                        pool.tax().plus(piece.tax()));
            }
        }
    }

    /**
     * The headroom checks of a void that belong to the line (D71): quantity within the net, not in a LIVE partition,
     * and the pool after the void within the bound. @return how much the line's gross falls
     *
     * @throws SettlementRuleException {@code QUANTITY_EXCEEDS_NET}, {@code QUANTITY_PARTITIONED},
     *                                 {@code SPLIT_ROUNDING_LIMIT}
     */
    public Money voidReduces(BigDecimal quantity) {
        requirePositive(quantity);
        if (quantity.compareTo(netQty) > 0) {
            throw exceedsNet(quantity);
        }
        Pool pool = pool();
        if (quantity.compareTo(pool.quantity()) > 0) {
            throw SettlementRuleException.quantityPartitioned(lineId, pool.quantity());
        }
        LineAmounts after = LineAmounts.of(unitPrice, netQty.subtract(quantity), ratePercent);
        Money reduction = gross.minus(after.gross());
        requireBound(pool.quantity().subtract(quantity), pool.gross().minus(reduction),
                pool.tax().minus(tax.minus(after.tax())));
        return reduction;
    }

    private void requireBound(BigDecimal quantity, Money shareGross, Money shareTax) {
        if (!PartitionAmounts.withinFiscalBound(quantity, unitPrice, ratePercent, shareGross, shareTax)) {
            throw SettlementRuleException.splitRoundingLimit(lineId, quantity, shareGross, shareTax);
        }
    }

    private static void requirePositive(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new SettlementRuleException(SettlementRule.INVALID_AMOUNT, "A quantity must be positive.");
        }
    }

    private SettlementRuleException exceedsNet(BigDecimal quantity) {
        return SettlementRuleException.quantityExceedsNet(lineId, netQty, quantity);
    }

    public UUID lineId() {
        return lineId;
    }

    public Money unitPrice() {
        return unitPrice;
    }

    public BigDecimal ratePercent() {
        return ratePercent;
    }

    public BigDecimal netQty() {
        return netQty;
    }

    public List<Piece> live() {
        return live;
    }
}
