package com.xavier.backend.orders;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A confirmed line as settlement bills it and fiscal copies it into a {@code DraftLine} (D59): {@code netQty} is the
 * quantity still billable (confirmed − voided − transferred out − split out, {@code > 0}), the amounts are
 * {@link LineAmounts#of}{@code (unitPrice, netQty, ratePercent)}. {@code description} is the snapshot name, with the
 * variant name in parentheses when there is one, at most 200 characters (fiscal's line limit).
 *
 * <p>R3 (D84, for reporting's dimensions): the item and its code, the catalog release the line was priced from (the
 * family is resolved from it), and who confirmed the line when ({@code servedByEmployeeId}: the round's confirmer;
 * for a line transferred in, the employee who made the transfer).
 *
 * <p>R4 (D101, D107): {@code unitPrice} is the <em>effective</em> price, after promotions and adjustments;
 * {@code listUnitPrice} is the catalog's and {@code discountLabel} what a document prints for the difference
 * ({@code null} at list price). An R3 line reads its unit price as its list price.
 */
public record BillingLine(UUID lineId, String description, BigDecimal netQty, Money unitPrice, Money gross,
                          Money net, Money tax, String taxCode, BigDecimal ratePercent, UUID itemId, String itemCode,
                          UUID releaseId, UUID servedByEmployeeId, Instant confirmedAt, Money listUnitPrice,
                          String discountLabel) {

    /** The R3 components: at list price, no label. */
    public BillingLine(UUID lineId, String description, BigDecimal netQty, Money unitPrice, Money gross, Money net,
                       Money tax, String taxCode, BigDecimal ratePercent, UUID itemId, String itemCode,
                       UUID releaseId, UUID servedByEmployeeId, Instant confirmedAt) {
        this(lineId, description, netQty, unitPrice, gross, net, tax, taxCode, ratePercent, itemId, itemCode,
                releaseId, servedByEmployeeId, confirmedAt, unitPrice, null);
    }
}
