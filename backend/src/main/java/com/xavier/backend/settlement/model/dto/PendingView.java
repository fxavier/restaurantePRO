package com.xavier.backend.settlement.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * What still needs someone in a store ({@code GET /backoffice/settlement/pending}): payment attempts still open or in
 * doubt, fiscal drafts not issued, and orders already settled that are still OPEN.
 */
public record PendingView(UUID storeId, List<AttemptView> inDoubtAttempts, List<FiscalDraftView> fiscalDrafts,
                          List<OpenOrderBalance> settledOpenOrders) {
}
