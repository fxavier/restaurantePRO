package com.xavier.backend.promotions.loyalty.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A member's entries in {@code [from, to]}: the balance before, each line with its running balance, the balance after. */
public record StatementView(UUID customerId, Instant from, Instant to, long openingBalance, List<StatementLine> lines,
                            long closingBalance) {
}
