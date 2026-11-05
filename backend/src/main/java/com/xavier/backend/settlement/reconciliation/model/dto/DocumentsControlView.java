package com.xavier.backend.settlement.reconciliation.model.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /backoffice/reconciliation/documents} (D80): the exceptions of a store's business day, computed from its
 * facts when asked (none stored), and the tax rounding differences they explain away (D68 amendment).
 */
public record DocumentsControlView(UUID storeId, LocalDate businessDate, List<DocumentException> exceptions,
                                   List<ExplainedRoundingDifference> roundingDifferences) {
}
