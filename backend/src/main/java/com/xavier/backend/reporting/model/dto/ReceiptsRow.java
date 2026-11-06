package com.xavier.backend.reporting.model.dto;

import com.xavier.backend.shared.Money;

/**
 * Money that went through the till, in the cash day of its session (D85, D87). {@code received} is payments −
 * refunds (debt payments included, as their cash movements are); {@code debtCollected} is the part of it paid
 * against a customer's account; {@code onAccount} is credit sales, which are never money received. A credit sale
 * has no method or collecting employee, so {@code onAccount} is zero in those rows and shown in the totals.
 */
public record ReceiptsRow(String key, String label, Money received, Money onAccount, Money debtCollected) {
}
