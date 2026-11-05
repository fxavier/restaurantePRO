package com.xavier.backend.orders;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A confirmed line as settlement bills it and fiscal copies it into a {@code DraftLine} (D59): {@code netQty} is the
 * quantity still billable (confirmed − voided − transferred out, {@code > 0}), the amounts are
 * {@link LineAmounts#of}{@code (unitPrice, netQty, ratePercent)}. {@code description} is the snapshot name, with the
 * variant name in parentheses when there is one, at most 200 characters (fiscal's line limit).
 *
 * <p>R3 (D84, for reporting's dimensions): the item and its code, the catalog release the line was priced from (the
 * family is resolved from it), and who confirmed the line when ({@code servedByEmployeeId}: the round's confirmer;
 * for a line transferred in, the employee who made the transfer).
 */
public record BillingLine(UUID lineId, String description, BigDecimal netQty, Money unitPrice, Money gross,
                          Money net, Money tax, String taxCode, BigDecimal ratePercent, UUID itemId, String itemCode,
                          UUID releaseId, UUID servedByEmployeeId, Instant confirmedAt) {
}
