package com.xavier.backend.customers;

import com.xavier.backend.shared.Money;

/**
 * A customer's credit now (D73): {@code balance} = Σ DEBIT − Σ CREDIT of the ledger (never stored), {@code reserved}
 * = Σ ACTIVE reservations, {@code available} = limit − balance − reserved (negative when the limit was lowered below
 * the debt).
 */
public record CreditView(Money limit, Money balance, Money reserved, Money available, boolean blocked) {
}
