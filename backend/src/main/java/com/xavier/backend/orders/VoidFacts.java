package com.xavier.backend.orders;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A void as reporting projects it (D84): an immutable fact, so a pull returns the same facts every time.
 * {@code businessDay} is the order's; {@code gross} is {@link LineAmounts#of} of the voided quantity at the line's
 * snapshot; {@code afterDispatch} says production had received the line's round when the void was recorded (for a
 * line transferred in, the round it came from on the source order). {@code consumedLineId} (D100) is the round line
 * the voided line descends from through transfers: the voided line itself when it was confirmed in a round, else
 * the source line its transfers lead back to, so inventory reverses the consumption it posted for that line.
 */
public record VoidFacts(UUID voidId, UUID orderId, UUID storeId, LocalDate businessDay, Instant at,
                        VoidedLine lineSnapshot, BigDecimal quantity, Money gross, String reason, boolean wasted,
                        UUID operatorEmployeeId, UUID approverEmployeeId, boolean afterDispatch, UUID consumedLineId) {

    /** The R3 components; {@code consumedLineId} is unknown (null). */
    public VoidFacts(UUID voidId, UUID orderId, UUID storeId, LocalDate businessDay, Instant at,
                     VoidedLine lineSnapshot, BigDecimal quantity, Money gross, String reason, boolean wasted,
                     UUID operatorEmployeeId, UUID approverEmployeeId, boolean afterDispatch) {
        this(voidId, orderId, storeId, businessDay, at, lineSnapshot, quantity, gross, reason, wasted,
                operatorEmployeeId, approverEmployeeId, afterDispatch, null);
    }

    /**
     * What the voided line kept from the catalog at confirmation: {@code description} is the billed one (with the
     * variant in parentheses), {@code releaseId} the catalog release the family is resolved from.
     */
    public record VoidedLine(UUID lineId, UUID itemId, String itemCode, String description, Money unitPrice,
                             String taxCode, BigDecimal taxRatePercent, UUID releaseId) {
    }
}
