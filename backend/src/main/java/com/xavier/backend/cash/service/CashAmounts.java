package com.xavier.backend.cash.service;

import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.repository.MethodTotal;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Parses request amounts into {@link Money} once, at the boundary, and turns query sums into Money. */
final class CashAmounts {

    private CashAmounts() {
    }

    /** @throws CashRuleViolationException {@code INVALID_AMOUNT} for more decimals than the currency allows */
    static Money of(BigDecimal amount, Currency currency) {
        try {
            return Money.of(amount, currency);
        } catch (IllegalArgumentException e) {
            throw new CashRuleViolationException(CashRule.INVALID_AMOUNT,
                    amount.toPlainString() + " has more decimals than " + currency.getCurrencyCode() + " allows.");
        }
    }

    static Map<String, Money> byMethod(List<MethodTotal> totals, Currency currency) {
        Map<String, Money> byMethod = new TreeMap<>();
        totals.forEach(total -> byMethod.put(total.methodCode(), Money.of(total.amount(), currency)));
        return byMethod;
    }
}
