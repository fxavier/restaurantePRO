package com.xavier.backend.orders.model;

import com.xavier.backend.orders.AdjustmentKind;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * A confirmed line's price now (D101, D105): its latest adjustment's unit price, else the effective price it was
 * confirmed at; its list price; where the discount comes from ({@code PROMOTION}, {@code MANUAL}, {@code OFFER},
 * {@code LOYALTY}, §8.4 "guardar origem") and the label a bill prints for it. Origin and label are {@code null}
 * when the line sells at its list price.
 */
public record EffectivePrice(Money listUnitPrice, Money unitPrice, String origin, String label) {

    /** @param adjustments the line's adjustments in {@code seq} order */
    public static EffectivePrice of(OrderLine line, List<OrderLinePriceAdjustment> adjustments) {
        return of(line, adjustments, null);
    }

    /**
     * @param inheritedRewardCode the reward a copy carries from the line that holds it (D112a); it labels a line that
     *                            has no adjustment of its own, {@code null} otherwise
     */
    public static EffectivePrice of(OrderLine line, List<OrderLinePriceAdjustment> adjustments,
                                    String inheritedRewardCode) {
        return of(line, adjustments, inheritedRewardCode, null);
    }

    /**
     * @param lineageOrigin the latest adjustment that is not a reversal in the line's lineage (its own, else its
     *                      nearest ancestor's before the copy, S53): the origin and label of a copy without an
     *                      adjustment of its own, or of a line whose latest adjustment is a reversal; {@code null}
     *                      when not read or there is none
     */
    public static EffectivePrice of(OrderLine line, List<OrderLinePriceAdjustment> adjustments,
                                    String inheritedRewardCode, OrderLinePriceAdjustment lineageOrigin) {
        Money list = line.listUnitPrice();
        Optional<OrderLinePriceAdjustment> latest = adjustments.isEmpty() ? Optional.empty()
                : Optional.of(adjustments.getLast());
        Money unit = latest.map(OrderLinePriceAdjustment::unitPrice)
                .orElseGet(() -> line.snapshot().orElseThrow().unitPrice());
        if (unit.equals(list)) {
            return new EffectivePrice(list, unit, null, null);
        }
        boolean reversed = latest.filter(last -> last.getKind() == AdjustmentKind.REVERSAL).isPresent();
        if ((latest.isEmpty() || reversed) && lineageOrigin != null) {
            // after a reversal the price is not the earlier adjustment's own, so its percent is not shown
            return ofAdjustment(list, unit, lineageOrigin, !reversed);
        }
        if (latest.isEmpty() && inheritedRewardCode != null) {
            return new EffectivePrice(list, unit, "LOYALTY", "Recompensa " + inheritedRewardCode);
        }
        if (latest.isEmpty()) {
            return line.promotion().map(promotion -> new EffectivePrice(list, unit, "PROMOTION", promotion.name()))
                    .orElseGet(() -> new EffectivePrice(list, unit, "MANUAL", "Desconto"));
        }
        return ofAdjustment(list, unit, latest.get(), true);
    }

    private static EffectivePrice ofAdjustment(Money list, Money unit, OrderLinePriceAdjustment last,
                                               boolean withPercent) {
        return switch (last.getKind()) {
            case OFFER -> new EffectivePrice(list, unit, "OFFER", "Oferta");
            case REWARD -> new EffectivePrice(list, unit, "LOYALTY", "Recompensa " + last.getRewardCode());
            case DISCOUNT -> new EffectivePrice(list, unit, "MANUAL", last.getPercent() == null || !withPercent
                    ? "Desconto" : "Desconto " + last.getPercent().stripTrailingZeros().toPlainString() + "%");
            case REVERSAL -> new EffectivePrice(list, unit, "MANUAL", "Desconto");
        };
    }

    /** D101: {@code percent} off {@code price}, HALF_UP per unit to the minor unit; within {@code [0, price]}. */
    public static Money afterPercentOff(Money price, BigDecimal percent) {
        if (percent.signum() <= 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("A percent off is in (0, 100]: " + percent);
        }
        return price.times(BigDecimal.ONE.subtract(percent.movePointLeft(2)));
    }

    /** The cumulative discount against the list price in percent, {@code 1 − unit/list} (D106); 0 for a free item. */
    public static BigDecimal percentOff(Money listUnitPrice, Money unitPrice) {
        if (listUnitPrice.amount().signum() == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.ONE.subtract(unitPrice.amount().divide(listUnitPrice.amount(), 10,
                java.math.RoundingMode.HALF_UP)).movePointRight(2);
    }
}
