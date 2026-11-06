package com.xavier.backend.promotions.exception;

import com.xavier.backend.promotions.model.dto.PromotionReleaseError;
import com.xavier.backend.shared.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** A promotions business rule refused the request: 422 with the stable {@code code}. */
public class PromotionRuleException extends BusinessRuleException {

    private final PromotionRule rule;

    public PromotionRuleException(PromotionRule rule, String detail) {
        this(rule, detail, Map.of());
    }

    public PromotionRuleException(PromotionRule rule, String detail, Map<String, ?> properties) {
        super(rule.name(), detail, properties);
        this.rule = rule;
    }

    public static PromotionRuleException invalid(String detail) {
        return new PromotionRuleException(PromotionRule.PROMOTION_INVALID, detail);
    }

    public static PromotionRuleException limitsInvalid(BigDecimal freeLimitPercent, BigDecimal maxPercent) {
        return new PromotionRuleException(PromotionRule.DISCOUNT_LIMITS_INVALID, "The discount limits "
                + freeLimitPercent + " % free and " + maxPercent + " % maximum need 0 <= free <= maximum <= 100.");
    }

    public static PromotionRuleException releaseInvalid(List<PromotionReleaseError> errors) {
        return new PromotionRuleException(PromotionRule.PROMOTION_RELEASE_INVALID,
                "The promotions cannot be published: " + errors.size() + " problem(s).",
                Map.of("errors", List.copyOf(errors)));
    }

    public PromotionRule rule() {
        return rule;
    }
}
