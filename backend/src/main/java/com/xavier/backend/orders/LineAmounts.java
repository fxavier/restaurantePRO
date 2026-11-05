package com.xavier.backend.orders;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The amounts of one order line (D59, P9: catalog prices are tax-inclusive). The one rounding place per line:
 * {@code gross = unitPrice.times(netQty)} and {@code net = gross.divide(1 + rate/100)}, both HALF_UP to the minor
 * unit ({@link Money}); {@code tax = gross − net}. The divisor is built exactly ({@code 1 + rate·10⁻²}, no
 * {@code MathContext}), because fiscal re-checks this formula per document line and rejects a mismatch. The order due
 * is the sum of line grosses and a document's tax summary the sum of line taxes: totals are never re-rounded.
 *
 * @param gross what the customer pays for the line
 * @param net   the gross without tax
 * @param tax   {@code gross − net}
 */
public record LineAmounts(Money gross, Money net, Money tax) {

    /**
     * @param netQty      the quantity still billable (confirmed minus voided and transferred out), {@code ≥ 0}
     * @param ratePercent the tax rate snapshotted at confirmation as a decimal percent ({@code 16} = 16%),
     *                    {@code ≥ 0}
     * @throws IllegalArgumentException for a negative quantity or rate
     */
    public static LineAmounts of(Money unitPrice, BigDecimal netQty, BigDecimal ratePercent) {
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(netQty, "netQty");
        Objects.requireNonNull(ratePercent, "ratePercent");
        if (netQty.signum() < 0) {
            throw new IllegalArgumentException("A net quantity cannot be negative: " + netQty);
        }
        if (ratePercent.signum() < 0) {
            throw new IllegalArgumentException("A tax rate cannot be negative: " + ratePercent);
        }
        Money gross = unitPrice.times(netQty);
        Money net = gross.divide(BigDecimal.ONE.add(ratePercent.movePointLeft(2)));
        return new LineAmounts(gross, net, gross.minus(net));
    }
}
