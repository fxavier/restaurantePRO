package com.xavier.backend.customers.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A customer's statement over {@code [from, to]}: the balance before, every entry, and the balance after. */
public record StatementView(UUID customerId, Instant from, Instant to, Money openingBalance, List<StatementLine> lines,
                            Money closingBalance) {
}
