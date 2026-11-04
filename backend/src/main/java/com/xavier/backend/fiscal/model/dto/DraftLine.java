package com.xavier.backend.fiscal.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One document line, copied from a confirmed order line's snapshot (spec §8.4, D59): {@code quantity} is the net
 * billable quantity, {@code gross = unitPrice × quantity}, {@code net + tax = gross}, all already rounded by the
 * caller. {@code taxRatePercent} is e.g. 16 for 16 %. Fiscal checks these sums and never re-rounds.
 */
public record DraftLine(UUID sourceLineId, String description, BigDecimal quantity, Money unitPrice,
                        BigDecimal taxRatePercent, Money gross, Money net, Money tax) {
}
