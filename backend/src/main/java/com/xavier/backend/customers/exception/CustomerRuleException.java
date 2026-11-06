package com.xavier.backend.customers.exception;

import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.Map;
import java.util.UUID;

/** A customers business rule refused the request: 422 with the stable {@code code} (PRD R3 conventions). */
public class CustomerRuleException extends BusinessRuleException {

    private final CustomerRule rule;

    public CustomerRuleException(CustomerRule rule, String detail) {
        this(rule, detail, Map.of());
    }

    public CustomerRuleException(CustomerRule rule, String detail, Map<String, ?> properties) {
        super(rule.name(), detail, properties);
        this.rule = rule;
    }

    public static CustomerRuleException inactive(UUID customerId) {
        return new CustomerRuleException(CustomerRule.CUSTOMER_INACTIVE, "Customer " + customerId + " is inactive.");
    }

    public static CustomerRuleException accountMissing(UUID customerId) {
        return new CustomerRuleException(CustomerRule.CREDIT_ACCOUNT_MISSING,
                "Customer " + customerId + " has no credit account.");
    }

    public static CustomerRuleException blocked(UUID customerId) {
        return new CustomerRuleException(CustomerRule.CREDIT_BLOCKED,
                "The credit of customer " + customerId + " is blocked.");
    }

    /** {@code available} may be negative when the limit was lowered below the debt. */
    public static CustomerRuleException limitExceeded(Money amount, Money available) {
        return new CustomerRuleException(CustomerRule.CREDIT_LIMIT_EXCEEDED, "The amount " + amount.amount()
                + " is more than the available credit " + available.amount() + ".",
                Map.of("available", available.amount()));
    }

    public static CustomerRuleException exceedsDebt(Money amount, Money debt) {
        return new CustomerRuleException(CustomerRule.AMOUNT_EXCEEDS_DEBT, "The amount " + amount.amount()
                + " is more than the open debt " + debt.amount() + ".", Map.of("debt", debt.amount()));
    }

    public static CustomerRuleException exceedsCredit(Money amount, Money refundable) {
        return new CustomerRuleException(CustomerRule.AMOUNT_EXCEEDS_CREDIT, "The amount " + amount.amount()
                + " is more than the " + refundable.amount() + " of the debt payment still to reverse.",
                Map.of("refundable", refundable.amount()));
    }

    public static CustomerRuleException invalidAmount(String detail) {
        return new CustomerRuleException(CustomerRule.INVALID_AMOUNT, detail);
    }

    public static CustomerRuleException invalidPeriod(Object from, Object to) {
        return new CustomerRuleException(CustomerRule.INVALID_PERIOD, "The period starts (" + from
                + ") after it ends (" + to + ").");
    }

    public CustomerRule rule() {
        return rule;
    }
}
