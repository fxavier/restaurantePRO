package com.xavier.backend.promotions;

import com.xavier.backend.promotions.model.dto.AppliedPromotion;
import com.xavier.backend.promotions.model.dto.PricedLine;
import com.xavier.backend.promotions.model.dto.PricingLine;
import com.xavier.backend.promotions.model.dto.PricingRequest;
import com.xavier.backend.promotions.model.dto.PricingResult;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot.Promotion;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot.Scope;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot.Window;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Optional;

/**
 * The pure pricing rule of promotions (D101, D103, P43): no clock, no database. For each line it keeps the promotions
 * that are valid at the request instant, inside one of their windows (read in the store's {@code zone}), for the
 * order's mode, for members when they say so, and whose scope names the line's item, variant or family. Exactly one
 * applies: the highest {@code priority}, then the lowest resulting price, then the lowest {@code code}. Promotions do
 * not stack.
 *
 * <p>The result is an effective unit price with {@code 0 ≤ price ≤ list}: a percent is applied per unit and rounded
 * HALF_UP to the currency's minor unit (10 % off 1.25 is 1.13), an amount off stops at 0, and a fixed price above the
 * list price makes that promotion ineligible.
 */
public final class PromotionEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PromotionEngine() {
    }

    public static PricingResult evaluate(PromotionSnapshot snapshot, PricingRequest request, ZoneId zone) {
        LocalDateTime local = LocalDateTime.ofInstant(request.at(), zone);
        return new PricingResult(snapshot.releaseId(), snapshot.version(), request.lines().stream()
                .map(line -> price(snapshot, request, local, line))
                .toList());
    }

    private static PricedLine price(PromotionSnapshot snapshot, PricingRequest request, LocalDateTime local,
                                    PricingLine line) {
        Money list = line.listUnitPrice();
        return snapshot.promotions().stream()
                .filter(promotion -> eligible(promotion, request, local, line))
                .flatMap(promotion -> candidate(promotion, list).stream())
                .min(Comparator.comparingInt((Candidate candidate) -> candidate.promotion().priority()).reversed()
                        .thenComparing(candidate -> candidate.price().amount())
                        .thenComparing(candidate -> candidate.promotion().code()))
                .map(best -> new PricedLine(line.key(), list, best.price(), applied(best.promotion())))
                .orElseGet(() -> new PricedLine(line.key(), list, list, null));
    }

    private static boolean eligible(Promotion promotion, PricingRequest request, LocalDateTime local,
                                    PricingLine line) {
        return valid(promotion, request.at())
                && (promotion.windows().isEmpty() || promotion.windows().stream().anyMatch(w -> inside(w, local)))
                && (promotion.modes() == null || promotion.modes().contains(request.modeCode()))
                && (!promotion.membersOnly() || request.member())
                && inScope(promotion.scope(), line);
    }

    private static boolean valid(Promotion promotion, Instant at) {
        return (promotion.validFrom() == null || !at.isBefore(promotion.validFrom()))
                && (promotion.validTo() == null || at.isBefore(promotion.validTo()));
    }

    /** A window belongs to the day it starts on; one whose end is before its start runs into the next day. */
    private static boolean inside(Window window, LocalDateTime local) {
        LocalTime time = local.toLocalTime();
        int today = local.getDayOfWeek().getValue();
        if (window.start().isBefore(window.end())) {
            return window.daysOfWeek().contains(today) && !time.isBefore(window.start()) && time.isBefore(window.end());
        }
        int yesterday = local.getDayOfWeek().minus(1).getValue();
        return (window.daysOfWeek().contains(today) && !time.isBefore(window.start()))
                || (window.daysOfWeek().contains(yesterday) && time.isBefore(window.end()));
    }

    private static boolean inScope(Scope scope, PricingLine line) {
        return scope.itemIds().contains(line.itemId())
                || (line.variantId() != null && scope.variantIds().contains(line.variantId()))
                || (line.familyId() != null && scope.familyIds().contains(line.familyId()));
    }

    private static Optional<Candidate> candidate(Promotion promotion, Money list) {
        Money price = switch (promotion.kind()) {
            case "PERCENT_OFF" -> list.times(BigDecimal.ONE.subtract(new BigDecimal(promotion.percent()).divide(HUNDRED)));
            case "AMOUNT_OFF_UNIT" -> atLeastZero(list.minus(Money.of(promotion.amount(), list.currency())));
            case "FIXED_UNIT_PRICE" -> Money.of(promotion.amount(), list.currency());
            default -> throw new IllegalArgumentException("Unknown promotion kind " + promotion.kind());
        };
        if (price.amount().compareTo(list.amount()) > 0 || price.amount().signum() < 0) {
            return Optional.empty(); // an "offer" above the list price is ignored (D103)
        }
        return Optional.of(new Candidate(promotion, price));
    }

    private static Money atLeastZero(Money money) {
        return money.amount().signum() < 0 ? Money.of(BigDecimal.ZERO, money.currency()) : money;
    }

    private static AppliedPromotion applied(Promotion promotion) {
        return new AppliedPromotion(promotion.id(), promotion.code(), promotion.name(), promotion.kind());
    }

    private record Candidate(Promotion promotion, Money price) {
    }
}
