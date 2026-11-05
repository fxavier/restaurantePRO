package com.xavier.backend.settlement;

import com.xavier.backend.orders.LineAmounts;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The one split rule of bill partitions (D68, amended in S28). Pure: no state, no I/O.
 *
 * <p><strong>Anchored shares.</strong> An order line's <em>pool</em> is what no LIVE ITEMS partition holds: its
 * remaining quantity, gross and tax. A new share of quantity {@code q} leaves the pool at exactly
 * {@code LineAmounts.of(unitPrice, remaining − q, rate)} and takes the difference. The shares and the pool always sum
 * to the line (they telescope), so the documents of a split sum to the order's gross and to its tax per rate (§14 #4).
 * While no cancel or void touched the line since its last share, every share is within 1 minor unit of
 * {@code round(unitPrice × q)} in gross and 1 of {@code round(gross / (1 + rate/100))} in net, and the pool is exact.
 *
 * <p><strong>Fiscal's bound.</strong> Fiscal accepts an allocated line within 1 minor unit of gross and 2 of net
 * ({@code FiscalDocument}, D68 amendment). A cancel returns a share's own rounding to the pool, so after cancels or
 * a void a pool or a new share can drift beyond it; settlement's guard ({@code SPLIT_ROUNDING_LIMIT}) refuses such a
 * command with {@link #withinFiscalBound}, so a drafted document never fails the check. The bound is the same
 * knowledge as fiscal's, kept here because settlement must decide before it drafts.
 */
public final class PartitionAmounts {

    static final int GROSS_UNITS = 1;
    static final int NET_UNITS = 2;
    /** The most tax an emptied pool may keep after a settled void, in minor units. */
    static final int DRIFT_UNITS = 1;

    private PartitionAmounts() {
    }

    /** One partition line's amounts: {@code gross = net + tax}. */
    public record Share(Money gross, Money net, Money tax) {
    }

    /**
     * The share of {@code qty} taken from a line's pool.
     *
     * @param remainingQty   the quantity in no LIVE partition ({@code ≥ qty})
     * @param remainingGross the pool's gross (line gross − Σ LIVE shares)
     * @param remainingTax   the pool's tax
     * @throws IllegalArgumentException for a quantity that is not positive or more than the pool's
     */
    public static Share share(Money unitPrice, BigDecimal ratePercent, BigDecimal remainingQty, Money remainingGross,
                              Money remainingTax, BigDecimal qty) {
        Objects.requireNonNull(qty, "qty");
        if (qty.signum() <= 0 || qty.compareTo(remainingQty) > 0) {
            throw new IllegalArgumentException("A share of " + qty + " does not fit a pool of " + remainingQty);
        }
        LineAmounts left = LineAmounts.of(unitPrice, remainingQty.subtract(qty), ratePercent);
        Money gross = remainingGross.minus(left.gross());
        Money tax = remainingTax.minus(left.tax());
        return new Share(gross, gross.minus(tax), tax);
    }

    /** {@code parts} equal amounts of {@code residual}; leftover minor units go to the lowest part numbers. */
    public static List<Money> equalParts(Money residual, int parts) {
        return residual.allocate(parts);
    }

    /**
     * Whether a line of {@code qty} with these amounts passes fiscal's check for allocated lines: no negative amount,
     * gross within {@value #GROSS_UNITS} minor unit of {@code round(unitPrice × qty)}, net within {@value #NET_UNITS}
     * of {@code round(gross / (1 + rate/100))}. A pool with nothing left ({@code qty = 0}) is never invoiced: it
     * passes only with no gross and at most one minor unit of tax (and net) of a void's rounding, the drift the
     * documents do not carry (the documents' tax per rate is authoritative, D68 amendment).
     */
    public static boolean withinFiscalBound(BigDecimal qty, Money unitPrice, BigDecimal ratePercent, Money gross,
                                            Money tax) {
        Money net = gross.minus(tax);
        if (qty.signum() == 0) {
            return gross.amount().signum() == 0 && units(tax) <= DRIFT_UNITS;
        }
        if (gross.amount().signum() < 0 || net.amount().signum() < 0 || tax.amount().signum() < 0) {
            return false;
        }
        Money exactNet = gross.divide(BigDecimal.ONE.add(ratePercent.movePointLeft(2)));
        return units(gross.minus(unitPrice.times(qty))) <= GROSS_UNITS && units(net.minus(exactNet)) <= NET_UNITS;
    }

    private static int units(Money difference) {
        return difference.amount().abs().movePointRight(difference.currency().getDefaultFractionDigits())
                .intValueExact();
    }
}
