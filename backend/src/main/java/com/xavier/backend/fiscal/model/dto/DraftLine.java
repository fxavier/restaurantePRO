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
 */
public record DraftLine(UUID sourceLineId, String description, BigDecimal quantity, Money unitPrice,
                        BigDecimal taxRatePercent, Money gross, Money net, Money tax, String taxCode) {

    /** A line whose tax code is not known. */
    public DraftLine(UUID sourceLineId, String description, BigDecimal quantity, Money unitPrice,
                     BigDecimal taxRatePercent, Money gross, Money net, Money tax) {
        this(sourceLineId, description, quantity, unitPrice, taxRatePercent, gross, net, tax, null);
    }
}
