package com.xavier.backend.fiscal.model.dto;

import com.xavier.backend.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A SALE draft (D66, D70), built by settlement. {@code sourceType} is {@code ORDER} (the residual document at close,
 * or R2's one document per order) or {@code PARTITION} (an ITEMS partition invoiced on its own). {@code orderId} is
 * the order the document belongs to: required for a PARTITION, and for an ORDER either {@code null} or its
 * {@code sourceId}. {@code businessDate} is the order's business day (D85); {@code null} falls back to the day of
 * issue. {@code buyer} is the customer snapshot (D65) and may be {@code null}; {@code onAccount} (a credit sale)
 * needs {@code customerId}. The totals must be the sums of the lines (D59, see {@link DraftLine}).
 */
public record DraftCommand(UUID storeId, SourceType sourceType, UUID sourceId, UUID orderId, LocalDate businessDate,
                           Buyer buyer, UUID customerId, boolean onAccount, List<DraftLine> lines, Money totalGross,
                           Money totalNet, Money totalTax) {
}
