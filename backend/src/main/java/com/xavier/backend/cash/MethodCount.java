package com.xavier.backend.cash;

import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.shared.Money;
import java.util.Objects;

/** The blind count of one payment method at session close; {@code methodCode} is settlement's method code (D52). */
public record MethodCount(String methodCode, Money amount) {

    static final int MAX_METHOD_CODE = 30;

    public MethodCount {
        requireMethodCode(methodCode);
        Objects.requireNonNull(amount, "amount");
    }

    /** A payment method code as the cash columns hold it ({@code VARCHAR(30)}), else 422 {@code INVALID_METHOD}. */
    static String requireMethodCode(String methodCode) {
        Objects.requireNonNull(methodCode, "methodCode");
        if (methodCode.isBlank() || methodCode.length() > MAX_METHOD_CODE) {
            throw new CashRuleViolationException(CashRule.INVALID_METHOD,
                    "A payment method code must be 1 to " + MAX_METHOD_CODE + " characters.");
        }
        return methodCode;
    }
}
