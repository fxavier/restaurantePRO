package com.xavier.backend.fiscal.model.dto;

import com.xavier.backend.shared.Money;
import java.util.List;
import java.util.UUID;

/**
 * The sale document of one order (P8), built by settlement from the frozen order (D55). The totals must be the
 * sums of the lines. {@code buyer} may be {@code null}. R2's shape of an {@code ORDER} {@link DraftCommand}: no
 * business day, no customer, not on account.
 */
public record SaleDraftCommand(UUID storeId, UUID orderId, Buyer buyer, List<DraftLine> lines, Money totalGross,
                               Money totalNet, Money totalTax) {

    public DraftCommand toDraftCommand() {
        return new DraftCommand(storeId, SourceType.ORDER, orderId, orderId, null, buyer, null, false, lines,
                totalGross, totalNet, totalTax);
    }
}
