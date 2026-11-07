package com.xavier.backend.orders;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The discounts one source recorded (D100, D101, for reporting): a round's promoted lines ({@code BATCH}) or one price
 * adjustment ({@code ADJUSTMENT}). An immutable fact, so a pull returns the same facts every time. {@code businessDay}
 * is the order's.
 */
public record DiscountFacts(String sourceType, UUID sourceId, UUID orderId, UUID storeId, LocalDate businessDay,
                           List<DiscountLine> lines) {

    /**
     * One discounted line: its catalog {@code listUnitPrice}, the price before this source ({@code previousUnitPrice},
     * the list price for a round) and after it ({@code unitPrice}), the quantity it applied to, and
     * {@code discountGross} = {@link LineAmounts#of} gross at the previous price − at the new one, for that quantity
     * (negative for a REVERSAL). {@code origin} is {@code PROMOTION}, {@code MANUAL} (a discount), {@code OFFER} or
     * {@code LOYALTY} (a reward);
     * the promotion fields are set for {@code PROMOTION}. {@code operatorEmployeeId} is who confirmed the round or
     * made the adjustment, {@code approverEmployeeId} who approved it ({@code null} when nobody had to).
     * {@code consumedLineId} (S53, as {@link VoidFacts#consumedLineId()}) is the round line the discounted line
     * descends from through splits and transfers, whose consumption carries its cost, and
     * {@code consumedLineQuantity} that round line's confirmed quantity, so a share of its cost can be taken.
     * {@code reversesOrigin} (S53) is set on a REVERSAL only: the origin of the price it takes back, that of the
     * line's latest earlier adjustment that was not a reversal ({@code PROMOTION} for a promoted line without one,
     * else {@code MANUAL}).
     */
    public record DiscountLine(UUID lineId, UUID itemId, String itemCode, UUID releaseId, String taxCode,
                               BigDecimal taxRatePercent, Money listUnitPrice, Money previousUnitPrice,
                               Money unitPrice, BigDecimal quantity, Money discountGross, String origin,
                               UUID promotionId, String promotionCode, String promotionName,
                               UUID operatorEmployeeId, UUID approverEmployeeId, Instant at,
                               UUID consumedLineId, BigDecimal consumedLineQuantity, String reversesOrigin) {
    }
}
