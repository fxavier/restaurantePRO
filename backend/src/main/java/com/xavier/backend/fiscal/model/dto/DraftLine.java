package com.xavier.backend.fiscal.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One document line, copied from a confirmed order line's snapshot (spec §8.4, D59). Prices are tax-inclusive:
 * {@code quantity} is the net billable quantity, {@code gross = unitPrice.times(quantity)},
 * {@code net = gross.divide(1 + taxRatePercent/100)} (both HALF_UP to minor units, {@code Money}) and
 * {@code tax = gross − net}. {@code taxRatePercent} is e.g. 16 for 16 %. Fiscal checks exactly this (422 otherwise)
 * and never re-rounds totals. {@code taxCode} is the line's catalog tax code (its snapshot), named by the document's
 * tax summary of that rate; {@code null} when not known.
 *
 * <p>D101/D107: {@code unitPrice} is the line's <em>effective</em> unit price, the only one the amounts check uses.
 * {@code listUnitPrice} is the catalog price before a discount or an offer ({@code null} when the line has none), never
 * below {@code unitPrice}; {@code discountLabel} (at most 80 characters) names the discount for the printout and
 * needs a {@code listUnitPrice}. Both are kept for printing and for a market adapter (B4); no amount derives from them.
 */
public record DraftLine(UUID sourceLineId, String description, BigDecimal quantity, Money unitPrice,
                        BigDecimal taxRatePercent, Money gross, Money net, Money tax, String taxCode,
                        Money listUnitPrice, String discountLabel) {

    /**
     * A list price equal to the unit price with no label is no discount: it is normalised to {@code null}, so an
     * allocated line's rounding cent never prints as a discount and an undiscounted redraft matches an R3 draft.
     */
    public DraftLine {
        if (listUnitPrice != null && unitPrice != null && (discountLabel == null || discountLabel.isBlank())
                && listUnitPrice.currency().equals(unitPrice.currency())
                && listUnitPrice.amount().compareTo(unitPrice.amount()) == 0) {
            listUnitPrice = null;
            discountLabel = null;
        }
    }

    /** A line with no discount (R3). */
    public DraftLine(UUID sourceLineId, String description, BigDecimal quantity, Money unitPrice,
                     BigDecimal taxRatePercent, Money gross, Money net, Money tax, String taxCode) {
        this(sourceLineId, description, quantity, unitPrice, taxRatePercent, gross, net, tax, taxCode, null, null);
    }

    /** A line whose tax code is not known. */
    public DraftLine(UUID sourceLineId, String description, BigDecimal quantity, Money unitPrice,
                     BigDecimal taxRatePercent, Money gross, Money net, Money tax) {
        this(sourceLineId, description, quantity, unitPrice, taxRatePercent, gross, net, tax, null, null, null);
    }
}
