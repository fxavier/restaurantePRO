package com.xavier.backend.orders.model;

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
        Money list = line.listUnitPrice();
        Optional<OrderLinePriceAdjustment> latest = adjustments.isEmpty() ? Optional.empty()
                : Optional.of(adjustments.getLast());
        Money unit = latest.map(OrderLinePriceAdjustment::unitPrice)
                .orElseGet(() -> line.snapshot().orElseThrow().unitPrice());
        if (unit.equals(list)) {
            return new EffectivePrice(list, unit, null, null);
        }
        if (latest.isEmpty()) {
            return line.promotion().map(promotion -> new EffectivePrice(list, unit, "PROMOTION", promotion.name()))
                    .orElseGet(() -> new EffectivePrice(list, unit, "MANUAL", "Desconto"));
        }
        OrderLinePriceAdjustment last = latest.get();
        return switch (last.getKind()) {
            case OFFER -> new EffectivePrice(list, unit, "OFFER", "Oferta");
            case REWARD -> new EffectivePrice(list, unit, "LOYALTY", "Recompensa " + last.getRewardCode());
            case DISCOUNT -> new EffectivePrice(list, unit, "MANUAL", last.getPercent() == null ? "Desconto"
                    : "Desconto " + last.getPercent().stripTrailingZeros().toPlainString() + "%");
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
