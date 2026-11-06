package com.xavier.backend.promotions.loyalty.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.Map;
import java.util.UUID;

/** A loyalty business rule refused the request: 422 with the stable {@code code} (PRD R4 conventions). */
public class LoyaltyRuleException extends BusinessRuleException {

    private final LoyaltyRule rule;

    public LoyaltyRuleException(LoyaltyRule rule, String detail) {
        this(rule, detail, Map.of());
    }

    public LoyaltyRuleException(LoyaltyRule rule, String detail, Map<String, ?> properties) {
        super(rule.name(), detail, properties);
        this.rule = rule;
    }

    public static LoyaltyRuleException notMember(UUID customerId) {
        return new LoyaltyRuleException(LoyaltyRule.LOYALTY_NOT_MEMBER,
                "Customer " + customerId + " is not a loyalty member.");
    }

    public static LoyaltyRuleException suspended(UUID customerId) {
        return new LoyaltyRuleException(LoyaltyRule.LOYALTY_ACCOUNT_SUSPENDED,
                "The loyalty account of customer " + customerId + " is suspended.");
    }

    public static LoyaltyRuleException insufficientPoints(long balance, int required) {
        return new LoyaltyRuleException(LoyaltyRule.LOYALTY_INSUFFICIENT_POINTS, "The balance of " + balance
                + " points is less than the " + required + " needed.",
                Map.of("balance", balance, "required", required));
    }

    public static LoyaltyRuleException rewardNotApplicable(String rewardCode, String why) {
        return new LoyaltyRuleException(LoyaltyRule.LOYALTY_REWARD_NOT_APPLICABLE,
                "The reward " + rewardCode + " cannot be redeemed here: " + why + ".");
    }

    public static LoyaltyRuleException programInvalid(String detail) {
        return new LoyaltyRuleException(LoyaltyRule.LOYALTY_PROGRAM_INVALID, detail);
    }

    public static LoyaltyRuleException reasonRequired(String action) {
        return new LoyaltyRuleException(LoyaltyRule.REASON_REQUIRED, "A " + action + " needs a reason.");
    }

    public LoyaltyRule rule() {
        return rule;
    }
}
