package com.xavier.backend.orders;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A confirmed line as settlement bills it and fiscal copies it into a {@code DraftLine} (D59): {@code netQty} is the
 * quantity still billable (confirmed − voided − transferred out, {@code > 0}), the amounts are
 * {@link LineAmounts#of}{@code (unitPrice, netQty, ratePercent)}. {@code description} is the snapshot name, with the
 * variant name in parentheses when there is one, at most 200 characters (fiscal's line limit).
 */
public record BillingLine(UUID lineId, String description, BigDecimal netQty, Money unitPrice, Money gross,
                          Money net, Money tax, String taxCode, BigDecimal ratePercent) {
}
