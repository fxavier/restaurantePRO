package com.xavier.backend.settlement.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * What still needs someone in a store ({@code GET /backoffice/settlement/pending}): payment attempts still open or in
 * doubt, fiscal drafts not issued (an order's, or a partition's), orders already settled that are still OPEN, and
 * credit charges still RESERVED (D74: their document is not issued, or their debit is not posted yet).
 */
public record PendingView(UUID storeId, List<AttemptView> inDoubtAttempts, List<FiscalDraftView> fiscalDrafts,
                          List<OpenOrderBalance> settledOpenOrders, List<CreditChargeView> reservedCreditCharges) {
}
