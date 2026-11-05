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
 *
 * <p>{@code amountsAllocated} (D68 amendment, S28): the lines carry settlement's allocated shares (every PARTITION
 * draft, and the residual ORDER draft of an order that has or had partitions). Their amounts are checked within a
 * bound instead of D59's exact recomputation: gross within 1 minor unit of {@code round(unitPrice × quantity)}, net
 * within 2 of {@code round(gross / (1 + rate/100))}, {@code net + tax = gross} exactly, no negative amount. It is part
 * of the draft's content: a redraft with the other flag is a {@code DRAFT_MISMATCH}.
 */
public record DraftCommand(UUID storeId, SourceType sourceType, UUID sourceId, UUID orderId, LocalDate businessDate,
                           Buyer buyer, UUID customerId, boolean onAccount, List<DraftLine> lines, Money totalGross,
                           Money totalNet, Money totalTax, boolean amountsAllocated) {

    /** A draft whose lines follow D59 exactly ({@code amountsAllocated = false}). */
    public DraftCommand(UUID storeId, SourceType sourceType, UUID sourceId, UUID orderId, LocalDate businessDate,
                        Buyer buyer, UUID customerId, boolean onAccount, List<DraftLine> lines, Money totalGross,
                        Money totalNet, Money totalTax) {
        this(storeId, sourceType, sourceId, orderId, businessDate, buyer, customerId, onAccount, lines, totalGross,
                totalNet, totalTax, false);
    }
}
