package com.xavier.backend.promotions;

import com.xavier.backend.promotions.model.dto.DiscountLimits;
import com.xavier.backend.promotions.model.dto.PricingLine;
import com.xavier.backend.promotions.model.dto.PricingRequest;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot.Promotion;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot.Scope;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot.Window;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** Builders for pure engine tests: fixed ids, MZN, one store. */
final class EngineFixtures {

    static final Currency MZN = Currency.getInstance("MZN");
    static final UUID STORE = UUID.fromString("0190a000-0000-7000-8000-000000000501");
    static final UUID RELEASE = UUID.fromString("0190a000-0000-7000-8000-000000000502");
    static final UUID ITEM = UUID.fromString("0190a000-0000-7000-8000-000000000503");
    static final UUID VARIANT = UUID.fromString("0190a000-0000-7000-8000-000000000504");
    static final UUID FAMILY = UUID.fromString("0190a000-0000-7000-8000-000000000505");
    static final UUID OTHER = UUID.fromString("0190a000-0000-7000-8000-000000000506");
    static final Instant AT = Instant.parse("2026-11-06T12:00:00Z");

    private EngineFixtures() {
    }

    static Money mzn(String amount) {
        return Money.of(amount, MZN);
    }

    static PromotionSnapshot snapshot(Promotion... promotions) {
        return new PromotionSnapshot(PromotionSnapshot.SCHEMA_VERSION, STORE, RELEASE, 7, "MZN",
                new DiscountLimits(new BigDecimal("10"), new BigDecimal("50")), List.of(promotions));
    }

    static Scope items(UUID... ids) {
        return new Scope(List.of(ids), List.of(), List.of());
    }

    static Scope variants(UUID... ids) {
        return new Scope(List.of(), List.of(ids), List.of());
    }

    static Scope families(UUID... ids) {
        return new Scope(List.of(), List.of(), List.of(ids));
    }

    static Promotion percentOff(String code, String percent, int priority, Scope scope) {
        return promotion(code, "PERCENT_OFF", percent, null, priority, scope);
    }

    static Promotion amountOff(String code, String amount, int priority, Scope scope) {
        return promotion(code, "AMOUNT_OFF_UNIT", null, amount, priority, scope);
    }

    static Promotion fixedPrice(String code, String amount, int priority, Scope scope) {
        return promotion(code, "FIXED_UNIT_PRICE", null, amount, priority, scope);
    }

    static Promotion promotion(String code, String kind, String percent, String amount, int priority, Scope scope) {
        return new Promotion(UUID.nameUUIDFromBytes(code.getBytes()), code, "Promo " + code, kind, percent, amount,
                priority, null, null, false, null, scope, List.of());
    }

    static Promotion withWindows(Promotion p, Window... windows) {
        return new Promotion(p.id(), p.code(), p.name(), p.kind(), p.percent(), p.amount(), p.priority(),
                p.validFrom(), p.validTo(), p.membersOnly(), p.modes(), p.scope(), List.of(windows));
    }

    static Promotion withValidity(Promotion p, Instant from, Instant to) {
        return new Promotion(p.id(), p.code(), p.name(), p.kind(), p.percent(), p.amount(), p.priority(),
                from, to, p.membersOnly(), p.modes(), p.scope(), p.windows());
    }

    static Promotion withModes(Promotion p, String... modes) {
        return new Promotion(p.id(), p.code(), p.name(), p.kind(), p.percent(), p.amount(), p.priority(),
                p.validFrom(), p.validTo(), p.membersOnly(), List.of(modes), p.scope(), p.windows());
    }

    static Promotion membersOnly(Promotion p) {
        return new Promotion(p.id(), p.code(), p.name(), p.kind(), p.percent(), p.amount(), p.priority(),
                p.validFrom(), p.validTo(), true, p.modes(), p.scope(), p.windows());
    }

    static PricingLine line(String listPrice) {
        return line(ITEM, null, FAMILY, listPrice);
    }

    static PricingLine line(UUID item, UUID variant, UUID family, String listPrice) {
        return new PricingLine(UUID.randomUUID(), item, variant, family, mzn(listPrice), BigDecimal.ONE);
    }

    static PricingRequest request(Instant at, PricingLine... lines) {
        return new PricingRequest(STORE, at, "DINE_IN", false, List.of(lines));
    }

    static PricingRequest request(PricingLine... lines) {
        return request(AT, lines);
    }
}
