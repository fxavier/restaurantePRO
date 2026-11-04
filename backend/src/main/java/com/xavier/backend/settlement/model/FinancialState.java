package com.xavier.backend.settlement.model;

import com.xavier.backend.shared.Money;

/**
 * The financial state of an order (spec §8.3): derived from the due and the payments, never stored. R2 has no credit.
 */
public enum FinancialState {
    UNSETTLED,
    PARTIALLY_SETTLED,
    SETTLED;

    public static FinancialState of(Money due, Money paid) {
        int comparison = paid.amount().compareTo(due.amount());
        if (comparison >= 0) {
            return SETTLED;
        }
        return paid.amount().signum() == 0 ? UNSETTLED : PARTIALLY_SETTLED;
    }
}
