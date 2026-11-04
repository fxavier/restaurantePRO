package com.xavier.backend.orders.model;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a line keeps from the catalog when it is confirmed (spec §8.4, D58). {@code variantName} is {@code null} for
 * the item itself and {@code destination} when nothing prepares it (D56).
 */
public record LineSnapshot(String itemCode, String description, String variantName, Money unitPrice,
                           UUID taxCategoryId, String taxCode, BigDecimal taxRatePercent, UUID releaseId,
                           long catalogVersion, String destination) {

    /** A fiscal document line holds at most this many characters ({@code fiscal_document_line.description}). */
    public static final int MAX_BILLED_DESCRIPTION = 200;

    /**
     * What a bill and its document print: the name, with the variant in parentheses when there is one, cut with an
     * ellipsis at {@value #MAX_BILLED_DESCRIPTION} characters so that a long name never blocks the invoice.
     */
    public String billedDescription() {
        String full = variantName == null ? description : description + " (" + variantName + ")";
        return full.length() <= MAX_BILLED_DESCRIPTION ? full : full.substring(0, MAX_BILLED_DESCRIPTION - 1) + "…";
    }
}
